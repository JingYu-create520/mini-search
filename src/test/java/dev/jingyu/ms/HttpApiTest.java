package dev.jingyu.ms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jingyu.ms.api.HttpApi;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round trips over the real HTTP server. These exist because the {@code /api/*} handlers had no
 * coverage at all: everything was tested through the engine, which is exactly how a cached
 * {@code Searcher} in {@link HttpApi} survived — indexing through {@code POST /api/index}
 * republishes the engine's searcher, and the server kept answering from the one it captured in
 * its constructor, so anything the later publication switched on (vectors, thesaurus) was
 * invisible to the only client that matters.
 */
@DisplayName("HTTP API against a live server")
class HttpApiTest {

    private static String request(int port, String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void aDocumentIndexedOverHttpIsSearchableOverHttp() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            assertTrue(port > 0, "an ephemeral port should be reported back");

            String empty = request(port, "GET", "/api/search?q=" + URLEncoder.encode("纹路", StandardCharsets.UTF_8), null);
            assertTrue(empty.contains("\"total\":0") || empty.contains("\"hits\":[]"), "fresh engine finds nothing: " + empty);

            String added = request(port, "POST", "/api/index",
                    "{\"id\":\"http-1\",\"title\":\"鸢尾花观察笔记\",\"body\":\"今天记录了花瓣上的纹路\"}");
            assertTrue(added.contains("\"indexed\":true"), "index response: " + added);

            String q = URLEncoder.encode("纹路", StandardCharsets.UTF_8);
            for (String mode : new String[] {"bm25", "hybrid", "semantic"}) {
                String hit = request(port, "GET", "/api/search?q=" + q + "&mode=" + mode, null);
                assertTrue(hit.contains("http-1"), mode + " should see the document added after start: " + hit);
            }

            String stats = request(port, "GET", "/api/stats", null);
            assertTrue(stats.contains("\"documents\":1"), "stats after one insert: " + stats);

            String deleted = request(port, "DELETE", "/api/index?id=http-1", null);
            assertTrue(deleted.contains("\"deleted\":true"), "delete response: " + deleted);
            assertEquals(0, engine.index().numDocs(), "the delete reached the engine");
        } finally {
            api.stop();
        }
    }

    @Test
    void theDefaultBindIsLoopbackNotEveryInterface() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            // `new InetSocketAddress(port)` was the whole binding: wildcard, with unauthenticated
            // write endpoints behind it. A local-first tool should have to opt into the network.
            assertTrue(api.boundAddress().getAddress().isLoopbackAddress(),
                    "default bind must be loopback, was " + api.boundAddress());
        } finally {
            api.stop();
        }
    }

    @Test
    void anExplicitHostStillBindsTheWildcardTheContainerNeeds() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, "0.0.0.0", 0);
        api.start();
        try {
            assertTrue(api.boundAddress().getAddress().isAnyLocalAddress(),
                    "0.0.0.0 must still be honoured: " + api.boundAddress());
            assertTrue(api.boundPort() > 0);
        } finally {
            api.stop();
        }
    }

    @Test
    void unknownEndpointsAndMissingParametersAreRejectedNotThrown() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            assertTrue(request(port, "GET", "/api/nope", null).contains("unknown endpoint"));
            assertTrue(request(port, "GET", "/api/search?q=", null).contains("q is required"));
            assertTrue(request(port, "POST", "/api/index", "{\"id\":\"\"}").contains("id is required"));
        } finally {
            api.stop();
        }
    }

    @Test
    @DisplayName("越界分页参数不能把服务打成 500")
    void nonsensePagingIsClampedNotFatal() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            request(port, "POST", "/api/index", "{\"id\":\"p1\",\"title\":\"分页边界\",\"body\":\"从这里开始\"}");
            String q = URLEncoder.encode("分页", StandardCharsets.UTF_8);
            for (String paging : new String[] {"from=-5", "topK=-1", "topK=0", "topK=99999", "from=99999",
                    "topK=abc", "from=abc"}) {
                String r = request(port, "GET", "/api/search?q=" + q + "&" + paging, null);
                assertTrue(r.contains("\"hits\""), paging + " should answer normally: " + r);
                assertTrue(!r.contains("\"error\""), paging + " must not become a server error: " + r);
            }
        } finally {
            api.stop();
        }
    }

    @Test
    @DisplayName("坏输入也要有回答，不能把连接默默掐掉")
    void badRequestsAreAnsweredNotDropped() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            String[] bad = {
                    "{\"id\":\"x\",\"body\":",                                   // truncated
                    "not json at all",                                           // not json
                    "",                                                          // empty body
                    "{\"id\":\"b\",\"body\":" + "[".repeat(5000) + "]".repeat(5000) + "}",
            };
            for (String body : bad) {
                String r = request(port, "POST", "/api/index", body);
                assertTrue(r.contains("\"error\""), () -> "no answer at all for ["
                        + body.substring(0, Math.min(24, body.length())) + "...]: " + r);
                assertTrue(r.length() < 600, () -> "the error must not echo the payload back: " + r.length());
                assertEquals(0, engine.numDocs(), "a refused body stores nothing");
            }
            // And the server is still a server afterwards.
            String ok = request(port, "POST", "/api/index", "{\"id\":\"s1\",\"title\":\"坏输入之后\",\"body\":\"仍然可写\"}");
            assertTrue(ok.contains("\"indexed\":true"), "normal writes still work: " + ok);
        } finally {
            api.stop();
        }
    }

    @Test
    @DisplayName("写接口有请求体上限")
    void oversizedWriteBodiesAreRefused() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            // One byte past 8 MiB is enough to trip it; a kilobyte of slack keeps the test honest if
            // the wrapper's own bytes are counted differently later.
            String huge = "{\"id\":\"big\",\"title\":\"t\",\"body\":\"" + "a".repeat((8 << 20) + 1024) + "\"}";
            String r = request(port, "POST", "/api/index", huge);
            assertTrue(r.contains("body larger than 8 MiB"), "expected the cap to answer, got: " + r);
            assertEquals(0, engine.numDocs(), "a refused body stores nothing");

            String ok = request(port, "POST", "/api/index", "{\"id\":\"s1\",\"title\":\"小文档\",\"body\":\"正文\"}");
            assertTrue(ok.contains("\"indexed\":true"), "the cap must not break normal writes: " + ok);
        } finally {
            api.stop();
        }
    }

    @Test
    @DisplayName("/api/doc 两种寻址都能取回原文，取不到就是 404")
    void docLookupsReadThroughTheGuard() throws Exception {
        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
        HttpApi api = new HttpApi(engine, 0);
        api.start();
        try {
            int port = api.boundPort();
            request(port, "POST", "/api/index",
                    "{\"id\":\"doc-1\",\"title\":\"海带为什么冬天不冻\",\"body\":\"比热容让沿海城市冬天温和\"}");

            String byId = request(port, "GET", "/api/doc?id=doc-1", null);
            assertTrue(byId.contains("海带为什么冬天不冻"), "by external id: " + byId);
            assertTrue(byId.contains("比热容"), "the body comes back intact: " + byId);

            int internal = (int) Math.round((Double) Json.obj(Json.parse(byId)).get("docId"));
            String byDocId = request(port, "GET", "/api/doc?docId=" + internal, null);
            assertTrue(byDocId.contains("doc-1"), "by internal id " + internal + ": " + byDocId);

            assertTrue(request(port, "GET", "/api/doc?id=never-indexed", null).contains("no such document"));
        } finally {
            api.stop();
        }
    }
}
