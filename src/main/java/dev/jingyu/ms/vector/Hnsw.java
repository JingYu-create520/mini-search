package dev.jingyu.ms.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Hierarchical Navigable Small World graphs: approximate k-NN by greedy descent through a
 * multi-layer proximity graph.
 *
 * <p>Each node gets a random maximum layer (geometric, controlled by {@code mL}). Layer {@code l}
 * holds a subset of the nodes, so the top layers are sparse long-range shortcuts and layer 0 is the
 * full graph. A query enters at the top and greedily walks to the locally best neighbour, which is a
 * coarse approach; the same walk repeats on each lower layer with the previous result as the seed,
 * refining it. On layer 0 the walk keeps an {@code ef} sized candidate queue instead of a single
 * pointer -- that width is the recall/speed dial.
 *
 * <p>Insertion does the same walk with {@code efConstruction} and then links the new node to its
 * {@code M} nearest discovered nodes, reciprocally, pruning any list that overflows to its closest
 * entries. Pruning by pure distance is simpler than the paper's relative-neighbour heuristic; it
 * costs a little recall on clustered data and buys readability. Measured recall against
 * {@link BruteForce} is asserted in {@code HnswTest} so the loss is a number, not a worry.
 *
 * <p>Not thread-safe for concurrent inserts; reads after a publish are safe because the graph is
 * only appended to.
 */
public final class Hnsw implements VectorIndex {

    private static final class Cand {
        final int node;
        final float score;
        Cand(int node, float score) { this.node = node; this.score = score; }
    }

    private final int dim;
    private final int m;
    private final int maxM0;
    private final int efConstruction;
    private final double mL;
    private final Random rnd;

    private final List<float[]> vecs = new ArrayList<>();
    private final List<Integer> docIds = new ArrayList<>();
    private final List<int[][]> links = new ArrayList<>();
    private final List<int[]> linkCount = new ArrayList<>();
    private final List<Integer> topLevel = new ArrayList<>();

    private int entry = -1;
    private int maxLevel = -1;
    private int[] visitedStamp = new int[1024];
    private int stamp = 1;
    private long compared;

    public Hnsw(int dim) { this(dim, 16, 200, 42); }

    public Hnsw(int dim, int m, int efConstruction, long seed) {
        this.dim = dim;
        this.m = m;
        this.maxM0 = m * 2;
        this.efConstruction = efConstruction;
        this.mL = 1.0 / Math.log(m);
        this.rnd = new Random(seed);
    }

    @Override public String name() { return "hnsw(m=" + m + ",efc=" + efConstruction + ")"; }

    @Override public int size() { return vecs.size(); }

    public long compared() { return compared; }

    public void resetCompared() { compared = 0; }

    // ------------------------------------------------------------------ insert

    @Override
    public void add(int docId, float[] vector) {
        float[] v = Encoder.normalize(vector.clone());
        int node = vecs.size();
        vecs.add(v);
        docIds.add(docId);
        int level = (int) Math.floor(-Math.log(Math.max(1e-9, rnd.nextDouble())) * mL);
        ensureNode(node, level);
        if (entry < 0) { entry = node; maxLevel = level; return; }

        int cur = entry;
        for (int l = maxLevel; l > level; l--) {
            cur = greedyClosest(v, cur, l);
        }
        int ep = cur;
        for (int l = Math.min(level, maxLevel); l >= 0; l--) {
            List<Cand> found = searchLayer(v, List.of(ep), efFor(l), l);
            int cap = l == 0 ? maxM0 : m;
            int[] chosen = selectNeighbours(found, m);
            setLinks(node, l, chosen);
            for (int nb : chosen) {
                int[] cur2 = neighbours(nb, l);
                int cap2 = l == 0 ? maxM0 : m;
                if (cur2.length < cap2) {
                    addLink(nb, l, node);
                } else if (closerThanAny(v, nb, l, node)) {
                    List<Cand> pool = new ArrayList<>();
                    for (int x : cur2) pool.add(new Cand(x, sim(nb, x)));
                    pool.add(new Cand(node, sim(nb, node)));
                    int[] keep = selectNeighbours(pool, cap2);
                    setLinks(nb, l, keep);
                }
            }
            if (!found.isEmpty()) ep = found.get(0).node;
        }
        if (level > maxLevel) { maxLevel = level; entry = node; }
    }

    private int efFor(int layer) { return layer == 0 ? efConstruction : 1 + m; }

    private void ensureNode(int node, int level) {
        int[][] per = new int[level + 1][];
        int[] cnt = new int[level + 1];
        for (int l = 0; l <= level; l++) per[l] = new int[0];
        links.add(per);
        linkCount.add(cnt);
        topLevel.add(level);
    }

    private int[] neighbours(int node, int level) {
        int[][] per = links.get(node);
        if (level >= per.length) return new int[0];
        return per[level];
    }

    private void setLinks(int node, int level, int[] to) {
        links.get(node)[level] = to;
    }

    private void addLink(int node, int level, int target) {
        int[] cur = neighbours(node, level);
        int[] next = new int[cur.length + 1];
        System.arraycopy(cur, 0, next, 0, cur.length);
        next[cur.length] = target;
        setLinks(node, level, next);
    }

    /** Keep the new edge only if the new node is closer to {@code a} than a's current neighbours are. */
    private boolean closerThanAny(float[] v, int a, int level, int candidate) {
        float da = sim(a, candidate);
        for (int x : neighbours(a, level)) if (sim(a, x) > da) return false;
        return true;
    }

    private int greedyClosest(float[] q, int start, int level) {
        int best = start;
        float bestScore = sim(start, q);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int nb : neighbours(best, level)) {
                float s = sim(nb, q);
                if (s > bestScore) { bestScore = s; best = nb; improved = true; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ search

    @Override
    public List<Neighbor> search(float[] q, int k) {
        return search(q, k, Math.max(k, 32));
    }

    public List<Neighbor> search(float[] q, int k, int ef) {
        if (entry < 0) return List.of();
        float[] nq = Encoder.normalize(q.clone());
        int cur = entry;
        for (int l = maxLevel; l > 0; l--) cur = greedyClosest(nq, cur, l);
        List<Cand> found = searchLayer(nq, List.of(cur), Math.max(ef, k), 0);
        List<Neighbor> out = new ArrayList<>(Math.min(k, found.size()));
        for (int i = 0; i < Math.min(k, found.size()); i++) out.add(new Neighbor(docIds.get(found.get(i).node), found.get(i).score));
        return out;
    }

    /** Best-first search on one layer; returns candidates sorted by similarity, descending. */
    private List<Cand> searchLayer(float[] q, List<Integer> eps, int ef, int level) {
        markVisited();
        PriorityQueue<Cand> front = new PriorityQueue<>(Comparator.comparingDouble(c -> -c.score));
        PriorityQueue<Cand> result = new PriorityQueue<>(Comparator.comparingDouble(c -> c.score));
        for (int ep : eps) {
            float s = simNode(ep, q);
            touch(ep);
            front.add(new Cand(ep, s));
            result.add(new Cand(ep, s));
        }
        while (!front.isEmpty()) {
            Cand c = front.poll();
            if (c.score < result.peek().score && result.size() >= ef) break;
            for (int nb : neighbours(c.node, level)) {
                if (seen(nb)) continue;
                touch(nb);
                compared++;
                float s = simNode(nb, q);
                if (result.size() < ef || s > result.peek().score) {
                    front.add(new Cand(nb, s));
                    result.add(new Cand(nb, s));
                    if (result.size() > ef) result.poll();
                }
            }
        }
        List<Cand> out = new ArrayList<>(result);
        out.sort(Comparator.comparingDouble((Cand c) -> -c.score).thenComparingInt(c -> c.node));
        return out;
    }

    private int[] selectNeighbours(List<Cand> pool, int cap) {
        List<Cand> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble((Cand c) -> -c.score).thenComparingInt(c -> c.node));
        int n = Math.min(cap, sorted.size());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = sorted.get(i).node;
        return out;
    }

    // ------------------------------------------------------------------ geometry

    private float sim(int a, int b) {
        float[] x = vecs.get(a), y = vecs.get(b);
        float s = 0;
        for (int i = 0; i < dim; i++) s += x[i] * y[i];
        return s;
    }

    private float sim(int a, float[] q) { return simNode(a, q); }

    private float simNode(int a, float[] q) {
        float[] x = vecs.get(a);
        float s = 0;
        for (int i = 0; i < dim; i++) s += x[i] * q[i];
        return s;
    }

    private void markVisited() {
        stamp++;
        if (stamp == Integer.MAX_VALUE) { visitedStamp = new int[Math.max(1024, vecs.size() + 1)]; stamp = 1; }
    }

    private void touch(int node) {
        if (node >= visitedStamp.length) visitedStamp = java.util.Arrays.copyOf(visitedStamp, node * 2 + 1);
        visitedStamp[node] = stamp;
    }

    private boolean seen(int node) {
        return node < visitedStamp.length && visitedStamp[node] == stamp;
    }

    public int levels() { return maxLevel + 1; }

    public long edges() {
        long e = 0;
        for (int[][] per : links) for (int[] l : per) e += l.length;
        return e;
    }
}
