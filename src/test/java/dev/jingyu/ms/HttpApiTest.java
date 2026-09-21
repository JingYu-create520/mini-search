package dev.jingyu.ms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jingyu.ms.api.HttpApi;
import dev.jingyu.ms.core.Engine;
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
    void unknownEndpointsAndMissingParametersAreRejectedNotThrown() throws Exception {        Engine engine = Engine.empty(new Engine.Options().mining(false).vectors(false));
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
}
