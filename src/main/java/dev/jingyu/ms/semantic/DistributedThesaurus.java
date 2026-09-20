package dev.jingyu.ms.semantic;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.index.Doc;
import dev.jingyu.ms.index.InvertedIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A distributed thesaurus: for every term, the terms that live in the same documents.
 *
 * The oldest trick in retrieval (SMART, 1970s) and the one that still works on eighty documents.
 * Word embeddings need millions of tokens of context before they are anything but noise; a
 * co-occurrence table only needs the corpus to be itself. Two documents that share no query word but
 * are built from the same vocabulary end up close, and expanding the query with those neighbours lets
 * BM25 reach them.
 *
 * Relatedness is cosine over binary document-indicator vectors, co / sqrt(df_a * df_b): the classic
 * coordinate-matrix idea, in one pass and with no training.
 */
public final class DistributedThesaurus {

    static final int SAMPLE_DOCS = 3000;
    static final int MAX_TERMS_PER_DOC = 60;

    private final Map<String, List<String>> related = new HashMap<>();
    private final int topK;
    private final int minCooccurrence;
    private final double minCosine;

    public DistributedThesaurus(int topK, int minCooccurrence, double minCosine) {
        this.topK = topK;
        this.minCooccurrence = minCooccurrence;
        this.minCosine = minCosine;
    }

    public static DistributedThesaurus standard() { return new DistributedThesaurus(8, 2, 0.08); }

    public int size() { return related.size(); }

    public List<String> related(String term) { return related.getOrDefault(term, List.of()); }

    /**
     * Count term pairs inside documents. Cost is bounded three ways, because all-pairs over a 50k
     * document vocabulary is not something you can wait for: only SAMPLE_DOCS documents contribute
     * (evenly spaced, so the sample is not just the first page of the corpus), hub terms are dropped,
     * and each document yields at most MAX_TERMS_PER_DOC of its rarest terms.
     */
    public static DistributedThesaurus build(InvertedIndex index, List<Doc> docs, ChineseAnalyzer analyzer,
                                             int topK, int minCo, double minCos) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, List<String>> perDoc = new LinkedHashMap<>();
        for (Doc d : docs) {
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(tokens(analyzer, d)));
            perDoc.put(d.externalId(), distinct);
            for (String t : distinct) df.merge(t, 1, Integer::sum);
        }
        int n = Math.max(1, docs.size());
        int step = Math.max(1, n / SAMPLE_DOCS);
        Map<String, Integer> cooc = new HashMap<>(1 << 16);
        for (int i = 0; i < docs.size(); i += step) {
            List<String> terms = new ArrayList<>(perDoc.get(docs.get(i).externalId()));
            terms.removeIf(t -> df.getOrDefault(t, 0) < minCo || df.get(t) > n * 0.25);
            if (terms.size() > MAX_TERMS_PER_DOC) {
                terms.sort(Comparator.comparingInt((String t) -> df.getOrDefault(t, 0)).thenComparing(t -> t));
                terms = new ArrayList<>(terms.subList(0, MAX_TERMS_PER_DOC));
            }
            for (int a = 0; a < terms.size(); a++) {
                for (int b = a + 1; b < terms.size(); b++) {
                    String x = terms.get(a), y = terms.get(b);
                    String key = x.compareTo(y) < 0 ? x + "\t" + y : y + "\t" + x;
                    cooc.merge(key, 1, Integer::sum);
                }
            }
        }
        DistributedThesaurus th = new DistributedThesaurus(topK, minCo, minCos);
        Map<String, List<Object[]>> byTerm = new HashMap<>();
        for (Map.Entry<String, Integer> e : cooc.entrySet()) {
            int co = e.getValue();
            if (co < minCo) continue;
            String[] pair = e.getKey().split("\t");
            double cos = co / Math.sqrt((double) df.get(pair[0]) * df.get(pair[1]));
            if (cos < minCos) continue;
            byTerm.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(new Object[]{cos, pair[1]});
            byTerm.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(new Object[]{cos, pair[0]});
        }
        for (Map.Entry<String, List<Object[]>> e : byTerm.entrySet()) {
            List<Object[]> scored = e.getValue();
            scored.sort((p, q) -> Double.compare((double) q[0], (double) p[0]));
            List<String> tops = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, scored.size()); i++) tops.add((String) scored.get(i)[1]);
            th.related.put(e.getKey(), tops);
        }
        return th;
    }

    private static List<String> tokens(ChineseAnalyzer analyzer, Doc d) {
        return analyzer.terms(d.field(Doc.TITLE) + " " + d.field(Doc.BODY) + " " + d.field(Doc.TAGS));
    }

    /**
     * Terms to add to a query. An expansion term is repeated by its rank weight, because
     * {@link dev.jingyu.ms.ranking.Bm25} scores a query term once per occurrence: repetition is how a
     * thesaurus confidence value becomes a soft score boost without inventing a new scoring function.
     */
    public List<String> expand(List<String> queryTerms, int budget) {
        Map<String, Integer> extras = new LinkedHashMap<>();
        for (String t : queryTerms) {
            List<String> rel = related.get(t);
            if (rel == null) continue;
            for (int i = 0; i < rel.size(); i++) {
                String e = rel.get(i);
                if (queryTerms.contains(e)) continue;
                extras.merge(e, i < 3 ? 2 : 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(extras.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
                .thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            for (int i = 0; i < e.getValue() && out.size() < budget; i++) out.add(e.getKey());
            if (out.size() >= budget) break;
        }
        return out;
    }

    public Map<String, Object> debugInfo(String term) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", term);
        m.put("related", related.getOrDefault(term, List.of()));
        return m;
    }

    @Override public String toString() {
        return "DistributedThesaurus{entries=" + related.size() + ", topK=" + topK
                + ", minCo=" + minCooccurrence + ", minCos=" + minCosine + "}";
    }
}
