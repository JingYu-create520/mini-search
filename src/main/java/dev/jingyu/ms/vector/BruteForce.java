package dev.jingyu.ms.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exact k-NN: compare the query against every stored vector.
 *
 * <p>This is the correctness oracle for {@link Hnsw} and the default for small indexes, where the
 * graph would cost more than it saves. At 50k vectors of 120 floats a scan is a few milliseconds on
 * a laptop, so choosing it is not a compromise until the collection grows.
 */
public final class BruteForce implements VectorIndex {

    private final List<Integer> ids = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();
    private final int dim;

    public BruteForce(int dim) { this.dim = dim; }

    @Override public void add(int docId, float[] v) {
        ids.add(docId);
        vectors.add(Encoder.normalize(v.clone()));
    }

    @Override public List<Neighbor> search(float[] q, int k) {
        float[] nq = Encoder.normalize(q.clone());
        List<Neighbor> all = new ArrayList<>(ids.size());
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            float s = 0;
            for (int d = 0; d < dim; d++) s += nq[d] * v[d];
            all.add(new Neighbor(ids.get(i), s));
        }
        all.sort(Comparator.comparingDouble((Neighbor x) -> -x.score()).thenComparingInt(Neighbor::docId));
        return all.size() > k ? new ArrayList<>(all.subList(0, k)) : all;
    }

    @Override public int size() { return ids.size(); }

    @Override public String name() { return "brute"; }

    public List<float[]> rawVectors() { return vectors; }

    public List<Integer> rawIds() { return ids; }
}
