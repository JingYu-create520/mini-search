package dev.jingyu.ms.mcp;

import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.crawl.Crawler;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server over stdio: newline-delimited JSON-RPC 2.0, one message per line, which is what the
 * Model Context Protocol's stdio transport actually is.
 *
 * <p>Only four methods are implemented -- {@code initialize}, {@code tools/list}, {@code tools/call}
 * and {@code ping} -- because that is all a client needs to discover and invoke tools. The tool
 * surface is the same search the browser UI uses, so an agent gets the local Google rather than a
 * degraded copy of it.
 */
public final class McpServer {

    private static final String PROTOCOL = "2025-06-18";

    private final Engine engine;
    private Crawler crawler;

    public McpServer(Engine engine) { this.engine = engine; }

    public McpServer withCrawler(Crawler c) { this.crawler = c; return this; }

    public void serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String reply = handle(line);
            if (reply == null) continue;
            w.write(reply);
            w.write("\n");
            w.flush();
        }
    }

    /** Public for tests: one request line in, one response line out (null if none should be sent). */
    public String handle(String line) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(line);
        } catch (RuntimeException e) {
            return error(null, -32700, "parse error");
        }
        Object id = req.get("id");
        String method = Json.str(req, "method", "");
        Map<String, Object> params = Json.obj(req.get("params"));
        try {
            return switch (method) {
                case "initialize" -> result(id, Map.of(
                        "protocolVersion", PROTOCOL,
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "mini-search", "version", "0.1.0",
                                "title", "Local-first hybrid search engine for Chinese")));
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping" -> result(id, Map.of());
                case "tools/list" -> result(id, Map.of("tools", toolList()));
                case "tools/call" -> result(id, callTool(Json.str(params, "name", ""),
                        Json.obj(params.get("arguments"))));
                default -> error(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException e) {
            Log.warn("mcp %s failed: %s", method, e.getMessage());
            return error(id, -32603, e.getMessage());
        }
    }

    private static List<Object> toolList() {
        List<Object> tools = new ArrayList<>();
        tools.add(tool("search",
                "Search the local index. Returns ranked documents with title, id, url, score and a "
                        + "highlighted snippet. mode=hybrid is the default and usually the best.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Chinese or mixed text"),
                                "mode", Map.of("type", "string", "enum", List.of("bm25", "vector", "hybrid")),
                                "topK", Map.of("type", "integer", "minimum", 1, "maximum", 50)),
                        "required", List.of("query"))));
        tools.add(tool("index_url",
                "Fetch one web page politely (robots.txt, rate limit), extract its main text and add "
                        + "it to the index. Returns what was stored.",
                Map.of("type", "object",
                        "properties", Map.of("url", Map.of("type", "string")),
                        "required", List.of("url"))));
        tools.add(tool("index_text",
                "Index a piece of text directly, without crawling.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "stable document id"),
                                "title", Map.of("type", "string"),
                                "body", Map.of("type", "string"),
                                "url", Map.of("type", "string"),
                                "tags", Map.of("type", "string")),
                        "required", List.of("id", "title", "body"))));
        tools.add(tool("stats", "Engine statistics: document count, term count, dictionary size, "
                + "which retrieval models are active.", Map.of("type", "object", "properties", Map.of())));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", schema);
        return m;
    }

    private Map<String, Object> callTool(String name, Map<String, Object> args) {
        return switch (name) {
            case "search" -> {
                String q = Json.str(args, "query", "");
                Searcher.Mode mode = Searcher.Mode.parse(Json.str(args, "mode", "hybrid"), Searcher.Mode.HYBRID);
                int topK = Math.max(1, Math.min(50, Json.integer(args, "topK", 10)));
                Searcher.Result r = engine.searcher().search(q, mode, topK, 0, false, true);
                List<Object> hits = new ArrayList<>();
                for (var h : r.hits()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rank", h.rank() + 1);
                    m.put("id", h.id());
                    m.put("title", h.title());
                    m.put("url", h.url());
                    m.put("score", Math.round(h.score() * 1000) / 1000.0);
                    m.put("snippet", h.snippet());
                    hits.add(m);
                }
                yield text(Map.of("query", q, "mode", mode.name().toLowerCase(),
                        "tokens", r.tokens(), "total", r.total(), "hits", hits));
            }
            case "index_url" -> {
                String url = Json.str(args, "url", "");
                if (crawler == null) yield error("a crawler is not enabled on this server");
                Crawler.CrawlResult res = crawler.fetchAndIndex(engine, url);
                yield text(res.toMap());
            }
            case "index_text" -> {
                engine.add(Json.str(args, "id", ""), Json.str(args, "url", ""),
                        Json.str(args, "title", ""), Json.str(args, "body", ""),
                        Json.str(args, "tags", ""));
                yield text(Map.of("indexed", true, "documents", engine.index().numDocs()));
            }
            case "stats" -> text(engine.stats());
            default -> error("unknown tool: " + name);
        };
    }

    private static Map<String, Object> text(Object payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", Json.write(payload))));
        out.put("isError", false);
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", message == null ? "" : message)));
        out.put("isError", true);
        return out;
    }

    private static String result(Object id, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", payload);
        return Json.write(m);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return Json.write(m);
    }
}
