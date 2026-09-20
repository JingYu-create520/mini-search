package dev.jingyu.ms.search;

import java.util.List;
import java.util.Map;

/** One ranked result. {@code score} means different things per mode; {@code parts} keeps both. */
public record Hit(int docId,
                  String id,
                  String url,
                  String title,
                  String snippet,
                  double score,
                  Double bm25,
                  Double cosine,
                  int rank,
                  List<String> tags,
                  Map<String, Double> contributions) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("docId", docId);
        m.put("id", id);
        m.put("url", url);
        m.put("title", title);
        m.put("snippet", snippet);
        m.put("score", Math.round(score * 1e6) / 1e6);
        if (bm25 != null) m.put("bm25", Math.round(bm25 * 1e6) / 1e6);
        if (cosine != null) m.put("cosine", Math.round(cosine * 1e6) / 1e6);
        m.put("rank", rank);
        m.put("tags", tags);
        if (contributions != null && !contributions.isEmpty()) m.put("explain", contributions);
        return m;
    }
}
