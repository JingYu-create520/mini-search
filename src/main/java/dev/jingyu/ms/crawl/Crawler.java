package dev.jingyu.ms.crawl;

import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A polite breadth-first crawler: robots first, per-host rate limiting, 64-bit de-duplication, a size
 * cap, and a User-Agent that says who to contact.
 *
 * <p>Every failure mode is a {@link CrawlResult} with a reason rather than an exception, because a
 * crawler that dies on the first 404 never finishes. What it does when the world is unfriendly is the
 * part worth testing, so the test suite runs it against a local HTTP server: 404, 500, a forbidden
 * robots path, a page with no prose, a page in GBK, and a redirect.
 */
public final class Crawler {

    public record CrawlResult(String url, boolean ok, String reason, String title, int chars,
                              int links, int status, String charset, long millis) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", url);
            m.put("indexed", ok);
            m.put("reason", reason);
            m.put("title", title);
            m.put("chars", chars);
            m.put("links", links);
            m.put("status", status);
            m.put("charset", charset);
            m.put("millis", millis);
            return m;
        }
    }

    private final Fetcher fetcher;
    private final Robots robots;
    private final Map<String, Long> lastHit = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();
    private final long minIntervalMillis;
    private final int timeoutMs;
    private final String userAgent;
    private final UrlPolicy policy;

    /** Public addresses only, which is what a process that might be reached by others should use. */
    public Crawler(String userAgent, long minIntervalMillis, int maxBytes, int timeoutMs) {
        this(userAgent, minIntervalMillis, maxBytes, timeoutMs, UrlPolicy.STANDARD);
    }

    public Crawler(String userAgent, long minIntervalMillis, int maxBytes, int timeoutMs, UrlPolicy policy) {
        this.userAgent = userAgent;
        this.minIntervalMillis = minIntervalMillis;
        this.timeoutMs = timeoutMs;
        this.policy = policy;
        this.fetcher = new Fetcher(userAgent, maxBytes);
        this.robots = new Robots(userAgent);
    }

    public static Crawler standard() {
        return withPolicy(UrlPolicy.STANDARD);
    }

    /** Same crawler, different idea of what may be fetched. */
    public static Crawler withPolicy(UrlPolicy policy) {
        return new Crawler("mini-search/0.1 (+https://github.com/JingYu-create520/mini-search)",
                1000, 2 << 20, 12_000, policy);
    }

    public CrawlResult fetchAndIndex(Engine engine, String url) {
        return fetchPage(engine, url).result();
    }

    /** A result line plus the links parsed out of the same body, so crawling need not ask twice. */
    private record Fetched(CrawlResult result, List<String> links) {}

    /**
     * Fetch one page, extract it, index it. Never throws.
     *
     * <p>The target is checked before robots.txt is even looked at, because fetching robots.txt is
     * itself a request to the address being refused.
     */
    private Fetched fetchPage(Engine engine, String url) {
        long t0 = System.nanoTime();
        String normalized = Fingerprint.normalise(url);
        if (normalized.isEmpty()) return new Fetched(fail(url, "empty url", 0, 0, "", t0), List.of());
        if (!normalized.startsWith("http")) {
            return new Fetched(fail(url, "only http(s) URLs are crawled", 0, 0, "", t0), List.of());
        }
        UrlPolicy.Decision allowed = policy.check(normalized);
        if (!allowed.allowed()) {
            return new Fetched(fail(normalized, "refused: " + allowed.reason(), 0, 0, "", t0), List.of());
        }

        if (!robots.allowed(normalized)) {
            return new Fetched(new CrawlResult(normalized, false, "disallowed by robots.txt", "", 0, 0, 0, "",
                    ms(t0)), List.of());
        }
        waitTurn(normalized);
        try {
            Fetcher.Response res = fetcher.fetch(normalized, timeoutMs);
            if (!res.ok()) return new Fetched(fail(normalized, "HTTP " + res.status(), res.status(), 0, "", t0),
                    List.of());
            // A public page may redirect somewhere private. The request has happened by the time
            // java.net.http reports the final URL, so what this can still prevent is indexing and
            // echoing the content back; SECURITY.md states the rest.
            UrlPolicy.Decision landed = policy.check(res.finalUrl());
            if (!landed.allowed()) {
                return new Fetched(fail(normalized, "refused after redirect: " + landed.reason(), res.status(),
                        0, charsetOf(res), t0), List.of());
            }
            String html = res.decode();
            HtmlExtractor.Page page = HtmlExtractor.extract(html, res.finalUrl());
            if (page.title().isBlank() && page.text().isBlank()) {
                return new Fetched(new CrawlResult(normalized, false, "no extractable content", "", 0,
                        page.links().size(), res.status(), charsetOf(res), ms(t0)), page.links());
            }
            engine.add("url:" + normalized, normalized, page.title(), page.text(), "");
            return new Fetched(new CrawlResult(normalized, true, "", page.title(), page.text().length(),
                    page.links().size(), res.status(), charsetOf(res), ms(t0)), page.links());
        } catch (IOException e) {
            return new Fetched(fail(normalized, "fetch failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 0, 0, "", t0),
                    List.of());
        } catch (RuntimeException e) {
            return new Fetched(fail(normalized, "unexpected: " + e.getClass().getSimpleName() + " "
                    + e.getMessage(), 0, 0, "", t0), List.of());
        }
    }

    /** BFS from the seeds; returns one result per attempted URL, including the failures. */
    public List<CrawlResult> crawl(Engine engine, List<String> seeds, int maxDocs, int maxDepth) {
        List<CrawlResult> results = new ArrayList<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        for (String s : seeds) queue.add(new Object[]{Fingerprint.normalise(s), 0});
        int indexed = 0;

        while (!queue.isEmpty() && indexed < maxDocs) {
            Object[] top = queue.poll();
            String url = (String) top[0];
            int depth = (int) top[1];
            if (url.isEmpty() || !seen.add(Fingerprint.ofStrong(url))) continue;
            Fetched fetched = fetchPage(engine, url);
            CrawlResult r = fetched.result();
            results.add(r);
            if (r.ok()) indexed++;
            if (depth >= maxDepth || !r.ok()) continue;
            // The links came out of the body we already have. An earlier version fetched the page a
            // second time here, which doubled the request rate against every host and quietly broke
            // the per-host politeness limit this class exists to keep.
            for (String link : fetched.links()) {
                String n = Fingerprint.normalise(link);
                if (!n.isEmpty() && !seen.contains(Fingerprint.ofStrong(n))) queue.add(new Object[]{n, depth + 1});
            }
        }
        Log.info("crawl finished: %d urls attempted, %d indexed", results.size(), indexed);
        return results;
    }

    /** Links discovered without re-fetching: the caller already has the page. */
    public List<String> linksOf(String html, String base) {
        return HtmlExtractor.extract(html, base).links();
    }

    private void waitTurn(String url) {
        String host = hostOf(url);
        long now = System.currentTimeMillis();
        Long prev = lastHit.get(host);
        long interval = Math.max(minIntervalMillis, (long) (robots.crawlDelaySeconds(url) * 1000));
        if (prev != null) {
            long wait = prev + interval - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastHit.put(host, System.currentTimeMillis());
    }

    private static String charsetOf(Fetcher.Response res) {
        String cs = res.headerCharset();
        return cs == null ? "detected" : cs;
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() == null ? url : u.getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    private static CrawlResult fail(String url, String reason, int status, int links, String charset, long t0) {
        return new CrawlResult(url, false, reason, "", 0, links, status, charset, ms(t0));
    }
}
