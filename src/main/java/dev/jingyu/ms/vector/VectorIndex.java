package dev.jingyu.ms.vector;

import java.util.List;

/** Nearest-neighbour search over the document vectors built by an {@link Encoder}. */
public interface VectorIndex {

    record Neighbor(int docId, float score) {}

    void add(int docId, float[] vector);

    /** Up to {@code k} neighbours, best first. */
    List<Neighbor> search(float[] query, int k);

    int size();

    String name();

    /** Vectors must be unit length for cosine == dot product; callers normalise on the way in. */
    static float[] checked(float[] v) {
        return Encoder.normalize(v.clone());
    }
}
