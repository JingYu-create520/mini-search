package dev.jingyu.ms.crawl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One HTTP GET with the three things a crawler actually has to get right: a size cap, a timeout, and
 * a charset decision that does not silently produce mojibake.
 *
 * <p>Charset order is the standard one: BOM, then the {@code Content-Type} header, then an in-document
 * {@code <meta charset>} in the first few kilobytes, then a strict UTF-8 decode attempt, then GBK
 * (the common fallback for Chinese pages that declare nothing). If even the fallback is unavailable
 * the bytes are decoded as UTF-8 with replacement characters, so indexing continues on slightly
 * damaged text instead of failing the whole document.
 */
public final class Fetcher {

    /** Result of a fetch, still as raw bytes: decoding is a separate, testable decision. */
    public record Response(int status, byte[] body, String contentType, String finalUrl, long tookMillis) {
        public boolean ok() { return status >= 200 && status < 300; }

        public String headerCharset() {
            if (contentType == null) return null;
            var m = Pattern.compile("charset\\s*=\\s*\"?([\\w.:-]+)\"?", Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            return m.find() ? m.group(1) : null;
        }

        /** Decode with the documented fallback ladder. */
        public String decode() {
            byte[] b = body;
            String declared = headerCharset();
            String head = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
            if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
                return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
            }
            var meta = Pattern.compile("charset[\\s=]*[\"']?([\\w.:-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            String fromMeta = meta.find() ? meta.group(1) : null;
            for (String candidate : new String[]{declared, fromMeta}) {
                if (candidate == null) continue;
                try {
                    Charset cs = Charset.forName(candidate.toUpperCase(Locale.ROOT));
                    return new String(b, cs);
                } catch (RuntimeException ignored) {
                    // unknown or unsupported label: keep walking the ladder
                }
            }
            if (strictUtf8(b)) return new String(b, StandardCharsets.UTF_8);
            try {
                return new String(b, Charset.forName("GBK"));
            } catch (RuntimeException ignored) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }

        static boolean strictUtf8(byte[] b) {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                d.decode(ByteBuffer.wrap(b));
                return true;
            } catch (CharacterCodingException e) {
                return false;
            }
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final int maxBytes;

    public Fetcher(String userAgent, int maxBytes) {
        this.userAgent = userAgent;
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static Response get(String url, String userAgent, int timeoutMs) throws IOException {
        return new Fetcher(userAgent, 4 << 20).fetch(url, timeoutMs);
    }

    public Response fetch(String url, int timeoutMs) throws IOException {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = res.body();
            if (body.length > maxBytes) body = java.util.Arrays.copyOf(body, maxBytes);
            return new Response(res.statusCode(), body,
                    res.headers().firstValue("content-type").orElse("application/octet-stream"),
                    res.uri().toString(), (long) ((System.nanoTime() - t0) / 1e6));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("bad URL: " + url, e);
        }
    }
}
