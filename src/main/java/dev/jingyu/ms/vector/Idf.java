package dev.jingyu.ms.vector;

import java.util.HashMap;
import java.util.Map;

/** Document frequencies and the inverse-document-frequency table shared by both encoders. */
public final class Idf {

    private final Map<String, Integer> df;
    private final int totalDocs;

    public Idf(Map<String, Integer> df, int totalDocs) {
        this.df = df;
        this.totalDocs = Math.max(1, totalDocs);
    }

    public static Idf empty() { return new Idf(new HashMap<>(), 1); }

    public int df(String term) { return df.getOrDefault(term, 0); }

    /** Smoothed IDF, always positive; unseen terms get the rarest-word weight. */
    public double idf(String term) {
        int d = df.getOrDefault(term, 1);
        return Math.log((double) totalDocs / d) + 1.0;
    }

    public int size() { return df.size(); }

    public Map<String, Integer> raw() { return df; }
}
