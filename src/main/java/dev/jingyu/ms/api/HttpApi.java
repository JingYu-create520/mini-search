package dev.jingyu.ms.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The HTTP face of the engine, built on the JDK's own {@code HttpServer}: no servlet container, no
 * framework, no dependency. Routes are a switch, bodies are read as UTF-8 text and written as JSON
 * with an explicit charset, which is the whole reason Chinese output is not mojibake.
 *
 * <p>Everything the web UI can do is available here, and everything available here is what the MCP
 * server exposes, so the three surfaces cannot drift apart.
 */
public final class HttpApi {

    private final Engine engine;
    private final String host;
    private final int port;
    private HttpServer server;

    /** Loopback-only by default: see {@link #HttpApi(Engine, String, int)}. */
    public HttpApi(Engine engine, int port) {
        this(engine, "127.0.0.1", port);
    }

    /**
     * @param host interface to bind. The defaults used to be {@code new InetSocketAddress(port)},
     *     which is the wildcard address -- so a tool described as local-first was in fact
     *     listening on every interface, with unauthenticated write endpoints
     *     ({@code POST /api/index}, {@code DELETE /api/index}, {@code POST /api/crawl}) behind it.
     *     Anyone on the same LAN could rewrite the index or point the crawler at a URL of their
     *     choosing. Pass {@code 0.0.0.0} explicitly when you mean it -- the Docker image does.
     */
    public HttpApi(Engine engine, String host, int port) {
        this.engine = engine;
        this.host = host;
        this.port = port;
    }

    /** Address the server actually bound to; port 0 was accepted for tests. */
    public InetSocketAddress boundAddress() {
        return server == null ? new InetSocketAddress(host, port) : server.getAddress();
    }

    /** Port the server actually bound to; 0 was accepted for tests. */
    public int boundPort() {
        return boundAddress().getPort();
    }

    private dev.jingyu.ms.crawl.Crawler crawler;

    /** Optional: enables POST /api/crawl. */
    public HttpApi withCrawler(dev.jingyu.ms.crawl.Crawler c) {
        this.crawler = c;
        return this;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                    Thread t = new Thread(r, "ms-http");
                    t.setDaemon(true);
                    return t;
                }));
        server.createContext("/api/search", this::handleSearch);
        server.createContext("/api/stats", this::handleStats);
        server.createContext("/api/doc", this::handleDoc);
        server.createContext("/api/index", this::handleIndex);
        server.createContext("/api/crawl", this::handleCrawl);
        server.createContext("/", this::handleStatic);
        server.start();
        Log.info("HTTP API listening on http://localhost:%d", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() { return port; }

    // ------------------------------------------------------------------ routes

    private void handleSearch(HttpExchange ex) throws IOException {
        if (!cors(ex, "GET")) return;
        Map<String, String> q = query(ex);
        String text = q.getOrDefault("q", "").trim();
        Searcher.Mode mode = Searcher.Mode.parse(q.get("mode"), Searcher.Mode.HYBRID);
        int topK = intOr(q.get("topK"), 10);
        int from = intOr(q.get("from"), 0);
        boolean phrase = boolOr(q.get("phrase"));
        boolean highlight = !boolOr(q.get("noHighlight"));
        if (text.isEmpty()) {
            send(ex, 400, Json.write(Map.of("error", "q is required")));
            return;
        }
        long t0 = System.nanoTime();
        // Fetched per request, not cached at construction: POST /api/index and /api/crawl
        // republish the engine's Searcher, and a cached reference kept serving a server whose
        // vector and thesaurus layers stayed switched off no matter what was indexed.
        Searcher.Result r = engine.searcher().search(text, mode, topK, from, phrase, highlight);
        Map<String, Object> body = new LinkedHashMap<>(r.toMap());
        body.put("query", text);
        send(ex, 200, Json.write(body));
    }

    private void handleStats(HttpExchange ex) throws IOException {
        if (!cors(ex, "GET")) return;
        Map<String, Object> m = new LinkedHashMap<>(engine.stats());
        m.put("port", port);
        m.put("java", System.getProperty("java.version"));
        send(ex, 200, Json.write(m));
    }

    private void handleDoc(HttpExchange ex) throws IOException {
        if (!cors(ex, "GET")) return;
        String id = query(ex).get("id");
        int docId = intOr(query(ex).get("docId"), -1);
        Map<String, Object> out = new LinkedHashMap<>();
        dev.jingyu.ms.index.Doc d = docId >= 0 ? engine.doc(docId)
                : id == null ? null : engine.docByExternalId(id);
        if (d == null) {
            send(ex, 404, Json.write(Map.of("error", "no such document")));
            return;
        }
        out.put("docId", d.id());
        out.put("id", d.externalId());
        out.put("url", d.url());
        out.put("title", d.field(dev.jingyu.ms.index.Doc.TITLE));
        out.put("body", d.field(dev.jingyu.ms.index.Doc.BODY));
        out.put("tags", d.field(dev.jingyu.ms.index.Doc.TAGS));
        out.put("tokens", engine.analyzer().terms(d.field(dev.jingyu.ms.index.Doc.TITLE) + " "
                + d.field(dev.jingyu.ms.index.Doc.BODY)));
        send(ex, 200, Json.write(out));
    }

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!cors(ex, ex.getRequestMethod().equals("DELETE") ? "DELETE" : "POST")) return;
        if ("DELETE".equals(ex.getRequestMethod())) {
            String id = query(ex).get("id");
            boolean ok = id != null && engine.delete(id);
            send(ex, ok ? 200 : 404, Json.write(Map.of("deleted", ok, "id", id == null ? "" : id)));
            return;
        }
        Map<String, Object> o = Json.parseObject(readBody(ex));
        String id = Json.str(o, "id", "");
        if (id.isEmpty()) { send(ex, 400, Json.write(Map.of("error", "id is required"))); return; }
        var doc = engine.add(id, Json.str(o, "url", ""), Json.str(o, "title", ""),
                Json.str(o, "body", ""), Json.str(o, "tags", ""));
        boolean refresh = Json.str(o, "refresh", "false").equals("true");
        if (refresh) engine.refreshVectors();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", true);
        out.put("docId", doc.id());
        out.put("id", id);
        out.put("documents", engine.numDocs());
        send(ex, 200, Json.write(out));
    }

    private void handleCrawl(HttpExchange ex) throws IOException {
        if (!cors(ex, "POST")) return;
        if (crawler == null) {
            send(ex, 503, Json.write(Map.of("error", "crawler not enabled on this server")));
            return;
        }
        Map<String, Object> o = Json.parseObject(readBody(ex));
        String url = Json.str(o, "url", "");
        if (url.isEmpty()) { send(ex, 400, Json.write(Map.of("error", "url is required"))); return; }
        var result = crawler.fetchAndIndex(engine, url);
        send(ex, result.ok() ? 200 : 422, Json.write(result.toMap()));
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        cors(ex, "GET");
        String path = ex.getRequestURI().getPath();
        if (path.equals("/api") || path.startsWith("/api/")) {
            send(ex, 404, Json.write(Map.of("error", "unknown endpoint", "path", path)));
            return;
        }
        byte[] body = StaticFiles.read(path);
        if (body == null) {
            send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, StaticFiles.contentType(path), body);
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean cors(HttpExchange ex, String method) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return false;
        }
        return true;
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int intOr(String v, int dflt) {
        try { return v == null ? dflt : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static boolean boolOr(String v) {
        return v != null && (v.isEmpty() || v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on"));
    }

    /** Endpoint list, so the README and the MCP tool descriptions can be kept honest. */
    public static List<String> endpoints() {
        return new ArrayList<>(List.of("GET /api/search", "GET /api/stats", "GET /api/doc",
                "POST /api/index", "DELETE /api/index", "POST /api/crawl", "GET /* (static UI)"));
    }
}
