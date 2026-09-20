package dev.jingyu.ms.ranking;

import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.index.PostingList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 over multiple boosted fields.
 *
 * <p>Per field, per query term:
 * <pre>
 *   idf  = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   part = idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avgLen))
 *   add  = boost[field] * part
 * </pre>
 * {@code k1} caps how much extra evidence a repeated term adds; {@code b} decides how strongly long
 * documents are penalised. Field boosts let a title hit count for more than a body hit without
 * needing a separate index.
 */
public final class Bm25 {

    public final double k1;
    public final double b;
    private final double[] boosts;

    public Bm25() { this(1.2, 0.75, new double[]{3.0, 1.0, 2.0}); }

    public Bm25(double k1, double b, double[] boosts) {
        this.k1 = k1;
        this.b = b;
        this.boosts = boosts;
    }

    public Bm25 withK1B(double k1, double b) { return new Bm25(k1, b, boosts); }

    public Bm25 withBoosts(double... fieldBoosts) { return new Bm25(k1, b, fieldBoosts); }

    public static double idf(int numDocs, int docFreq) {
        return Math.log(1 + (numDocs - docFreq + 0.5) / (docFreq + 0.5));
    }

    /** Score every matching live document. Documents with no query term never appear. */
    public Map<Integer, Double> score(InvertedIndex index, List<String> queryTerms) {
        Map<Integer, Double> scores = new HashMap<>();
        int n = index.numDocs();
        if (n == 0) return scores;
        for (String term : queryTerms) {
            int df = index.docFreq(term);
            if (df == 0) continue;
            double idf = idf(n, df);
            for (int f = 0; f < InvertedIndex.FIELDS.size(); f++) {
                PostingList p = index.postings(f, term);
                if (p == null) continue;
                double avg = index.avgFieldLength(f);
                double boost = boosts[f];
                int len = p.size();
                for (int i = 0; i < len; i++) {
                    int docId = p.doc(i);
                    if (index.isDeleted(docId)) continue;
                    int tf = p.freq(i);
                    double norm = 1 - b + b * index.fieldLength(f, docId) / avg;
                    scores.merge(docId, boost * idf * tf * (k1 + 1) / (tf + k1 * norm), Double::sum);
                }
            }
        }
        return scores;
    }

    /** Upper bound on what an unprocessed term can still contribute -- the WAND pruning key. */
    public double maxIdf(InvertedIndex index, String term) {
        return idf(index.numDocs(), Math.max(1, index.docFreq(term))) * maxBoost();
    }

    public double maxBoost() {
        double m = 0;
        for (double x : boosts) m = Math.max(m, x);
        return m;
    }
}
