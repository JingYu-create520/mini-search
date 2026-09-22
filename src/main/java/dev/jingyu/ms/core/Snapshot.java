package dev.jingyu.ms.core;

import dev.jingyu.ms.index.Doc;
import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.index.PostingList;
import dev.jingyu.ms.util.Log;
import dev.jingyu.ms.util.VarInt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * On-disk snapshot of everything the engine holds in memory: documents, postings, field lengths,
 * mined dictionary, document vectors and the trained word vectors.
 *
 * <p>Format, deliberately boring: a header (magic, version, corpus fingerprint) followed by
 * length-prefixed sections, each with its own CRC32. A torn write therefore fails one section's
 * checksum and the engine rebuilds from source instead of serving half a index. Sections are
 * independent so an older file still loads if it lacks a newer section.
 *
 * <p>This is a single-segment snapshot, not the merged multi-segment layout described in the design
 * doc: merging earns nothing at this scale, so it is parked in v2.
 */
public final class Snapshot {

    public static final String MAGIC = "MSSN";
    public static final int VERSION = 2;

    private final long fingerprint;
    private final Map<String, byte[]> sections = new LinkedHashMap<>();

    public Snapshot(long fingerprint) { this.fingerprint = fingerprint; }

    public long fingerprint() { return fingerprint; }

    public boolean has(String name) { return sections.containsKey(name); }

    public byte[] raw(String name) { return sections.get(name); }

    // ------------------------------------------------------------------ write

    public static Snapshot of(InvertedIndex index, List<String> minedDict, List<String> customDict,
                              float[][] docVectors, int[] vectorDocIds, long fingerprint) throws IOException {
        Snapshot s = new Snapshot(fingerprint);
        s.put("docs", writeDocs(index));
        s.put("postings", writePostings(index));
        s.put("lens", writeLens(index));
        s.put("dict", writeDict(minedDict));
        // Its own section, so a snapshot written before this existed still loads: absent means empty.
        s.put("udict", writeDict(customDict));
        s.put("vectors", writeVectors(docVectors, vectorDocIds, index.maxDocId()));
        return s;
    }

    private void put(String name, byte[] payload) { sections.put(name, payload); }

    public void putModel(byte[] payload) { sections.put("w2v", payload); }

    public byte[] model() { return sections.get("w2v"); }

    /**
     * One labelled string, kept as a section so a file written before it existed simply lacks it --
     * which is the same tolerance the format already has for {@code w2v}.
     */
    public void putString(String name, String value) {
        sections.put(name, value.getBytes(StandardCharsets.UTF_8));
    }

    public String string(String name) {
        byte[] b = sections.get(name);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    public void writeTo(Path file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        DataOutputStream head = new DataOutputStream(bos);
        head.writeUTF(MAGIC);
        head.writeInt(VERSION);
        head.writeLong(fingerprint);
        head.writeInt(sections.size());
        for (Map.Entry<String, byte[]> e : sections.entrySet()) {
            head.writeUTF(e.getKey());
            head.writeInt(e.getValue().length);
            head.write(e.getValue());
            CRC32 crc = new CRC32();
            crc.update(e.getValue());
            head.writeInt((int) crc.getValue());
        }
        head.flush();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        Files.write(tmp, bos.toByteArray());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Log.info("snapshot written: %s (%.1f MB)", file.getFileName(), bos.size() / 1e6);
    }

    public static Snapshot read(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all));
        String magic = in.readUTF();
        if (!magic.equals(MAGIC)) throw new IOException("not a mini-search snapshot: " + magic);
        int version = in.readInt();
        if (version != VERSION) throw new IOException("unsupported snapshot version " + version);
        long fp = in.readLong();
        int n = in.readInt();
        Snapshot s = new Snapshot(fp);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            int len = in.readInt();
            byte[] payload = new byte[len];
            in.readFully(payload);
            int crc = in.readInt();
            CRC32 check = new CRC32();
            check.update(payload);
            if ((int) check.getValue() != crc) throw new IOException("checksum mismatch in section " + name);
            s.sections.put(name, payload);
        }
        return s;
    }

    // ------------------------------------------------------------------ payload codecs

    /**
     * Length-prefixed UTF-8, because {@code DataOutput.writeUTF} caps a string at 65535 bytes of
     * modified UTF-8 -- roughly 21k Chinese characters -- and a single crawled article goes past that
     * without anyone trying. Hitting the cap threw from {@code save()} *after* the documents were
     * already indexed, so the run looked like it had worked and had persisted nothing. It also stores
     * real UTF-8 rather than CESU-8, so supplementary characters (emoji) survive the round trip.
     */
    static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    static String readStr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) throw new IOException("negative string length " + n);
        byte[] b = in.readNBytes(n);
        if (b.length != n) throw new IOException("truncated string field: wanted " + n + " bytes, got " + b.length);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] writeDocs(InvertedIndex index) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        List<Doc> docs = index.allDocs();
        out.writeInt(docs.size());
        for (Doc d : docs) {
            out.writeInt(d.id());
            writeStr(out, d.externalId());
            writeStr(out, d.url());
            writeStr(out, d.field(Doc.TITLE));
            writeStr(out, d.field(Doc.BODY));
            writeStr(out, d.field(Doc.TAGS));
        }
        out.flush();
        return bos.toByteArray();
    }

    public static void readDocs(byte[] payload, List<Object[]> into) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            into.add(new Object[]{in.readInt(), readStr(in), readStr(in), readStr(in), readStr(in), readStr(in)});
        }
    }

    private static byte[] writePostings(InvertedIndex index) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(InvertedIndex.FIELDS.size());
        for (int f = 0; f < InvertedIndex.FIELDS.size(); f++) {
            var entries = new ArrayList<>(index.fieldPostings(f).entrySet());
            entries.sort(Map.Entry.comparingByKey());
            out.writeInt(entries.size());
            for (var e : entries) {
                PostingList p = e.getValue();
                writeStr(out, e.getKey());
                VarInt.write(out, p.size());
                int prev = 0;
                for (int i = 0; i < p.size(); i++) { VarInt.write(out, p.doc(i) - prev); prev = p.doc(i); }
                for (int i = 0; i < p.size(); i++) {
                    int[] pos = p.positions(i);
                    VarInt.write(out, pos.length);
                    int pp = 0;
                    for (int x : pos) {
                        if (pp == 0) { VarInt.write(out, x); pp = x; continue; }
                        VarInt.write(out, x - pp);
                        pp = x;
                    }
                }
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    /** Term -> per field list of (docId, positions). Returned map is field-indexed. */
    public static List<Map<String, List<Object[]>>> readPostings(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int nf = in.readInt();
        List<Map<String, List<Object[]>>> out = new ArrayList<>();
        for (int f = 0; f < nf; f++) {
            Map<String, List<Object[]>> byTerm = new LinkedHashMap<>();
            int nt = in.readInt();
            for (int t = 0; t < nt; t++) {
                String term = readStr(in);
                int n = VarInt.read(in);
                int[] docs = new int[n];
                int prev = 0;
                for (int i = 0; i < n; i++) { prev += VarInt.read(in); docs[i] = prev; }
                List<Object[]> runs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int np = VarInt.read(in);
                    int[] pos = new int[np];
                    int pp = 0;
                    for (int k = 0; k < np; k++) {
                        int d = VarInt.read(in);
                        pp = k == 0 ? d : pp + d;
                        pos[k] = pp;
                    }
                    runs.add(new Object[]{docs[i], pos});
                }
                byTerm.put(term, runs);
            }
            out.add(byTerm);
        }
        return out;
    }

    private static byte[] writeLens(InvertedIndex index) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int[] ids = index.liveDocIds();
        out.writeInt(InvertedIndex.FIELDS.size());
        out.writeInt(ids.length);
        for (int f = 0; f < InvertedIndex.FIELDS.size(); f++) {
            int prev = 0;
            for (int id : ids) {
                VarInt.write(out, id - prev);
                VarInt.write(out, index.fieldLength(f, id));
                prev = id;
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    /** Per field: pairs of {docId, fieldLength}. */
    public static int[][] readLens(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int nf = in.readInt();
        int n = in.readInt();
        int[][] out = new int[nf][];
        for (int f = 0; f < nf; f++) {
            int[] flat = new int[n * 2];
            int prev = 0;
            for (int i = 0; i < n; i++) {
                prev += VarInt.read(in);
                flat[i * 2] = prev;
                flat[i * 2 + 1] = VarInt.read(in);
            }
            out[f] = flat;
        }
        return out;
    }

    private static byte[] writeDict(List<String> words) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(words.size());
        for (String w : words) writeStr(out, w);
        out.flush();
        return bos.toByteArray();
    }

    public static List<String> readDict(byte[] payload) throws IOException {
        if (payload == null) return List.of();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int n = in.readInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(readStr(in));
        return out;
    }

    private static byte[] writeVectors(float[][] vecs, int[] ids, int maxDocId) throws IOException {
        if (vecs == null || vecs.length == 0) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(vecs.length);
        out.writeInt(vecs[0].length);
        VarInt.write(out, maxDocId);
        for (int i = 0; i < vecs.length; i++) {
            VarInt.write(out, ids[i]);
            for (float v : vecs[i]) out.writeFloat(v);
        }
        out.flush();
        return bos.toByteArray();
    }

    public record StoredVectors(int[] docIds, float[][] vectors) {}

    public static StoredVectors readVectors(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return new StoredVectors(new int[0], new float[0][]);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int n = in.readInt();
        int dim = in.readInt();
        VarInt.read(in);
        int[] ids = new int[n];
        float[][] v = new float[n][dim];
        for (int i = 0; i < n; i++) {
            ids[i] = VarInt.read(in);
            for (int d = 0; d < dim; d++) v[i][d] = in.readFloat();
        }
        return new StoredVectors(ids, v);
    }

    /** Bytes of a UTF-8 string, for tests and ad-hoc sections. */
    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
