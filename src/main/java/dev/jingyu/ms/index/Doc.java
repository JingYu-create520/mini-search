package dev.jingyu.ms.index;

import java.util.LinkedHashMap;
import java.util.Map;

/** A searchable document: an internal int id plus the fields the analyser will cut. */
public final class Doc {

    public static final String TITLE = "title";
    public static final String BODY = "body";
    public static final String TAGS = "tags";

    private final int id;
    private final String externalId;
    private final Map<String, String> fields;
    private final String url;

    public Doc(int id, String externalId, String url, Map<String, String> fields) {
        this.id = id;
        this.externalId = externalId;
        this.url = url == null ? "" : url;
        this.fields = new LinkedHashMap<>(fields);
    }

    public int id() { return id; }
    /** Caller-supplied stable id (a URL hash, a corpus id); the engine never rewrites it. */
    public String externalId() { return externalId; }
    public String url() { return url; }
    public String field(String name) { return fields.getOrDefault(name, ""); }
    public Map<String, String> fields() { return fields; }

    /** Text used to build the semantic vector: title gets repeated so it weighs more. */
    public String vectorText() {
        String t = field(TITLE);
        return t + "。" + t + "。" + field(TAGS) + "。" + field(BODY);
    }

    @Override public String toString() { return "Doc#" + id + "(" + field(TITLE) + ")"; }
}
