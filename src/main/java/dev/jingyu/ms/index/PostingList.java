package dev.jingyu.ms.index;

import java.util.Arrays;

/**
 * The list of documents containing one term, with positions.
 *
 * <p>Document ids arrive in increasing order (an append-only index never reuses an id), so the
 * storage is already delta-sorted: {@code docs[i] - docs[i-1]} are small positive ints, which is
 * what makes {@link dev.jingyu.ms.index.SnapshotStore}'s variable-byte coding worth anything.
 * Positions live in one shared pool; {@code posStart[i]} indexes doc i's run of positions.
 */
public final class PostingList {

    private int[] docs = new int[8];
    private int[] posStart = new int[8];
    private int size;

    private int[] pool = new int[32];
    private int poolSize;

    private int lastDoc = -1;
    private boolean sealed;

    public int size() { return size; }

    public void add(int docId, int[] positions) {
        if (sealed) throw new IllegalStateException("posting list sealed");
        if (docId <= lastDoc) {
            if (docId == lastDoc) appendPositions(positions);   // same doc, later field chunk
            return;                                             // smaller id: ignore, ids are monotone
        }
        ensure(size + 1);
        docs[size] = docId;
        posStart[size] = poolSize;
        lastDoc = docId;
        size++;
        appendPositions(positions);
    }

    private void appendPositions(int[] positions) {
        if (positions.length == 0) return;
        if (poolSize + positions.length > pool.length)
            pool = Arrays.copyOf(pool, Math.max(pool.length << 1, poolSize + positions.length));
        int base = positions[0];
        pool[poolSize++] = base;                              // absolute first
        for (int i = 1; i < positions.length; i++)           // deltas after that
            pool[poolSize++] = positions[i] - positions[i - 1];
    }

    private void ensure(int n) {
        if (n <= docs.length) return;
        docs = Arrays.copyOf(docs, Math.max(n, docs.length << 1));
        posStart = Arrays.copyOf(posStart, docs.length);
    }

    public int doc(int i) { return docs[i]; }

    /** Term frequency inside {@code docs[i]} without materialising the position array. */
    public int freq(int i) {
        return (i + 1 < size ? posStart[i + 1] : poolSize) - posStart[i];
    }

    /** Absolute positions of the term in document {@code docs[i]}. */
    public int[] positions(int i) {
        int start = posStart[i];
        int end = (i + 1 < size) ? posStart[i + 1] : poolSize;
        int[] out = new int[end - start];
        if (out.length == 0) return out;
        int prev = pool[start];
        out[0] = prev;
        for (int k = 1; k < out.length; k++) { prev += pool[start + k]; out[k] = prev; }
        return out;
    }

    public void seal() {
        sealed = true;
        docs = Arrays.copyOf(docs, size);
        posStart = Arrays.copyOf(posStart, size);
    }

    public void unsealForMerge() { sealed = false; }

    int[] rawDocs() { return docs; }
    int rawSize() { return size; }
}
