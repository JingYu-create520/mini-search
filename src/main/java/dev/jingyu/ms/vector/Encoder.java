package dev.jingyu.ms.vector;

/** Turns text into a fixed-length float vector that is always L2-normalised. */
public interface Encoder {

    int dimension();

    float[] encode(String text);

    /** Stable identifier written into snapshots and shown in /stats. */
    String name();

    static float[] normalize(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        if (s <= 0) return v;
        float inv = (float) (1.0 / Math.sqrt(s));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }

    /** Dot product of two normalised vectors == cosine similarity, in [-1, 1]. */
    static float dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }
}
