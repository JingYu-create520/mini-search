package dev.jingyu.ms.crawl;

import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * robots.txt: fetch once per host, remember, and obey.
 *
 * <p>Rules are evaluated per user-agent group. {@code Allow} beats {@code Disallow} on a longer
 * prefix, an empty {@code Disallow:} means everything is allowed, and a missing or unreadable file
 * means nothing is restricted. {@code Crawl-delay} is honoured when present.
 *
 * <p>Failing closed (blocking the whole host because the file could not be read) would be the safe
 * choice for a commercial crawler; here a network error on robots.txt is treated as "no rules", but
 * a <em>parse</em> error falls back to blocking, because that means the site did speak and we could
 * not understand it.
 */
public final class Robots {

    private record Rule(boolean allow, String path) {}

    private static final class Policy {
        final java.util.List<Rule> rules = new java.util.ArrayList<>();
        double crawlDelay = -1;
        boolean loaded;
    }

    private final Map<String, Policy> byHost = new HashMap<>();
    private final String userAgent;

    public Robots(String userAgent) { this.userAgent = userAgent; }

    /**
     * Install a host's robots.txt without fetching it. Tests use this to exercise rule evaluation
     * without touching the network, and it doubles as the hook for an operator-supplied policy file.
     */
    public Robots preload(String url, String robotsTxt) {
        Policy p = new Policy();
        parse(robotsTxt, p);
        p.loaded = true;
        byHost.put(hostOf(url), p);
        return this;
    }

    public boolean allowed(String url) {
        try {
            return allowed(url, policy(url));
        } catch (RuntimeException e) {
            Log.warn("robots check failed for %s (%s); skipping URL", url, e.getMessage());
            return false;
        }
    }

    public double crawlDelaySeconds(String url) {
        try {
            Policy p = policy(url);
            return p.crawlDelay < 0 ? 0 : p.crawlDelay;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean allowed(String url, Policy p) {
        if (!p.loaded) return true;                       // nothing published, nothing restricted
        String path = pathOf(url);
        Rule best = null;
        for (Rule r : p.rules) {
            if (path.startsWith(r.path()) || match(r.path(), path)) {
                if (best == null || r.path().length() > best.path().length()) best = r;
            }
        }
        return best == null || best.allow();
    }

    /**
     * Google-style wildcard matching: {@code *} matches any run of characters. A trailing {@code $}
     * is accepted and ignored, because this implementation already requires the pattern to consume
     * the whole path.
     */
    static boolean match(String pattern, String path) {
        String p = pattern;
        if (p.endsWith("$")) p = p.substring(0, p.length() - 1);
        if (!p.contains("*")) return false;
        int pi = 0, ti = 0, star = -1, mark = 0;
        while (ti < path.length()) {
            if (pi < p.length() && (p.charAt(pi) == path.charAt(ti))) { pi++; ti++; }
            else if (pi < p.length() && p.charAt(pi) == '*') { star = pi; mark = ti; pi++; }
            else if (star >= 0) { pi = star + 1; ti = ++mark; }
            else return false;
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }

    private Policy policy(String url) {
        String host = hostOf(url);
        Policy p = byHost.get(host);
        if (p == null) {
            p = new Policy();
            byHost.put(host, p);
            load(p, (url.startsWith("https") ? "https://" : "http://") + host + "/robots.txt");
        }
        return p;
    }

    private void load(Policy p, String robotsUrl) {
        try {
            byte[] body = Fetcher.get(robotsUrl, userAgent, 8000).body();
            parse(new String(body, StandardCharsets.UTF_8), p);
            p.loaded = true;
        } catch (IOException e) {
            p.loaded = false;   // unreachable: no rules
        }
    }

    void parse(String text, Policy p) {
        boolean relevant = false;
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int colon = l.indexOf(':');
            if (colon < 0) continue;
            String key = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = l.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    String ua = val.toLowerCase(Locale.ROOT);
                    relevant = ua.equals("*") || userAgent.toLowerCase(Locale.ROOT).startsWith(ua)
                            || ua.startsWith(userAgent.toLowerCase(Locale.ROOT));
                }
                case "disallow" -> {
                    if (relevant && !val.isEmpty() && p.rules.stream().noneMatch(r -> r.path().equals(val)))
                        p.rules.add(new Rule(false, val));
                }
                case "allow" -> {
                    if (relevant && !val.isEmpty()) p.rules.add(new Rule(true, val));
                }
                case "crawl-delay" -> {
                    if (relevant) {
                        try { p.crawlDelay = Double.parseDouble(val); } catch (NumberFormatException ignored) {}
                    }
                }
                default -> { }
            }
        }
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null) return url;
            return u.getPort() > 0 && u.getPort() != 80 && u.getPort() != 443 ? host + ":" + u.getPort() : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String pathOf(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getRawPath();
            String q = u.getRawQuery();
            return (path == null || path.isEmpty() ? "/" : path) + (q == null ? "" : "?" + q);
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
