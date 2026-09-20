package dev.jingyu.ms.hybrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: merge ranked lists using only their ranks.
 *
 * <p>A document at rank {@code r} in a list contributes {@code 1 / (k + r)} with {@code k} typically
 * 60. Because only ranks are used, nothing needs to be calibrated between a BM25 score in the tens
 * and a cosine in [0,1] -- the awkward part of mixing retrieval models disappears. The cost is that
 * gaps between ranks are treated as uniform, so a dominant first place is not rewarded more than a
 * marginal one.
 */
public final class Rrf {

    public static final int DEFAULT_K = 60;

    private Rrf() {}

    public static Map<Integer, Double> fuse(List<List<Integer>> rankedLists, int k) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (List<Integer> list : rankedLists) {
            for (int r = 0; r < list.size(); r++) {
                out.merge(list.get(r), 1.0 / (k + r + 1), Double::sum);
            }
        }
        return out;
    }

    /** Rank-order fusion with a per-list weight; weight 0 effectively drops that list. */
    public static List<Integer> fuse(List<List<Integer>> rankedLists, double[] weights, int k, int topN) {
        Map<Integer, Double> merged = new LinkedHashMap<>();
        for (int i = 0; i < rankedLists.size(); i++) {
            double w = weights == null ? 1.0 : weights[Math.min(i, weights.length - 1)];
            List<Integer> list = rankedLists.get(i);
            for (int r = 0; r < list.size(); r++) merged.merge(list.get(r), w / (k + r + 1), Double::sum);
        }
        return topOrder(merged, topN);
    }

    public static List<Integer> topOrder(Map<Integer, Double> scores, int topN) {
        List<Map.Entry<Integer, Double>> e = new ArrayList<>(scores.entrySet());
        e.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(x -> -x.getValue())
                .thenComparingInt(Map.Entry::getKey));
        List<Integer> out = new ArrayList<>(Math.min(topN, e.size()));
        for (int i = 0; i < e.size() && out.size() < topN; i++) out.add(e.get(i).getKey());
        return out;
    }

    /**
     * Weighted score fusion, the alternative to RRF. Both sides are min-max normalised first, which
     * is exactly the calibration step RRF avoids -- kept here because it is sometimes better and
     * because the eval harness compares the two.
     */
    public static Map<Integer, Double> weighted(Map<Integer, Double> a, Map<Integer, Double> b, double alpha) {
        Map<Integer, Double> na = normalize(a), nb = normalize(b);
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : na.entrySet()) out.merge(e.getKey(), alpha * e.getValue(), Double::sum);
        for (Map.Entry<Integer, Double> e : nb.entrySet()) out.merge(e.getKey(), (1 - alpha) * e.getValue(), Double::sum);
        return out;
    }

    static Map<Integer, Double> normalize(Map<Integer, Double> in) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double v : in.values()) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (Double.isInfinite(lo)) return Map.of();
        double span = hi - lo;
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : in.entrySet()) out.put(e.getKey(), span == 0 ? 1.0 : (e.getValue() - lo) / span);
        return out;
    }
}
