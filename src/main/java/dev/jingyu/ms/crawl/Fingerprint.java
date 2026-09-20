package dev.jingyu.ms.crawl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 64-bit URL fingerprints for crawl de-duplication.
 *
 * <p>Storing every visited URL as a string is wasteful once a crawl reaches tens of thousands of
 * pages; a 64-bit hash of the normalised URL is, at that scale,碰撞概率仍以十亿分之一计。Collisions
 * cost a missed page, not a wrong index entry, which is an acceptable trade for a demo crawler.
 *
 * <p>Normalisation matters more than the hash: without dropping fragments, sorting parameters and
 * lowercasing the host, {@code /a?x=1&y=2} and {@code /a?y=2&x=1#/top} crawl forever.
 */
public final class Fingerprint {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Fingerprint() {}

    public static String normalise(String url) {
        if (url == null) return "";
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int scheme = u.indexOf("://");
        String schemePart = scheme < 0 ? "" : u.substring(0, scheme + 3).toLowerCase(Locale.ROOT);
        String rest = scheme < 0 ? u : u.substring(scheme + 3);
        int pathStart = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String path = pathStart < 0 ? "" : rest.substring(pathStart);
        authority = authority.toLowerCase(Locale.ROOT);
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            List<String> parts = new ArrayList<>(Arrays.asList(path.substring(q + 1).split("&")));
            parts.removeIf(s -> s.isEmpty());
            Collections.sort(parts);
            query = parts.isEmpty() ? "" : "?" + String.join("&", parts);
            path = path.substring(0, q);
        }
        if (authority.endsWith(":80") || authority.endsWith(":443")) {
            authority = authority.substring(0, authority.lastIndexOf(':'));
        }
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() && !query.isEmpty()) path = "/";
        return schemePart + authority + path + query;
    }

    public static long of(String url) {
        String s = normalise(url);
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** A double hash reduces the collision rate further at negligible cost. */
    public static long ofStrong(String url) {
        String s = normalise(url);
        long a = of(s);
        long b = FNV_OFFSET;
        for (int i = s.length() - 1; i >= 0; i--) {
            b ^= s.charAt(i);
            b *= FNV_PRIME;
        }
        return a ^ Long.rotateLeft(b, 32);
    }
}
