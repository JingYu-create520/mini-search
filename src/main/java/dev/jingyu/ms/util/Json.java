package dev.jingyu.ms.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ~200 line JSON reader/writer, so the engine has zero runtime dependencies.
 *
 * <p>Mirrors the shape you would get from any library: objects are
 * {@code Map<String,Object>}, arrays are {@code List<Object>}, numbers are {@code Double}
 * (or {@code Long} when written by {@link #write}), strings, booleans and null.
 */
public final class Json {

    // ------------------------------------------------------------------ parsing

    /**
     * How deep the recursive descent will go.
     *
     * <p>The parser calls {@code value() -> array() -> value()} once per nesting level, so without a
     * ceiling a 120 KB body of sixty thousand open brackets ends the parsing thread with a
     * {@link StackOverflowError}. That is an {@link Error} and not a {@link RuntimeException}, so it
     * slips past every path written to answer a bad request politely: the exchange dies without ever
     * sending a status line. Before this cap that was reproducible with one curl.
     *
     * <p>Ninety-six is orders of magnitude past anything a document, a tool argument or a corpus line
     * legitimately contains here -- the engine's own payloads top out around four levels.
     */
    public static final int MAX_DEPTH = 96;

    private final String src;
    private int at;
    private int depth;

    private Json(String src) { this.src = src; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.at < text.length()) throw err(text, p.at, "trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (at >= src.length()) throw err(src, at, "unexpected end of input");
        if (++depth > MAX_DEPTH) {
            depth--;
            throw err(src, at, "nested more than " + MAX_DEPTH + " levels deep");
        }
        try {
            char c = src.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        } finally {
            depth--;
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++; // '{'
        skipWs();
        if (peek() == '}') { at++; return out; }
        while (true) {
            skipWs();
            String k = string();
            skipWs();
            if (peek() != ':') throw err(src, at, "expected ':'");
            at++;
            skipWs();
            out.put(k, value());
            skipWs();
            char c = peek();
            at++;
            if (c == '}') return out;
            if (c != ',') throw err(src, at - 1, "expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        at++; // '['
        skipWs();
        if (peek() == ']') { at++; return out; }
        while (true) {
            skipWs();
            out.add(value());
            skipWs();
            char c = peek();
            at++;
            if (c == ']') return out;
            if (c != ',') throw err(src, at - 1, "expected ',' or ']'");
        }
    }

    private String string() {
        if (peek() != '"') throw err(src, at, "expected '\"'");
        at++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at >= src.length()) throw err(src, at, "unterminated string");
            char c = src.charAt(at++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = src.charAt(at++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (at + 4 > src.length()) throw err(src, at, "bad \\u escape");
                    sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw err(src, at - 1, "bad escape \\" + e);
            }
        }
    }

    private Boolean bool() {
        if (src.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (src.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        throw err(src, at, "expected a boolean");
    }

    private Object nul() {
        if (src.startsWith("null", at)) { at += 4; return null; }
        throw err(src, at, "expected null");
    }

    private Double number() {
        int start = at;
        while (at < src.length() && "-+.eE0123456789".indexOf(src.charAt(at)) >= 0) at++;
        if (start == at) throw err(src, at, "expected a value");
        try {
            return Double.parseDouble(src.substring(start, at));
        } catch (NumberFormatException e) {
            throw err(src, start, "bad number");
        }
    }

    private char peek() { return at < src.length() ? src.charAt(at) : '\0'; }

    private void skipWs() {
        while (at < src.length() && Character.isWhitespace(src.charAt(at))) at++;
    }

    private static IllegalArgumentException err(String s, int i, String msg) {
        int from = Math.max(0, i - 20), to = Math.min(s.length(), i + 20);
        return new IllegalArgumentException("JSON " + msg + " at offset " + i + ": ..." + s, null);
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) quote(s, sb);
        else if (v instanceof Boolean b) sb.append(b);
        else if (v instanceof Double d) {
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append((long) (double) d);
            else sb.append(d);
        } else if (v instanceof Number n) sb.append(n);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(l.get(i), sb);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            writeValue(List.of(arr), sb);
        } else if (v instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) sb.append(i > 0 ? "," : "").append(arr[i]);
            sb.append(']');
        } else if (v instanceof double[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) sb.append(i > 0 ? "," : "").append(arr[i]);
            sb.append(']');
        } else quote(String.valueOf(v), sb);
    }

    public static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // Keep CJK and other printable text verbatim; the HTTP layer serves UTF-8.
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ typed access

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) { return v instanceof Map ? (Map<String, Object>) v : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) { return v instanceof List ? (List<Object>) v : List.of(); }

    public static String str(Map<String, Object> o, String k, String dflt) {
        Object v = o.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    public static int integer(Map<String, Object> o, String k, int dflt) {
        Object v = o.get(k);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    public static double dbl(Map<String, Object> o, String k, double dflt) {
        Object v = o.get(k);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }
}
