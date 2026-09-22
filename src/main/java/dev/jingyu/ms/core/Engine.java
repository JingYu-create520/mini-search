package dev.jingyu.ms.core;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Lexicon;
import dev.jingyu.ms.analyzer.TermMining;
import dev.jingyu.ms.index.Doc;
import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.ranking.Bm25;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Log;
import dev.jingyu.ms.vector.BruteForce;
import dev.jingyu.ms.vector.EmbeddingModel;
import dev.jingyu.ms.vector.Encoder;
import dev.jingyu.ms.vector.Hnsw;
import dev.jingyu.ms.vector.Idf;
import dev.jingyu.ms.vector.VectorIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole engine in one object: lexicon, analyser, inverted index, BM25, embeddings, k-NN, and the
 * searcher that combines them. The HTTP API, the MCP server and the eval harness are all thin
 * wrappers over this class, which is why they can all be read in one sitting.
 *
 * <p>Build order is not arbitrary:
 * <ol>
 *   <li>mine new words from the raw text (needs no segmentation, only character n-grams);</li>
 *   <li>seal the lexicon, then build the inverted index;</li>
 *   <li>train word vectors on the tokens the index just produced;</li>
 *   <li>encode every document and publish the k-NN structure.</li>
 * </ol>
 * Reversing 1 and 2 would mean segmenting with a dictionary you have not mined yet, which is exactly
 * the circular dependency that makes hand-written Chinese search engines annoying to build.
 */
public final class Engine {

    public static final class Options {
        public boolean stopwords;
        public boolean mine = true;
        public String dictPath;              // optional user-supplied dictionary file
        public int mineMinFreq = 2;
        public double mineMinCohesion = 0.5;
        public double mineMinFreedom = 0.8;
        public int mineMaxWords = 80_000;
        public boolean trainVectors = true;
        public int dim = 120;
        public int epochs = 8;
        public int hnswM = 16;
        public int efConstruction = 200;
        public String knn = "auto";           // auto | hnsw | brute
        public double k1 = 1.2, b = 0.75;
        public double[] boosts = {3.0, 1.0, 2.0};
        public int rrfK = 60;
        /**
         * Dense embeddings are a data-hungry model: measured recall@5 on the 84-document demo corpus
         * is 0.03, i.e. worse than random order for the user, while at half a million tokens the same
         * code reaches a usable neighbourhood structure. So the vector layer only turns itself on
         * above this token count, and says so, instead of silently polluting hybrid ranking.
         */
        public long minTokensForEmbeddings = 400_000;
        public boolean forceVectors = false;
        /**
         * Directory holding a local pretrained model (bge-small-zh ONNX + vocab.txt). When set, this
         * replaces the corpus-trained encoder and the token gate above no longer applies -- a
         * pretrained model is exactly what makes semantics work on a small corpus.
         */
        public String modelPath;
        public boolean clsPooling = true;
        public boolean queryInstruction = true;
        public int maxWordPieces = 256;

        public Options stopword(boolean v) { stopwords = v; return this; }
        public Options mining(boolean v) { mine = v; return this; }
        public Options vectors(boolean v) { trainVectors = v; return this; }
        public Options epochs(int v) { epochs = v; return this; }
        public Options dim(int v) { dim = v; return this; }
        public Options knn(String v) { knn = v; return this; }
    }

    private final Options options;
    private final Lexicon lexicon;
    private final ChineseAnalyzer analyzer;
    private final InvertedIndex index;
    private final Bm25 bm25;
    private final List<String> minedWords = new ArrayList<>();
    /**
     * Words contributed by {@code --dict}. Kept separately from {@link #minedWords} because they have
     * a different origin and a different shelf life, but they have to survive the same round trip:
     * they decided how the stored postings were cut, so losing them loses every query that needs them.
     */
    private final List<String> customWords = new ArrayList<>();

    private EmbeddingModel model;
    private dev.jingyu.ms.vector.Encoder encoder;   // model, or a pretrained one when --model is given
    private VectorIndex vectors;
    private volatile Searcher searcher;   // republished on every mutation; readers never take a lock
    private dev.jingyu.ms.semantic.DistributedThesaurus thesaurus;
    private long fingerprint;
    private final Map<Integer, float[]> docVectorsById = new LinkedHashMap<>();
    /**
     * Wall-clock seconds for each build stage, newest run winning. Only here so that {@code bench}
     * can put them in the committed artifact: a stage time printed to stderr once cannot be checked
     * by anyone reading the docs later, and the seconds the docs quoted from them had gone stale.
     */
    private final Map<String, Double> stages = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One lock for the whole live index: writers take the write side, a search takes the read side for
     * the duration of the query. The index is mutated in place (a new document appends postings to the
     * maps a running search is walking), so "readers need no lock" was never true -- it just usually
     * looked fine. Readers still run in parallel with each other; see {@link #writing}.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock indexGuard =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Mutate the live index. Reentrant, so a writer may call another writer. */
    private <T> T writing(java.util.function.Supplier<T> body) {
        indexGuard.writeLock().lock();
        try {
            return body.get();
        } finally {
            indexGuard.writeLock().unlock();
        }
    }

    private void writing(Runnable body) {
        indexGuard.writeLock().lock();
        try {
            body.run();
        } finally {
            indexGuard.writeLock().unlock();
        }
    }

    /** Read engine state that is not reached through a published {@link Searcher}: stats, a doc by id. */
    public <T> T reading(java.util.function.Supplier<T> body) {
        indexGuard.readLock().lock();
        try {
            return body.get();
        } finally {
            indexGuard.readLock().unlock();
        }
    }

    /** Document count of the live index, read under the guard. */
    public int numDocs() {
        return reading(index::numDocs);
    }

    /** One stored document by internal id, or null. Read under the guard. */
    public dev.jingyu.ms.index.Doc doc(int id) {
        return reading(() -> index.doc(id));
    }

    /** One stored document by its external id, or null. Read under the guard. */
    public dev.jingyu.ms.index.Doc docByExternalId(String externalId) {
        return reading(() -> {
            int id = index.find(externalId);
            return id < 0 ? null : index.doc(id);
        });
    }

    /** Self-guarded map, so this stays callable while the write lock is held. */
    private void stage(String name, double seconds) {
        stages.put(name, Math.round(seconds * 10.0) / 10.0);
    }

    /** Stage timings of the most recent build/refresh, for {@code bench} and {@code stats}. */
    public Map<String, Double> stageSeconds() {
        return new java.util.TreeMap<>(stages);
    }

    private Engine(Options options) {
        this.options = options;
        this.lexicon = Lexicon.core();
        if (options.dictPath != null) {
            try {
                customWords.addAll(lexicon.loadFile(java.nio.file.Path.of(options.dictPath)));
                Log.info("custom dictionary %s: %d words (they travel with the snapshot)",
                        options.dictPath, customWords.size());
            } catch (java.io.IOException e) {
                Log.warn("cannot read --dict %s: %s", options.dictPath, e.getMessage());
            }
        }
        this.analyzer = new ChineseAnalyzer(lexicon).setStopwords(options.stopwords);
        this.index = new InvertedIndex(analyzer);
        this.bm25 = new Bm25(options.k1, options.b, options.boosts);
        this.searcher = new Searcher(index, bm25).setRrfK(options.rrfK).setGuard(indexGuard);
    }

    public static Engine empty() { return new Engine(new Options()); }

    public static Engine empty(Options options) { return new Engine(options); }

    public Options options() { return options; }

    public InvertedIndex index() { return index; }

    public Searcher searcher() { return searcher; }

    public ChineseAnalyzer analyzer() { return analyzer; }

    public Lexicon lexicon() { return lexicon; }

    public VectorIndex vectorIndex() { return vectors; }

    public EmbeddingModel model() { return model; }

    public long fingerprint() { return fingerprint; }

    public int minedWordCount() { return minedWords.size(); }

    /** Gate: dense embeddings only earn their place once there is enough text to train them. */
    private boolean enoughTextForEmbeddings() {
        if (options.forceVectors) return true;
        long tokens = index.totalTokens(1);
        if (tokens < options.minTokensForEmbeddings) {
            Log.info("vector layer skipped: %,d corpus tokens < %,d needed for a useful embedding; "
                    + "mode=semantic (thesaurus) and mode=bm25 remain available, or pass --force-vectors",
                    tokens, options.minTokensForEmbeddings);
            return false;
        }
        return true;
    }

    /** Publish a fresh Searcher over whatever is currently built. */
    private Searcher publish() {
        searcher = new Searcher(index, bm25).setRrfK(options.rrfK).setGuard(indexGuard);
        if (encoder != null && vectors != null) searcher.enableVectors(encoder, vectors);
        if (thesaurus != null) searcher.enableThesaurus(thesaurus);
        return searcher;
    }

    /**
     * Swap in a local pretrained embedding model. This is the only route to real semantics on a small
     * corpus, and it stays a flag rather than a default because the weights are 24 MB and the ONNX
     * Runtime jar is 93 MB -- shipping those would void "the base jar has zero dependencies".
     */
    private synchronized void usePretrainedModel() {
        java.nio.file.Path dir = java.nio.file.Path.of(options.modelPath);
        long t0 = System.nanoTime();
        try {
            encoder = dev.jingyu.ms.vector.Encoder.loadPretrained(dir, options.clsPooling,
                    options.maxWordPieces, options.queryInstruction);
        } catch (Exception e) {
            Log.warn("cannot load model from %s (%s); falling back to the corpus-trained path",
                    dir, e.getMessage());
            encoder = null;
            return;
        }
        Log.info("pretrained encoder %s loaded from %s in %.1fs", encoder.name(), dir, Log.since(t0));
        encodeDocuments(encoder);
    }

    /** One forward pass per document; the k-NN structure is chosen by size, as everywhere else. */
    private void encodeDocuments(dev.jingyu.ms.vector.Encoder enc) {
        writing(() -> encodeDocumentsLocked(enc));
    }

    private void encodeDocumentsLocked(dev.jingyu.ms.vector.Encoder enc) {
        List<Doc> docs = index.allDocs();
        docs.sort(java.util.Comparator.comparingInt(Doc::id));
        boolean useHnsw = "hnsw".equalsIgnoreCase(options.knn)
                || ("auto".equalsIgnoreCase(options.knn) && docs.size() >= 2000);
        vectors = useHnsw ? new Hnsw(enc.dimension(), options.hnswM, options.efConstruction, 42)
                : new BruteForce(enc.dimension());
        docVectorsById.clear();
        long t0 = System.nanoTime();
        for (Doc d : docs) {
            float[] v = enc.encode(d.vectorText());
            docVectorsById.put(d.id(), v);
            vectors.add(d.id(), v);
        }
        Log.info("encoded %d documents with %s in %.1fs (%.0f ms/doc)",
                docs.size(), enc.name(), Log.since(t0), Log.ms(t0) / Math.max(1, docs.size()));
    }

    /** Corpus-derived thesaurus; the semantic model that works without a big training corpus. */
    public synchronized dev.jingyu.ms.semantic.DistributedThesaurus buildThesaurus() {
        return writing(this::buildThesaurusLocked);
    }

    private dev.jingyu.ms.semantic.DistributedThesaurus buildThesaurusLocked() {
        long t0 = System.nanoTime();
        thesaurus = dev.jingyu.ms.semantic.DistributedThesaurus.build(
                index, index.allDocs(), analyzer, 8, 2, 0.08);
        double tookThesaurus = Log.since(t0);
        Log.info("thesaurus: %s in %.1fs", thesaurus, tookThesaurus);
        stage("thesaurus", tookThesaurus);
        return thesaurus;
    }

    public dev.jingyu.ms.semantic.DistributedThesaurus thesaurus() { return thesaurus; }

    // ------------------------------------------------------------------ build

    /** Full offline build: mine, index, train, encode. */
    public static Engine build(List<Corpus.RawDoc> docs, Options options) {
        Engine e = new Engine(options);
        e.fingerprint = Corpus.fingerprint(docs);

        if (options.mine) {
            long t0 = System.nanoTime();
            List<String> texts = new ArrayList<>(docs.size());
            for (Corpus.RawDoc d : docs) texts.add(d.title() + "。" + d.body() + "。" + d.tags());
            List<TermMining.Word> found = TermMining.mine(texts, new TermMining.Options(
                    6, 2, options.mineMinFreq, options.mineMinCohesion, options.mineMinFreedom, options.mineMaxWords));
            for (TermMining.Word w : found) {
                e.minedWords.add(w.term());
                e.lexicon.add(w.term(), w.freq());
            }
            double tookMining = Log.since(t0);
            Log.info("term mining: %d candidates accepted from %d docs in %.1fs",
                    found.size(), docs.size(), tookMining);
            e.stage("mining", tookMining);
        }
        e.lexicon.seal();

        long t1 = System.nanoTime();
        for (Corpus.RawDoc d : docs) e.addRaw(d);
        double tookIndex = Log.since(t1);
        Log.info("inverted index built: %s in %.1fs", e.index, tookIndex);
        e.stage("invertedIndex", tookIndex);

        if (options.modelPath != null && !options.modelPath.isBlank()) e.usePretrainedModel();
        else if (options.trainVectors && e.enoughTextForEmbeddings()) e.trainVectors(docs);
        e.buildThesaurus();
        e.publish();
        return e;
    }

    private void trainVectors(List<Corpus.RawDoc> docs) { trainAndIndexVectors(); }

    /** (Re)train the semantic layer from whatever is currently indexed. {@code docs} is unused. */
    public void trainAndIndexVectors() {
        writing(this::trainAndIndexVectorsLocked);
    }

    private void trainAndIndexVectorsLocked() {
        long t0 = System.nanoTime();
        List<Doc> docs = index.allDocs();
        docs.sort(java.util.Comparator.comparingInt(Doc::id));
        Map<Integer, List<String>> tokens = new LinkedHashMap<>();
        for (Doc d : docs) tokens.put(d.id(), analyzer.terms(d.vectorText()));
        Idf idf = new Idf(index.documentFrequencies(), Math.max(1, index.numDocs()));
        EmbeddingModel.Config cfg = new EmbeddingModel.Config();
        cfg.dim = options.dim;
        cfg.epochs = options.epochs;
        model = new EmbeddingModel(analyzer, idf, cfg);
        long tTrain = System.nanoTime();
        model.train(new ArrayList<>(tokens.values()));
        stage("word2vec", Log.since(tTrain));
        long tGraph = System.nanoTime();
        buildVectorIndex(tokens);
        stage("vectorIndex", Log.since(tGraph));
        encoder = model;
        // vectorLayer is the sum of the two above plus tokenising every document; publishing all
        // three keeps a reader from doing what a previous draft of the docs did and reading the
        // total as "HNSW build".
        double tookLayer = Log.since(t0);
        Log.info("vector layer ready: %s in %.1fs", vectors == null ? "off" : vectors.name(), tookLayer);
        stage("vectorLayer", tookLayer);
    }

    /** Title is doubled so a document's vector leans on what its title claims. */
    private static String vectorText(String title, String tags, String body) {
        return title + "。" + title + "。" + tags + "。" + body;
    }
    private void buildVectorIndex(Map<Integer, List<String>> tokensByDoc) {
        if (model == null) return;
        int dim = options.dim;
        boolean useHnsw = "hnsw".equalsIgnoreCase(options.knn)
                || ("auto".equalsIgnoreCase(options.knn) && tokensByDoc.size() >= 2000);
        vectors = useHnsw ? new Hnsw(dim, options.hnswM, options.efConstruction, 42) : new BruteForce(dim);
        docVectorsById.clear();
        // two passes: the corpus mean is the centre that the final vectors then remove
        Map<Integer, float[]> raw = new LinkedHashMap<>();
        float[] mean = new float[dim];
        int n = 0;
        for (Map.Entry<Integer, List<String>> e : tokensByDoc.entrySet()) {
            if (index.doc(e.getKey()) == null) continue;
            float[] r = model.raw(e.getValue());
            raw.put(e.getKey(), r);
            for (int d = 0; d < dim; d++) mean[d] += r[d];
            n++;
        }
        if (n > 0) for (int d = 0; d < dim; d++) mean[d] /= n;
        model.setCenter(mean);
        for (Map.Entry<Integer, float[]> e : raw.entrySet()) {
            float[] v = model.toVector(e.getValue());
            docVectorsById.put(e.getKey(), v);
            vectors.add(e.getKey(), v);
        }
    }

    // ------------------------------------------------------------------ incremental

    /**
     * Every mutation of the live index goes through a {@code synchronized} method on this
     * instance: the HTTP server runs a thread pool, and {@code POST /api/index},
     * {@code DELETE /api/index} and {@code POST /api/crawl} can all arrive at once.
     *
     * <p>{@code synchronized} keeps the writers off each other; it does nothing about readers, which
     * is why the mutations below also take {@link #indexGuard}'s write side. A published
     * {@link Searcher} is a stable view of which layers exist, but it points at the same
     * {@link InvertedIndex} the writers are appending to, so "readers only touch the volatile field"
     * was never a safety argument -- it just hid the race well most of the time.
     */
    public synchronized Doc addRaw(Corpus.RawDoc raw) {
        return writing(() -> {
            Map<String, String> f = new LinkedHashMap<>();
            f.put(Doc.TITLE, raw.title());
            f.put(Doc.BODY, raw.body());
            f.put(Doc.TAGS, raw.tags());
            return index.add(raw.id(), raw.url(), f);
        });
    }

    /** Index live text straight from the crawler or the API. */
    public Doc add(String id, String url, String title, String body, String tags) {
        return addRaw(new Corpus.RawDoc(id, url, title, body, tags));
    }

    public synchronized boolean delete(String id) {
        return writing(() -> index.deleteByExternalId(id));
    }

    /** Rebuild the semantic layer after documents changed; cheap compared with re-mining. */
    public synchronized void refreshVectors() {
        writing(() -> {
            if (encoder != null && encoder.pretrained()) encodeDocuments(encoder);
            else if (options.trainVectors && enoughTextForEmbeddings()) trainAndIndexVectors();
            else buildVectorIndex(snapshotTokens());
            publish();
        });
    }

    private Map<Integer, List<String>> snapshotTokens() {
        List<Doc> docs = index.allDocs();
        docs.sort(java.util.Comparator.comparingInt(Doc::id));
        Map<Integer, List<String>> tokens = new LinkedHashMap<>();
        for (Doc d : docs) tokens.put(d.id(), analyzer.terms(d.vectorText()));
        return tokens;
    }

    // ------------------------------------------------------------------ stats

    public Map<String, Object> stats() {
        return reading(this::statsLocked);
    }

    private Map<String, Object> statsLocked() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents", index.numDocs());
        m.put("terms", index.vocabularySize());
        m.put("dictionaryWords", lexicon.size());
        m.put("minedWords", minedWords.size());
        m.put("stopwords", options.stopwords);
        m.put("mining", options.mine);
        m.put("bm25", Map.of("k1", options.k1, "b", options.b, "boosts", options.boosts));
        m.put("rrfK", options.rrfK);
        m.put("encoder", encoder == null ? "off" : encoder.name());
        m.put("vocabulary", model == null ? 0 : model.vocabularySize());
        m.put("thesaurusEntries", thesaurus == null ? 0 : thesaurus.size());
        m.put("knn", vectors == null ? "off" : vectors.name());
        m.put("vectors", vectors == null ? 0 : vectors.size());
        m.put("fingerprint", fingerprint);
        m.put("stageSeconds", stageSeconds());
        m.put("analyzer", "han-maxmatch+latin+digit");
        return m;
    }

    // ------------------------------------------------------------------ snapshot

    public void save(Path file) throws IOException {
        indexGuard.readLock().lock();
        try {
            saveLocked(file);
        } finally {
            indexGuard.readLock().unlock();
        }
    }

    private void saveLocked(Path file) throws IOException {
        int[] liveIds = index.liveDocIds();
        List<Integer> kept = new ArrayList<>();
        for (int id : liveIds) if (docVectorsById.containsKey(id)) kept.add(id);
        int[] ids = new int[kept.size()];
        float[][] vecs = new float[kept.size()][];
        for (int i = 0; i < kept.size(); i++) { ids[i] = kept.get(i); vecs[i] = docVectorsById.get(ids[i]); }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (model != null) model.save(bos);
        Snapshot s = Snapshot.of(index, minedWords, customWords, vecs, ids, fingerprint);
        s.putModel(bos.toByteArray());
        s.writeTo(file);
    }

    /** Load a previously saved engine. Falls back to a full rebuild when the file is unusable. */
    public static Engine restore(Path file, Options options, List<Corpus.RawDoc> fallback) {
        try {
            Snapshot s = Snapshot.read(file);
            if (fallback != null && s.fingerprint() != Corpus.fingerprint(fallback)) {
                Log.info("snapshot is stale for this corpus, rebuilding");
                return build(fallback, options);
            }
            Engine e = new Engine(options);
            e.fingerprint = s.fingerprint();
            for (String w : Snapshot.readDict(s.raw("dict"))) {
                e.minedWords.add(w);
                e.lexicon.add(w);
            }
            // The words --dict contributed when this file was written. Restoring them is the difference
            // between "search finds it" and a quiet empty result list for a term that is in the index.
            for (String w : Snapshot.readDict(s.raw("udict"))) {
                if (!e.customWords.contains(w)) e.customWords.add(w);
                e.lexicon.add(w);
            }
            if (options.dictPath == null && !e.customWords.isEmpty()) {
                Log.info("snapshot carries %d custom dictionary words from the --dict it was built with",
                        e.customWords.size());
            }
            e.lexicon.seal();

            List<Object[]> rows = new ArrayList<>();
            Snapshot.readDocs(s.raw("docs"), rows);
            Map<Integer, Doc> byId = new LinkedHashMap<>();
            for (Object[] r : rows) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put(Doc.TITLE, (String) r[3]);
                f.put(Doc.BODY, (String) r[4]);
                f.put(Doc.TAGS, (String) r[5]);
                byId.put((Integer) r[0], new Doc((Integer) r[0], (String) r[1], (String) r[2], f));
            }
            for (Doc d : byId.values()) e.index.registerDoc(d);
            e.index.setNextId(byId.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);

            int[][] lens = Snapshot.readLens(s.raw("lens"));
            for (int f = 0; f < lens.length; f++) {
                for (int i = 0; i < lens[f].length; i += 2) e.index.setFieldLength(f, lens[f][i], lens[f][i + 1]);
            }

            List<Map<String, List<Object[]>>> posts = Snapshot.readPostings(s.raw("postings"));
            for (int f = 0; f < posts.size(); f++) {
                for (Map.Entry<String, List<Object[]>> term : posts.get(f).entrySet()) {
                    for (Object[] run : term.getValue()) {
                        e.index.putPosting(f, term.getKey(), (Integer) run[0], (int[]) run[1]);
                    }
                }
            }

            Snapshot.StoredVectors sv = Snapshot.readVectors(s.raw("vectors"));
            if (sv.docIds().length > 0 && (s.model() == null || s.model().length == 0)
                    && (options.modelPath == null || options.modelPath.isBlank())) {
                Log.warn("snapshot holds %d vectors written by an external model but none was passed;"
                        + " the dense path stays off. Re-run with --model DIR", sv.docIds().length);
            }
            if (sv.docIds().length > 0 && s.model() != null && s.model().length > 0) {
                Idf idf = new Idf(e.index.documentFrequencies(), Math.max(1, e.index.numDocs()));
                EmbeddingModel.Config cfg = new EmbeddingModel.Config();
                cfg.dim = sv.vectors()[0].length;
                e.model = EmbeddingModel.load(new ByteArrayInputStream(s.model()), e.analyzer, idf, cfg);
                boolean useHnsw = "hnsw".equalsIgnoreCase(options.knn)
                        || ("auto".equalsIgnoreCase(options.knn) && sv.docIds().length >= 2000);
                e.vectors = useHnsw ? new Hnsw(cfg.dim, options.hnswM, options.efConstruction, 42)
                        : new BruteForce(cfg.dim);
                for (int i = 0; i < sv.docIds().length; i++) {
                    e.docVectorsById.put(sv.docIds()[i], sv.vectors()[i]);
                    e.vectors.add(sv.docIds()[i], sv.vectors()[i]);
                }
            }
            // a snapshot never carries the pretrained model, only the vectors it produced: reload the
            // encoder so queries use the same model the stored documents were encoded with
            if (e.vectors != null && e.model != null) e.encoder = e.model;
            e.buildThesaurus();
            if (options.modelPath != null && !options.modelPath.isBlank()) {
                e.usePretrainedModel();
                e.publish();
            }
            Log.info("restored %s from %s", e.index, file.getFileName());
            return e;
        } catch (IOException | RuntimeException ex) {
            Log.warn("snapshot unusable (%s); rebuilding from corpus", ex.getMessage());
            return fallback == null ? empty(options) : build(fallback, options);
        }
    }
}
