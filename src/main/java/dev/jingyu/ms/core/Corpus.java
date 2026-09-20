package dev.jingyu.ms.core;

import dev.jingyu.ms.util.Json;
import dev.jingyu.ms.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Reads the demo corpus out of the jar, or a directory of JSONL files from disk. */
public final class Corpus {

    /** One source document before analysis. {@code id} is stable and user-visible. */
    public record RawDoc(String id, String url, String title, String body, String tags) {}

    private Corpus() {}

    public static List<RawDoc> loadDemo() {
        List<RawDoc> out = new ArrayList<>();
        try {
            String manifest = readClasspath("corpus/manifest.txt");
            for (String name : manifest.split("\\R")) {
                String f = name.trim();
                if (f.isEmpty() || f.startsWith("#")) continue;
                out.addAll(parseJsonl(readClasspath("corpus/" + f), f));
            }
        } catch (IOException e) {
            Log.warn("bundled corpus unavailable (%s); index something first", e.getMessage());
        }
        return out;
    }

    public static List<RawDoc> loadDir(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().forEach(files::add);
        }
        List<RawDoc> out = new ArrayList<>();
        for (Path p : files) out.addAll(parseJsonl(Files.readString(p, StandardCharsets.UTF_8), p.getFileName().toString()));
        return out;
    }

    public static List<RawDoc> parseJsonl(String text, String source) {
        List<RawDoc> out = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\\R")) {
            lineNo++;
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            try {
                Map<String, Object> o = Json.parseObject(line);
                String id = Json.str(o, "id", source + "-" + lineNo);
                String title = Json.str(o, "title", "");
                String body = Json.str(o, "body", "");
                String url = Json.str(o, "url", "");
                StringBuilder tags = new StringBuilder();
                Object t = o.get("tags");
                if (t instanceof List<?> l) {
                    for (Object x : l) tags.append(tags.length() == 0 ? "" : ",").append(x);
                } else if (t != null) tags.append(t);
                if (body.isBlank() && title.isBlank()) continue;
                out.add(new RawDoc(id, url, title, body, tags.toString()));
            } catch (RuntimeException e) {
                Log.warn("skipping %s:%d -- %s", source, lineNo, e.getMessage());
            }
        }
        return out;
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = Corpus.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing classpath resource: " + resource);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    /** Content fingerprint, so a stale snapshot on disk is detected instead of trusted. */
    public static long fingerprint(List<RawDoc> docs) {
        CRC32 crc = new CRC32();
        docs.stream().sorted(Comparator.comparing(RawDoc::id)).forEach(d -> {
            crc.update(d.id().getBytes(StandardCharsets.UTF_8));
            crc.update(d.title().getBytes(StandardCharsets.UTF_8));
            crc.update(d.body().getBytes(StandardCharsets.UTF_8));
        });
        return crc.getValue();
    }
}
