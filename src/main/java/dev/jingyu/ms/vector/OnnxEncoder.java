package dev.jingyu.ms.vector;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A real pretrained embedding model (BAAI <code>bge-small-zh-v1.5</code>, ONNX, int8 quantised,
 * 24 MB), running locally through ONNX Runtime -- the semantic layer that corpus-trained word2vec
 * cannot replace on a small corpus.
 *
 * <p>Why optional rather than default: the weights are 24 MB and the runtime jar is 93 MB, which
 * would break the promise that the base jar has zero runtime dependencies. ONNX Runtime is a
 * <i>provided</i> dependency, the weights never enter git, and {@code scripts/fetch-model.sh} pulls
 * both from a mirror that works without a proxy. The base jar still starts and searches;
 * {@code --model DIR} turns the neural path on.
 *
 * <p>Two model-specific choices are exposed as flags because both change retrieval quality and are
 * worth measuring rather than trusting:
 * <ul>
 *   <li><b>pooling</b> -- BGE ships with CLS pooling, while sentence-transformers exports are often
 *     consumed with mean pooling. The wrong one still produces plausible-looking vectors.</li>
 *   <li><b>query instruction</b> -- the Chinese BGE models are fine-tuned with a prefix on the query
 *     side only ({@code 为这个句子生成表示以用于检索文章：}). Applying it to documents, or to neither side,
 *     costs recall.</li>
 * </ul>
 */
public final class OnnxEncoder implements Encoder, AutoCloseable {

    public static final String BGE_ZH_QUERY_INSTRUCTION = "为这个句子生成表示以用于检索文章：";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final Set<String> inputNames;
    private final boolean clsPooling;
    private final int maxTokens;
    private final String queryPrefix;
    private final int dim;

    public OnnxEncoder(Path modelDir, boolean clsPooling, int maxTokens, boolean queryInstruction,
                       int configHiddenSize) throws IOException, OrtException {
        Path weights = findOnnx(modelDir);
        Path vocab = modelDir.resolve("vocab.txt");
        if (!Files.isRegularFile(vocab)) throw new IOException("no vocab.txt in " + modelDir);
        this.tokenizer = new WordPieceTokenizer(vocab);
        this.clsPooling = clsPooling;
        this.maxTokens = Math.max(16, maxTokens);
        this.queryPrefix = queryInstruction ? BGE_ZH_QUERY_INSTRUCTION : "";
        this.env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(weights.toString(), opts);
        }
        this.inputNames = session.getInputNames();
        this.dim = configHiddenSize > 0 ? configHiddenSize : 512;
    }

    /** {@code hidden_size} from the model's config.json, so we do not have to infer it. */
    public static int hiddenSizeOf(Path modelDir) {
        Path cfg = modelDir.resolve("config.json");
        if (!Files.isRegularFile(cfg)) return -1;
        try {
            String text = Files.readString(cfg, java.nio.charset.StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("\"hidden_size\"\\s*:\\s*(\\d+)").matcher(text);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Path findOnnx(Path modelDir) throws IOException {
        Path preferred = modelDir.resolve("onnx").resolve("model_quantized.onnx");
        if (Files.isRegularFile(preferred)) return preferred;
        try (var s = Files.walk(modelDir, 3)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".onnx"))
                    .min(java.util.Comparator.comparingInt(p -> p.toString().length()))
                    .orElseThrow(() -> new IOException("no .onnx file under " + modelDir));
        }
    }

    @Override public int dimension() { return dim; }

    @Override public String name() { return "bge-small-zh(onnx," + (clsPooling ? "cls" : "mean") + ")"; }

    /** Loaded from a downloaded model directory, not trained on the corpus at hand. */
    @Override public boolean pretrained() { return true; }

    public String modelFile() { return "bge-small-zh-v1.5 int8"; }

    public WordPieceTokenizer tokenizer() { return tokenizer; }

    /** Query side gets the instruction the model was fine-tuned with; documents do not. */
    @Override
    public float[] encodeQuery(String text) {
        return encode(queryPrefix + (text == null ? "" : text));
    }

    @Override
    public float[] encode(String text) {
        if (text == null || text.isEmpty()) return new float[dim];
        WordPieceTokenizer.Encoded e = tokenizer.encode(text, maxTokens);
        try {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            // BERT-style exports declare their inputs as tensor(int64); feeding int32 fails with
            // ORT_INVALID_ARGUMENT, which is the kind of thing you learn once and then encode here.
            OnnxTensor ids = tensor(e.ids());
            feeds.put("input_ids", ids);
            OnnxTensor mask = null;
            OnnxTensor types = null;
            if (inputNames.contains("attention_mask")) {
                mask = tensor(e.mask());
                feeds.put("attention_mask", mask);
            }
            if (inputNames.contains("token_type_ids")) {
                types = tensor(e.typeIds());
                feeds.put("token_type_ids", types);
            }
            float[][][] hidden;
            try (OrtSession.Result r = session.run(new HashMap<>(feeds))) {
                OnnxValue first = r.get(0);
                if (first == null) throw new OrtException("model returned no output");
                hidden = (float[][][]) first.getValue();
            } finally {
                ids.close();
                if (mask != null) mask.close();
                if (types != null) types.close();
            }
            return Encoder.normalize(pool(hidden[0], e.mask()));
        } catch (OrtException ex) {
            throw new IllegalStateException("onnx inference failed: " + ex.getMessage(), ex);
        }
    }

    private OnnxTensor tensor(int[] values) throws OrtException {
        long[] wide = new long[values.length];
        for (int i = 0; i < values.length; i++) wide[i] = values[i];
        return OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(wide), new long[]{1, values.length});
    }

    private float[] pool(float[][] hidden, int[] mask) {
        if (clsPooling) return hidden[0];
        float[] acc = new float[hidden[0].length];
        int n = 0;
        for (int i = 0; i < hidden.length; i++) {
            if (mask[i] == 0) continue;
            for (int d = 0; d < acc.length; d++) acc[d] += hidden[i][d];
            n++;
        }
        if (n == 0) return acc;
        for (int d = 0; d < acc.length; d++) acc[d] /= n;
        return acc;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // nothing useful to do at shutdown
        }
    }
}
