package dev.jingyu.ms.index;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The mutable inverted index: term -> documents, per field.
 *
 * <p>Append-only by internal id. Deleting sets a bit in a tombstone bitmap; the postings keep the
 * entry until {@link #compact()} rewrites the index, which is the same trade-off a real engine makes
 * with immutable segments (cheap delete, lazy reclamation). Because ids are handed out in increasing
 * order, every posting list is sorted with no merge step -- that is the whole reason the on-disk
 * format can be delta coded.
 *
 * <p>Scoring-relevant facts this class owns: document frequency per term per field, field length per
 * document, and the average field length. Everything above it (BM25, vectors, fusion) is derived.
 */
public final class InvertedIndex {

    public static final List<String> FIELDS = List.of(Doc.TITLE, Doc.BODY, Doc.TAGS);

    private final ChineseAnalyzer analyzer;
    private final Map<String, PostingList>[] postingsByField;
    private final Map<Integer, Doc> documents = new HashMap<>();
    private final Map<String, Integer> byExternalId = new HashMap<>();
    private final int[][] fieldLen;
    private final long[] fieldTokens;
    private final BitSet deleted = new BitSet();

    private int nextId;
    private int live;

    @SuppressWarnings("unchecked")
    public InvertedIndex(ChineseAnalyzer analyzer) {
        this.analyzer = analyzer;
        int f = FIELDS.size();
        postingsByField = new Map[f];
        for (int i = 0; i < f; i++) postingsByField[i] = new HashMap<>(1 << 12);
        fieldLen = new int[f][];
        for (int i = 0; i < f; i++) fieldLen[i] = new int[1024];
        fieldTokens = new long[f];
    }

    public ChineseAnalyzer analyzer() { return analyzer; }

    // ------------------------------------------------------------------ writing

    public Doc add(String externalId, String url, Map<String, String> fields) {
        return addDoc(new Doc(nextId, externalId, url, fields));
    }

    /** Index one document. Re-adding the same external id updates it (delete + add). */
    public Doc addDoc(Doc doc) {
        int existing = find(doc.externalId());
        if (existing >= 0) delete(existing);
        int id = doc.id();
        growTo(id);
        documents.put(id, doc);
        byExternalId.put(doc.externalId(), id);
        for (int f = 0; f < FIELDS.size(); f++) {
            List<Token> tokens = analyzer.analyze(doc.field(FIELDS.get(f)));
            Map<String, List<Integer>> byTerm = new LinkedHashMap<>();
            int len = 0;
            for (Token t : tokens) {
                byTerm.computeIfAbsent(t.term(), k -> new ArrayList<>(2)).add(t.position());
                len++;
            }
            fieldLen[f] = Arrays.copyOf(fieldLen[f], Math.max(fieldLen[f].length, id + 1));
            fieldLen[f][id] = len;
            fieldTokens[f] += len;
            for (Map.Entry<String, List<Integer>> e : byTerm.entrySet()) {
                List<Integer> ps = e.getValue();
                int[] arr = new int[ps.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = ps.get(i);
                postingsByField[f]
                        .computeIfAbsent(e.getKey(), k -> new PostingList())
                        .add(id, arr);
            }
        }
        nextId = Math.max(nextId, id + 1);
        live++;
        return doc;
    }

    private void growTo(int id) {
        if (id < fieldLen[0].length) return;
        int cap = Math.max(id + 1, fieldLen[0].length * 2);
        for (int f = 0; f < fieldLen.length; f++) fieldLen[f] = Arrays.copyOf(fieldLen[f], cap);
    }

    public boolean delete(int id) {
        Doc d = documents.get(id);
        if (d == null || deleted.get(id)) return false;
        deleted.set(id);
        documents.remove(id);
        byExternalId.remove(d.externalId());
        live--;
        // The average document length is the other half of BM25's normalisation, and it is
        // {@code fieldTokens / live}: subtracting one side without the other inflated the mean by
        // exactly the deleted documents, quietly re-weighting every search afterwards. Same family
        // as the IDF bug the article talks about -- plausible output, wrong ranking.
        for (int f = 0; f < fieldTokens.length; f++) {
            if (id < fieldLen[f].length) {
                fieldTokens[f] -= fieldLen[f][id];
                fieldLen[f][id] = 0;
            }
        }
        return true;
    }

    public boolean deleteByExternalId(String externalId) {
        int id = find(externalId);
        return id >= 0 && delete(id);
    }

    /** Drop tombstoned postings and re-pack ids. Costs a full re-index of the live docs. */
    public void compact() {
        if (deleted.isEmpty() && documents.size() == nextId) return;
        List<Doc> keep = new ArrayList<>(documents.values());
        keep.sort((a, b) -> Integer.compare(a.id(), b.id()));
        InvertedIndex fresh = new InvertedIndex(analyzer);
        for (Doc d : keep) fresh.addDoc(new Doc(fresh.nextId, d.externalId(), d.url(), d.fields()));
        resetFrom(fresh);
    }

    private void resetFrom(InvertedIndex other) {
        for (int f = 0; f < FIELDS.size(); f++) {
            postingsByField[f] = other.postingsByField[f];
            fieldLen[f] = other.fieldLen[f];
            fieldTokens[f] = other.fieldTokens[f];
        }
        documents.clear();
        documents.putAll(other.documents);
        byExternalId.clear();
        byExternalId.putAll(other.byExternalId);
        nextId = other.nextId;
        live = other.live;
        deleted.clear();
    }

    public void sealAll() {
        for (Map<String, PostingList> m : postingsByField) for (PostingList p : m.values()) p.seal();
    }

    public void unsealAll() {
        for (Map<String, PostingList> m : postingsByField) for (PostingList p : m.values()) p.unsealForMerge();
    }

    // ------------------------------------------------------------------ reading

    /** Restore path used by the snapshot loader: register a document without analysing it again. */
    public void registerDoc(Doc doc) {
        growTo(doc.id());
        documents.put(doc.id(), doc);
        byExternalId.put(doc.externalId(), doc.id());
        live++;
        nextId = Math.max(nextId, doc.id() + 1);
    }

    /** Restore path: insert one already-known posting run. {@code positions} must be ascending. */
    public void putPosting(int field, String term, int docId, int[] positions) {
        postingsByField[field].computeIfAbsent(term, k -> new PostingList()).add(docId, positions);
    }

    /** Restore path: record a field length (and the token total it implies). */
    public void setFieldLength(int field, int docId, int length) {
        growTo(docId);
        fieldLen[field][docId] = length;
        fieldTokens[field] += length;
    }

    public void setNextId(int id) { nextId = Math.max(nextId, id); }

    public int find(String externalId) { return byExternalId.getOrDefault(externalId, -1); }

    public Doc doc(int id) { return documents.get(id); }

    public int numDocs() { return live; }

    public int maxDocId() { return nextId; }

    public PostingList postings(int field, String term) { return postingsByField[field].get(term); }

    public PostingList postings(String field, String term) { return postings(FIELDS.indexOf(field), term); }

    /**
     * Number of distinct live documents containing the term, across all fields.
     *
     * <p>This is the denominator of IDF, so getting it wrong silently flattens the whole ranking:
     * counting fields instead of documents makes every term look equally common. The posting lists
     * are sorted by id, so the union across fields is a merge, not a set allocation.
     */
    public int docFreq(String term) {
        int fields = 0;
        PostingList[] lists = new PostingList[FIELDS.size()];
        for (int f = 0; f < FIELDS.size(); f++) {
            PostingList p = postingsByField[f].get(term);
            if (p != null && p.size() > 0) lists[fields++] = p;
        }
        if (fields == 0) return 0;
        if (fields == 1) return liveSize(lists[0]);
        int[] cursors = new int[fields];
        int total = 0, last = -1;
        while (true) {
            int min = Integer.MAX_VALUE;
            for (int i = 0; i < fields; i++) {
                while (cursors[i] < lists[i].size() && lists[i].doc(cursors[i]) == last) cursors[i]++;
                if (cursors[i] < lists[i].size()) min = Math.min(min, lists[i].doc(cursors[i]));
            }
            if (min == Integer.MAX_VALUE) break;
            total++;
            last = min;
            for (int i = 0; i < fields; i++) {
                while (cursors[i] < lists[i].size() && lists[i].doc(cursors[i]) == min) cursors[i]++;
            }
        }
        return total;
    }

    private int liveSize(PostingList p) {
        if (deleted.isEmpty()) return p.size();
        int n = 0;
        for (int i = 0; i < p.size(); i++) if (!deleted.get(p.doc(i))) n++;
        return n;
    }

    /** Raw document frequency across fields, ignoring tombstones; used by the IDF tables. */
    public Map<String, Integer> documentFrequencyMap() {
        Map<String, Integer> out = new HashMap<>();
        for (int f = 0; f < FIELDS.size(); f++) {
            for (Map.Entry<String, PostingList> e : postingsByField[f].entrySet()) {
                out.merge(e.getKey(), e.getValue().size(), Integer::sum);
            }
        }
        return out;
    }

    public int fieldLength(int field, int docId) {
        return docId < fieldLen[field].length ? fieldLen[field][docId] : 0;
    }

    public double avgFieldLength(int field) {
        return live == 0 ? 1 : (double) fieldTokens[field] / live;
    }

    public long totalTokens(int field) { return fieldTokens[field]; }

    public int vocabularySize() {
        TreeSet<String> all = new TreeSet<>();
        for (Map<String, PostingList> m : postingsByField) all.addAll(m.keySet());
        return all.size();
    }

    /** All terms indexed in any field, sorted by document frequency descending. */
    public List<String> vocabularyByDf() {
        Map<String, Integer> df = new HashMap<>();
        for (Map<String, PostingList> m : postingsByField)
            for (String t : m.keySet()) df.merge(t, m.get(t).size(), Integer::sum);
        List<String> out = new ArrayList<>(df.keySet());
        out.sort((a, b) -> Integer.compare(df.get(b), df.get(a)));
        return out;
    }

    public Map<String, Integer> documentFrequencies() {
        Map<String, Integer> df = new HashMap<>();
        for (Map<String, PostingList> m : postingsByField)
            for (Map.Entry<String, PostingList> e : m.entrySet())
                df.merge(e.getKey(), e.getValue().size(), Math::max);
        return df;
    }

    public Iterable<Map.Entry<String, PostingList>> postings(int field) { return postingsByField[field].entrySet(); }

    public Map<String, PostingList> fieldPostings(int field) { return postingsByField[field]; }

    public List<Doc> allDocs() { return new ArrayList<>(documents.values()); }

    public boolean isDeleted(int id) { return deleted.get(id); }

    /** Live documents in id order, used by the brute-force scorer and the eval harness. */
    public int[] liveDocIds() {
        int[] out = new int[live];
        int k = 0;
        for (int id : new TreeSet<>(documents.keySet())) out[k++] = id;
        return out;
    }

    @Override public String toString() {
        int terms = 0;
        for (Map<String, PostingList> m : postingsByField) terms += m.size();
        return "InvertedIndex{docs=" + live + ", terms=" + terms + ", nextId=" + nextId + "}";
    }

    // ------------------------------------------------------------------ phrase

    /**
     * Documents where the given terms occur consecutively and in order in one field.
     * Positions make this a merge of sorted lists rather than a scan of the text.
     */
    public List<Integer> phrase(int field, List<String> terms) {
        if (terms.isEmpty()) return List.of();
        List<int[]> runs = new ArrayList<>();          // [docId, positions...] flattened later
        List<PostingList> lists = new ArrayList<>();
        for (String t : terms) {
            PostingList p = postingsByField[field].get(t);
            if (p == null || p.size() == 0) return List.of();
            lists.add(p);
        }
        Iterator<Integer> it = intersectDocs(lists);
        List<Integer> hits = new ArrayList<>();
        while (it.hasNext()) {
            int docId = it.next();
            if (deleted.get(docId)) continue;
            int idx = 0;
            List<int[]> posSets = new ArrayList<>();
            for (PostingList p : lists) posSets.add(p.positions(indexOf(p, docId, idx++)));
            if (consecutive(posSets)) hits.add(docId);
        }
        return hits;
    }

    private static boolean consecutive(List<int[]> posSets) {
        for (int start : posSets.get(0)) {
            boolean ok = true;
            for (int i = 1; i < posSets.size(); i++) {
                if (Arrays.binarySearch(posSets.get(i), start + i) < 0) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    private static int indexOf(PostingList p, int docId, int ignored) {
        int n = p.size();
        for (int i = 0; i < n; i++) if (p.doc(i) == docId) return i;
        return -1;
    }

    private static Iterator<Integer> intersectDocs(List<PostingList> lists) {
        List<Integer> out = new ArrayList<>();
        PostingList first = lists.get(0);
        outer:
        for (int i = 0; i < first.size(); i++) {
            int d = first.doc(i);
            for (int k = 1; k < lists.size(); k++) {
                PostingList p = lists.get(k);
                int j = indexOf(p, d, 0);
                if (j < 0) continue outer;
            }
            out.add(d);
        }
        return out.iterator();
    }
}
