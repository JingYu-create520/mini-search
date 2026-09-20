package dev.jingyu.ms.search;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.hybrid.Rrf;
import dev.jingyu.ms.index.Doc;
import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.ranking.Bm25;
import dev.jingyu.ms.util.Log;
import dev.jingyu.ms.vector.Encoder;
import dev.jingyu.ms.vector.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query orchestration: analyse, run each retrieval model, fuse, hydrate, highlight.
 *
 * <p>{@code BM25} is lexical and exact, {@code VECTOR} is distributional and forgiving, {@code HYBRID}
 * merges the two ranked lists with RRF. The pool each list contributes to the fusion is deliberately
 * wider than {@code topK}: a document ranked 12th lexically but 1st semantically should be able to
 * win, and it can only do that if both lists are long enough to contain it.
 */
public final class Searcher {

    public enum Mode {
        BM25, VECTOR, HYBRID, SEMANTIC;

        public static Mode parse(String s, Mode dflt) {
            if (s == null || s.isBlank()) return dflt;
            try { return valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return dflt; }
        }
    }

    /** Result envelope; {@code toMap} is the HTTP/MCP wire shape. */
    public record Result(List<Hit> hits, int total, double tookMs, Mode mode, List<String> tokens,
                         Map<String, Double> modelMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mode", mode.name().toLowerCase());
            m.put("tokens", tokens);
            m.put("total", total);
            m.put("tookMs", round(tookMs));
            m.put("timings", modelMs);
            List<Object> hs = new ArrayList<>();
            for (Hit h : hits) hs.add(h.toMap());
            m.put("hits", hs);
            return m;
        }

        private static Object round(double v) { return Math.round(v * 1000) / 1000.0; }
    }

    /** Candidate pool each retrieval list contributes to the fusion. */
    public static final int POOL = 200;

    private final InvertedIndex index;
    private final Bm25 bm25;
    private Encoder encoder;
    private VectorIndex vectors;
    private dev.jingyu.ms.semantic.DistributedThesaurus thesaurus;
    private int expandBudget = 12;
    private int rrfK = Rrf.DEFAULT_K;
    /**
     * Per-list weights in fusion order: lexical, thesaurus-expanded, dense.
     *
     * <p>Chosen by measurement, not taste: on the bundled corpus the dense list is only worth
     * trusting when it comes from a pretrained model, and at these weights hybrid matches that model
     * (recall@5 0.987) instead of diluting it, while with no dense list at all hybrid stays equal to
     * plain BM25. Sweep them with {@code --fusion 1,0.5,6} and read the report.
     */
    private double[] fusionWeights = {1.0, 0.5, 6.0};

    /** Corpus-derived thesaurus that powers mode=semantic. */
    public Searcher enableThesaurus(dev.jingyu.ms.semantic.DistributedThesaurus t) {
        this.thesaurus = t;
        return this;
    }

    public boolean thesaurusEnabled() { return thesaurus != null; }

    public Searcher setExpandBudget(int terms) { this.expandBudget = terms; return this; }

    public Searcher(InvertedIndex index, Bm25 bm25) {
        this.index = index;
        this.bm25 = bm25;
    }

    public Searcher enableVectors(Encoder encoder, VectorIndex vectors) {
        this.encoder = encoder;
        this.vectors = vectors;
        return this;
    }

    public Searcher setRrfK(int k) { this.rrfK = k; return this; }

    public Searcher setFusionWeights(double... weights) {
        this.fusionWeights = weights.clone();
        return this;
    }

    public boolean vectorsEnabled() { return encoder != null && vectors != null && vectors.size() > 0; }

    public InvertedIndex index() { return index; }

    public ChineseAnalyzer analyzer() { return index.analyzer(); }

    public Bm25 bm25() { return bm25; }

    public Encoder encoder() { return encoder; }

    // ------------------------------------------------------------------ ranking lists

    public Map<Integer, Double> lexicalScores(String query) { return bm25.score(index, queryTerms(query)); }

    /**
     * BM25 over the query plus its distributed-thesaurus neighbours. This is the retrieval model that
     * carries the semantic load on small corpora, where a trained embedding is indistinguishable from
     * noise -- see {@link DistributedThesaurus}.
     */
    public Map<Integer, Double> semanticScores(String query) {
        List<String> terms = queryTerms(query);
        if (thesaurus != null) terms.addAll(thesaurus.expand(terms, expandBudget));
        return bm25.score(index, terms);
    }

    public List<Integer> semanticRanked(String query, int pool) {
        return rankedByScore(semanticScores(query), pool);
    }

    public Map<Integer, Double> vectorScores(String query, int pool) {
        Map<Integer, Double> out = new HashMap<>();
        if (!vectorsEnabled()) return out;
        float[] q = encoder.encodeQuery(query);
        for (VectorIndex.Neighbor n : vectors.search(q, pool)) {
            if (!index.isDeleted(n.docId())) out.put(n.docId(), (double) n.score());
        }
        return out;
    }

    public List<Integer> lexicalRanked(String query, int pool) { return rankedByScore(lexicalScores(query), pool); }

    public List<Integer> vectorRanked(String query, int pool) { return rankedByScore(vectorScores(query, pool), pool); }

    /** Ranked document ids for one mode, no hydration -- the shape the eval harness consumes. */
    public List<Integer> rankedIds(String query, Mode mode, int pool) {
        return switch (mode) {
            case BM25 -> lexicalRanked(query, pool);
            case VECTOR -> vectorRanked(query, pool);
            case SEMANTIC -> semanticRanked(query, pool);
            case HYBRID -> Rrf.fuse(fusionLists(query, pool), fusionWeights, rrfK, pool);
        };
    }

    /** The ranked lists that take part in fusion; the weight vector is aligned with this order. */
    private List<List<Integer>> fusionLists(String query, int pool) {
        List<List<Integer>> lists = new ArrayList<>();
        lists.add(lexicalRanked(query, pool));
        lists.add(semanticRanked(query, pool));
        if (vectorsEnabled()) lists.add(vectorRanked(query, pool));
        return lists;
    }

    private double[] fusionWeightsFor(int lists) {
        if (fusionWeights.length == lists) return fusionWeights;
        double[] w = new double[lists];
        for (int i = 0; i < lists; i++) w[i] = i < fusionWeights.length ? fusionWeights[i] : 1.0;
        return w;
    }

    private static List<Integer> rankedByScore(Map<Integer, Double> scores, int pool) {
        List<Map.Entry<Integer, Double>> e = new ArrayList<>(scores.entrySet());
        e.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(x -> -x.getValue())
                .thenComparingInt(Map.Entry::getKey));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(pool, e.size()); i++) out.add(e.get(i).getKey());
        return out;
    }

    // ------------------------------------------------------------------ full search

    public Result search(String query, Mode mode, int topK, int from, boolean phrase, boolean highlight) {
        long t0 = System.nanoTime();
        List<String> terms = queryTerms(query);
        Set<String> termSet = new LinkedHashSet<>(terms);

        Map<Integer, Double> lex = mode == Mode.VECTOR || mode == Mode.SEMANTIC
                ? Map.of() : bm25.score(index, terms);
        Map<Integer, Double> sem = (mode == Mode.BM25 || mode == Mode.VECTOR) ? Map.of() : semanticScores(query);
        Map<Integer, Double> vec = (mode == Mode.BM25 || mode == Mode.SEMANTIC || !vectorsEnabled())
                ? Map.of() : vectorScores(query, POOL);

        List<Integer> ranked;
        if (mode == Mode.HYBRID) {
            List<List<Integer>> lists = new ArrayList<>();
            lists.add(rankedByScore(lex, POOL));
            lists.add(rankedByScore(sem, POOL));
            if (!vec.isEmpty()) lists.add(rankedByScore(vec, POOL));
            ranked = Rrf.fuse(lists, fusionWeightsFor(lists.size()), rrfK, POOL);
        } else if (mode == Mode.BM25) {
            ranked = rankedByScore(lex, POOL);
        } else if (mode == Mode.SEMANTIC) {
            ranked = rankedByScore(sem, POOL);
        } else {
            ranked = rankedByScore(vec, POOL);
        }

        if (phrase && terms.size() > 1) {
            Set<Integer> allow = new HashSet<>(phraseCandidates(terms));
            List<Integer> kept = new ArrayList<>();
            for (int id : ranked) if (allow.contains(id)) kept.add(id);
            ranked = kept;
        }

        int total = ranked.size();
        List<Hit> hits = new ArrayList<>();
        for (int i = from; i < Math.min(from + topK, ranked.size()); i++) {
            int docId = ranked.get(i);
            Doc d = index.doc(docId);
            if (d == null) continue;
            String snippet = highlight
                    ? Highlighter.snippet(index.analyzer(), d.field(Doc.BODY), termSet, 24, 240)
                    : plain(d.field(Doc.BODY), 160);
            Map<String, Double> contrib = new java.util.LinkedHashMap<>();
            if (lex.containsKey(docId)) contrib.put("bm25", lex.get(docId));
            if (sem.containsKey(docId)) contrib.put("semantic", sem.get(docId));
            if (vec.containsKey(docId)) contrib.put("cosine", vec.get(docId));
            double score = switch (mode) {
                case BM25 -> lex.getOrDefault(docId, 0.0);
                case SEMANTIC -> sem.getOrDefault(docId, 0.0);
                case VECTOR -> vec.getOrDefault(docId, 0.0);
                case HYBRID -> 1.0 / (rrfK + i + 1);
            };
            hits.add(new Hit(docId, d.externalId(), d.url(), title(d, termSet, highlight), snippet, score,
                    lex.get(docId), vec.get(docId), i, splitTags(d.field(Doc.TAGS)), contrib));
        }
        Map<String, Double> timings = new java.util.LinkedHashMap<>();
        timings.put("bm25Docs", (double) lex.size());
        timings.put("vectorDocs", (double) vec.size());
        return new Result(hits, total, Log.ms(t0), mode, terms, timings);
    }

    private String title(Doc d, Set<String> terms, boolean highlight) {
        String t = d.field(Doc.TITLE);
        if (!highlight) return t;
        List<dev.jingyu.ms.analyzer.Token> ts = index.analyzer().analyze(t);
        return Highlighter.mark(t, ts, 0, Math.max(1, ts.size()), terms);
    }

    // ------------------------------------------------------------------ helpers

    public List<String> queryTerms(String query) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var t : index.analyzer().analyze(query)) if (seen.add(t.term())) out.add(t.term());
        return out;
    }

    private List<Integer> phraseCandidates(List<String> terms) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int f = 0; f < InvertedIndex.FIELDS.size(); f++) out.addAll(index.phrase(f, terms));
        return new ArrayList<>(out);
    }

    private static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        for (String s : tags.split("[,，、;；\\s]+")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String plain(String text, int max) {
        String t = text == null ? "" : text.strip();
        String cut = t.length() <= max ? t : t.substring(0, max) + "…";
        // escaped too, so a client can render `snippet` the same way whether highlighting is on or off
        return dev.jingyu.ms.search.Highlighter.escape(cut);
    }
}
