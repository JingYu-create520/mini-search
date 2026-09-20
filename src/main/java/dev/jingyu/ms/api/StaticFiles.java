package dev.jingyu.ms.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the single-page UI out of the jar, so {@code java -jar mini-search.jar} is the whole
 * product. Classpath first (packaged build), then {@code web/dist} on disk (a live Vite build during
 * development). Path traversal is refused by resolving and re-checking the prefix.
 */
public final class StaticFiles {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".js", "text/javascript; charset=utf-8"),
            Map.entry(".mjs", "text/javascript; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".png", "image/png"),
            Map.entry(".ico", "image/x-icon"),
            Map.entry(".woff2", "font/woff2"),
            Map.entry(".txt", "text/plain; charset=utf-8"));

    private StaticFiles() {}

    public static byte[] read(String urlPath) {
        String rel = normalise(urlPath);
        byte[] direct = load(rel);
        if (direct != null) return direct;
        return load("static/index.html");     // single-page fallback
    }

    public static String contentType(String urlPath) {
        String rel = normalise(urlPath);
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot);
        return TYPES.getOrDefault(ext, "text/plain; charset=utf-8");
    }

    private static String normalise(String urlPath) {
        String p = URLDecoder.decode(urlPath, StandardCharsets.UTF_8);
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) return "";
        return "static/" + p;
    }

    private static byte[] load(String resource) {
        if (resource.isEmpty()) return null;
        try (InputStream in = StaticFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
            // fall through to the filesystem
        }
        try {
            Path p = Path.of("web", "dist").resolve(resource.substring("static/".length()));
            if (Files.isRegularFile(p)) return Files.readAllBytes(p);
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
}
