package dev.jingyu.ms.vector;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Skip-gram word embeddings trained on the indexed corpus, which is what makes the vector half of
 * this engine semantic without downloading a model.
 *
 * <p>The distributional hypothesis: words that appear in similar contexts mean similar things.
 * Training therefore needs no labels, only text. For every centre token we ask a linear classifier
 * to score the tokens that really appeared nearby higher than tokens drawn at random from the corpus
 * unigram distribution (negative sampling, the cheap softmax substitute). Gradient descent pushes the
 * centre word's vector towards its true neighbours and away from the noise, so vectors end up
 * arranged by contextual similarity.
 *
 * <p>Document and query vectors are IDF-weighted averages of the word vectors, L2-normalised. The
 * weighting matters: an unweighted mean lets a frequent filler word dominate, and IDF-weighting plus
 * normalisation makes the vector's direction reflect what is distinctive about the text.
 *
 * <p>Everything is deterministic given {@link Config#seed}, so a reported eval number can be
 * reproduced on another machine.
 */
public final class EmbeddingModel implements Encoder {

    public static final class Config {
        public int dim = 120;
        public int window = 5;
        public int epochs = 8;
        public double lr = 0.05;
        public int negative = 5;
        public int maxVocab = 30_000;
        public int minDf = 1;
        public double subsample = 1e-3;
        public long seed = 42;
        public int negTable = 1_000_000;

        public Config dim(int v) { dim = v; return this; }
        public Config epochs(int v) { epochs = v; return this; }
        public Config lr(double v) { lr = v; return this; }
        public Config window(int v) { window = v; return this; }
    }

    private final Config cfg;
    private final ChineseAnalyzer analyzer;
    private final Idf idf;

    private String[] vocab = new String[0];
    private Map<String, Integer> lex = new HashMap<>();
    private float[][] w;               // centre (input) vectors
    private float[][] c;               // context (output) vectors
    private int[] table;               // negative-sampling unigram^0.75 table
    private double[] freq;             // corpus frequency, for subsampling
    private float[] center;            // corpus mean vector, removed before normalising
    private long trainNanos;

    public EmbeddingModel(ChineseAnalyzer analyzer, Idf idf, Config cfg) {
        this.analyzer = analyzer;
        this.idf = idf;
        this.cfg = cfg;
    }

    @Override public int dimension() { return cfg.dim; }

    public ChineseAnalyzer analyzer() { return analyzer; }

    @Override public String name() { return "w2v-" + cfg.dim + "d-e" + cfg.epochs; }

    public int vocabularySize() { return vocab.length; }

    public boolean isTrained() { return w != null && w.length > 0; }

    public long trainNanos() { return trainNanos; }

    // ------------------------------------------------------------------ training

    /** @param corpus one token list per document, already analyser-cut */
    public void train(List<List<String>> corpus) {
        long t0 = System.nanoTime();
        buildVocab(corpus);
        Random rnd = new Random(cfg.seed);
        int v = vocab.length;
        if (v == 0) { Log.warn("empty vocabulary, nothing to train"); return; }
        w = new float[v][cfg.dim];
        c = new float[v][cfg.dim];
        for (int i = 0; i < v; i++) {
            float s = 0.5f / cfg.dim;
            for (int d = 0; d < cfg.dim; d++) w[i][d] = (rnd.nextFloat() * 2 - 1) * s;
        }
        buildNegTable(corpus, rnd);

        long tokens = 0;
        for (List<String> doc : corpus) tokens += doc.size();
        long totalWork = Math.max(1, tokens * cfg.epochs);
        long done = 0;
        float[] scratch = new float[cfg.dim];

        for (int epoch = 0; epoch < cfg.epochs; epoch++) {
            float lr = (float) (cfg.lr * Math.max(0.001, 1.0 - (double) epoch / cfg.epochs));
            for (List<String> doc : corpus) {
                int[] ids = encodeDoc(doc);
                int n = ids.length;
                for (int i = 0; i < n; i++) {
                    if (skip(ids[i], rnd)) continue;
                    int spread = 1 + rnd.nextInt(cfg.window);
                    for (int off = -spread; off <= spread; off++) {
                        if (off == 0 || i + off < 0 || i + off >= n) continue;
                        done += sgns(ids[i], ids[i + off], lr, rnd, scratch);
                    }
                }
            }
            Log.info("word2vec epoch %d/%d done (%d updates, lr=%.4f)", epoch + 1, cfg.epochs, done, lr);
        }
        trainNanos = System.nanoTime() - t0;
        Log.info("word2vec trained: vocab=%d dim=%d in %.1fs", vocab.length, cfg.dim, Log.since(t0));
    }

    private void buildVocab(List<List<String>> corpus) {
        Map<String, Integer> counts = new HashMap<>(1 << 14);
        for (List<String> doc : corpus) {
            for (String t : new java.util.HashSet<>(doc)) counts.merge(t, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.removeIf(e -> e.getValue() < cfg.minDf);
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        if (entries.size() > cfg.maxVocab) entries = entries.subList(0, cfg.maxVocab);
        vocab = new String[entries.size()];
        freq = new double[entries.size()];
        lex = new HashMap<>(entries.size() * 2);
        long total = 0;
        for (int i = 0; i < entries.size(); i++) {
            vocab[i] = entries.get(i).getKey();
            lex.put(vocab[i], i);
            freq[i] = entries.get(i).getValue();
            total += freq[i];
        }
        if (total > 0) for (int i = 0; i < freq.length; i++) freq[i] /= total;
    }

    /** Unigram^0.75 sampling table: frequent words are drawn as noise more often, rare ones less. */
    private void buildNegTable(List<List<String>> corpus, Random rnd) {
        int size = cfg.negTable;
        table = new int[size];
        double[] pow = new double[vocab.length];
        double sum = 0;
        for (int i = 0; i < vocab.length; i++) { pow[i] = Math.pow(Math.max(freq[i], 1e-12), 0.75); sum += pow[i]; }
        double[] cum = new double[vocab.length];
        double acc = 0;
        for (int i = 0; i < vocab.length; i++) { acc += pow[i] / sum; cum[i] = acc; }
        cum[vocab.length - 1] = 1.0;
        for (int a = 0; a < size; a++) {
            double r = rnd.nextDouble();
            int lo = 0, hi = vocab.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] < r) lo = mid + 1; else hi = mid;
            }
            table[a] = lo;
        }
    }

    private boolean skip(int wordId, Random rnd) {
        double f = freq[wordId];
        double r = f / (cfg.subsample + f);
        return rnd.nextDouble() > Math.sqrt(r);
    }

    private int[] encodeDoc(List<String> doc) {
        int[] tmp = new int[doc.size()];
        int n = 0;
        for (String t : doc) {
            Integer id = lex.get(t);
            if (id != null) tmp[n++] = id;
        }
        return n == tmp.length ? tmp : java.util.Arrays.copyOf(tmp, n);
    }

    /** One negative-sampled update; returns 1 so the caller can track progress. */
    private long sgns(int centre, int context, float lr, Random rnd, float[] grad) {
        float[] v = w[centre];
        java.util.Arrays.fill(grad, 0f);
        for (int k = 0; k <= cfg.negative; k++) {
            int j = (k == 0) ? context : table[rnd.nextInt(table.length)];
            if (k != 0 && j == context) continue;
            float label = k == 0 ? 1f : 0f;
            float[] cv = c[j];
            double dot = 0;
            for (int d = 0; d < cfg.dim; d++) dot += v[d] * cv[d];
            float pred = (float) (1.0 / (1.0 + Math.exp(-Math.max(-12, Math.min(12, dot)))));
            float g = (label - pred) * lr;
            for (int d = 0; d < cfg.dim; d++) {
                cv[d] += g * v[d];
                grad[d] += g * cv[d];
            }
        }
        for (int d = 0; d < cfg.dim; d++) v[d] += grad[d];
        return 1;
    }

    // ------------------------------------------------------------------ encoding

    @Override
    public float[] encode(String text) {
        return toVector(raw(analyzer.terms(text)));
    }

    /** tf-idf weighted sum of word vectors, neither centred nor normalised. */
    public float[] raw(List<String> terms) {
        float[] out = new float[cfg.dim];
        if (!isTrained() || terms.isEmpty()) return out;
        Map<String, Integer> local = new HashMap<>();
        for (String t : terms) local.merge(t, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : local.entrySet()) {
            Integer id = lex.get(e.getKey());
            if (id == null) continue;
            double weight = (1 + Math.log(e.getValue())) * idf.idf(e.getKey());
            float[] wv = w[id];
            for (int d = 0; d < cfg.dim; d++) out[d] += (float) (weight * wv[d]);
        }
        return out;
    }

    /**
     * Final representation: subtract the corpus mean, then normalise.
     *
     * <p>Without the subtraction every document vector sits inside a narrow cone around the corpus
     * mean (mean-pooling is anisotropic), so cosine similarities all land near 0.95 and the ranking
     * is mostly noise -- measured recall@5 on the demo corpus went from 0.07 to a usable number after
     * centring. Removing the shared component is what lets the remaining direction express what is
     * actually different about a document.
     */
    public float[] toVector(float[] rawVector) {
        float[] v = rawVector.clone();
        if (center != null) {
            boolean any = false;
            for (int d = 0; d < v.length; d++) {
                v[d] -= center[d];
                if (v[d] != 0) any = true;
            }
            if (!any) return v;
        }
        return Encoder.normalize(v);
    }

    public void setCenter(float[] mean) { this.center = mean == null ? null : mean.clone(); }

    public float[] center() { return center == null ? null : center.clone(); }

    /** Vector for an already-tokenised document (avoids re-analysing at index time). */
    public float[] encodeTokens(List<String> terms) {
        return toVector(raw(terms));
    }

    /** Nearest vocabulary words by cosine -- used by the CLI's {@code vector} debugging command. */
    public List<String> nearest(String term, int k) {
        Integer id = lex.get(term);
        List<String> out = new ArrayList<>();
        if (id == null) return out;
        float[] q = w[id];
        double[] score = new double[vocab.length];
        for (int i = 0; i < vocab.length; i++) {
            if (i == id) continue;
            score[i] = Encoder.dot(q, w[i]);
        }
        Integer[] idx = new Integer[vocab.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(score[y], score[x]));
        for (int i = 0; i < Math.min(k, idx.length); i++) out.add(vocab[idx[i]]);
        return out;
    }

    // ------------------------------------------------------------------ persistence

    public void save(OutputStream os) throws IOException {
        DataOutputStream out = new DataOutputStream(os);
        out.writeUTF("MSWV2");
        out.writeInt(cfg.dim);
        out.writeBoolean(center != null);
        if (center != null) for (float x : center) out.writeFloat(x);
        out.writeInt(vocab.length);
        for (int i = 0; i < vocab.length; i++) {
            out.writeUTF(vocab[i]);
            for (int d = 0; d < cfg.dim; d++) out.writeFloat(w[i][d]);
        }
        out.flush();
    }

    public static EmbeddingModel load(InputStream is, ChineseAnalyzer analyzer, Idf idf, Config cfg)
            throws IOException {
        DataInputStream in = new DataInputStream(is);
        String magic = in.readUTF();
        if (!magic.equals("MSWV2")) throw new IOException("not a mini-search vector file: " + magic);
        int dim = in.readInt();
        if (dim != cfg.dim) cfg.dim = dim;
        EmbeddingModel m = new EmbeddingModel(analyzer, idf, cfg);
        if (in.readBoolean()) {
            m.center = new float[dim];
            for (int d = 0; d < dim; d++) m.center[d] = in.readFloat();
        }
        int n = in.readInt();
        m.vocab = new String[n];
        m.lex = new HashMap<>(n * 2);
        m.freq = new double[n];
        m.w = new float[n][dim];
        m.c = new float[n][dim];
        for (int i = 0; i < n; i++) {
            m.vocab[i] = in.readUTF();
            m.lex.put(m.vocab[i], i);
            for (int d = 0; d < dim; d++) m.w[i][d] = in.readFloat();
        }
        return m;
    }
}
