package dev.jingyu.ms.vector;

/** Turns text into a fixed-length float vector that is always L2-normalised. */
public interface Encoder {

    int dimension();

    /** Turns text into a unit-length vector. */
    float[] encode(String text);

    /**
     * Query-side encoding. Models that were fine-tuned with an instruction prefix on the query side
     * only (BGE's Chinese models do) need this to be different from document encoding; every other
     * encoder just delegates.
     */
    default float[] encodeQuery(String text) {
        return encode(text);
    }

    /** Stable identifier written into snapshots and shown in /stats. */
    String name();

    /**
     * True for an encoder backed by a downloaded model rather than one trained on the corpus
     * in front of us. The engine re-encodes documents through a different path for those, and
     * asks this interface instead of testing a concrete class -- see {@link #loadPretrained}.
     */
    default boolean pretrained() {
        return false;
    }

    /**
     * Load the optional ONNX-backed encoder for a local model directory.
     *
     * <p>Reflection is deliberate. {@code build.sh} compiles this tree without
     * {@code libs/onnxruntime.jar}, and a direct {@code new OnnxEncoder(...)} in the core made
     * that branch die with three "cannot find symbol" errors -- the zero-Maven loop the script
     * advertises simply did not work. Keeping the only compile-time reference to the class here
     * is what makes the jar optional in the sense the README uses.
     *
     * @throws ReflectiveOperationException when the optional implementation is not on the classpath
     */
    static Encoder loadPretrained(java.nio.file.Path modelDir, boolean clsPooling, int maxTokens,
                                  boolean queryInstruction) throws ReflectiveOperationException {
        Class<?> impl = Class.forName("dev.jingyu.ms.vector.OnnxEncoder");
        int hiddenSize = (int) impl.getMethod("hiddenSizeOf", java.nio.file.Path.class).invoke(null, modelDir);
        return (Encoder) impl
                .getConstructor(java.nio.file.Path.class, boolean.class, int.class, boolean.class, int.class)
                .newInstance(modelDir, clsPooling, maxTokens, queryInstruction, hiddenSize);
    }

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
