package dev.jingyu.ms;

import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.eval.EvalHarness;
import dev.jingyu.ms.search.Searcher;
import dev.jingyu.ms.vector.OnnxEncoder;
import dev.jingyu.ms.vector.WordPieceTokenizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The optional pretrained path.
 *
 * <p>The tokenizer test always runs -- it needs only {@code vocab.txt}. Everything that loads the
 * weights is guarded by an assumption, so a checkout without {@code scripts/fetch-model.sh} run yet
 * skips rather than fails: the base project must stay buildable with nothing downloaded.
 */
class OnnxModelTest {

    private static final Path MODEL = Path.of("models/bge-small-zh-v1.5");
    private static boolean haveWeights;

    @BeforeAll
    static void probe() {
        haveWeights = Files.exists(MODEL.resolve("onnx").resolve("model_quantized.onnx"))
                && Files.isRegularFile(MODEL.resolve("vocab.txt"));
    }

    private static void requireModel() {
        Assumptions.assumeTrue(haveWeights,
                "run scripts/fetch-model.sh to enable the pretrained embedding tests");
    }

    @Test
    void wordPieceHandlesChineseAndLatin() throws IOException {
        Path vocab = MODEL.resolve("vocab.txt");
        Assumptions.assumeTrue(Files.isRegularFile(vocab), "needs vocab.txt (scripts/fetch-model.sh)");
        WordPieceTokenizer t = new WordPieceTokenizer(vocab);
        assertEquals(101, t.id(WordPieceTokenizer.CLS), "BERT convention: [CLS]=101, [SEP]=102");
        assertEquals(102, t.id(WordPieceTokenizer.SEP));

        WordPieceTokenizer.Encoded e = t.encode("中文分词 BM25 检索", 64);
        assertEquals(101, e.ids()[0]);
        assertEquals(102, e.ids()[e.ids().length - 1]);
        assertEquals(e.length(), e.mask().length);
        for (int id : e.ids()) assertTrue(id >= 0 && id < t.vocabularySize(), "id out of range: " + id);
        assertEquals(0, java.util.Arrays.stream(e.ids()).filter(id -> id == t.id(WordPieceTokenizer.UNK))
                .count(), "common characters must not fall through to [UNK]");
        // one Han character is one piece in a Chinese BERT vocabulary
        assertTrue(t.encode("中文", 8).ids().length <= 4, "CJK should not explode into many sub-pieces");
    }

    @Test
    void pretrainedVectorsRankTheQueryNoLexicalMethodReached() {
        requireModel();
        Engine e = Engine.build(Corpus.loadDemo(), modelOptions());
        assertTrue(e.searcher().vectorsEnabled(), "the dense path should be on with --model");
        // "why are coastal cities mild in winter" -> the specific-heat document shares no query words,
        // and BM25/thesaurus both miss it entirely. Rank 1 is not asserted: the corpus also holds
        // 城市热岛效应, which is a defensible nearby answer, so this checks top-5 presence -- the same
        // criterion the eval file scores -- rather than a single hand-guessed ordering.
        List<Integer> ranked = e.searcher().rankedIds("为什么海边城市冬天不太冷", Searcher.Mode.VECTOR, 10);
        List<String> top5 = ranked.subList(0, Math.min(5, ranked.size())).stream()
                .map(id -> e.index().doc(id).externalId()).toList();
        assertTrue(top5.contains("sci-016"), "expected the ocean climate document in the top 5, got " + top5);
        assertTrue(e.searcher().rankedIds("为什么海边城市冬天不太冷", Searcher.Mode.BM25, 5).stream()
                .map(id -> e.index().doc(id).externalId()).noneMatch("sci-016"::equals),
                "the point of the test is that lexical retrieval misses this one");
    }

    @Test
    @DisplayName("装上本地模型后，dense 召回必须明显超过词法")
    void pretrainedDenseRetrievalBeatsLexical() throws IOException {
        requireModel();
        Engine e = Engine.build(Corpus.loadDemo(), modelOptions());
        List<EvalHarness.Query> queries = EvalHarness.loadQueries(Path.of("data/eval/queries.jsonl"));
        double lexical = EvalHarness.average(
                EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.BM25, 5)).get("recall@k");
        double dense = EvalHarness.average(
                EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.VECTOR, 5)).get("recall@k");
        double hybrid = EvalHarness.average(
                EvalHarness.evaluate(e.searcher(), queries, Searcher.Mode.HYBRID, 5)).get("recall@k");
        assertTrue(dense >= 0.95, "vector recall@5 with bge should be >= 0.95, got " + dense);
        assertTrue(dense > lexical, "a pretrained model must beat lexical retrieval here: " + dense + " vs " + lexical);
        assertTrue(hybrid >= lexical, "fusion must never fall below the best single lexical list: "
                + hybrid + " vs " + lexical);
    }

    @Test
    void instructionPrefixOnlyAppliesToQueries() {
        requireModel();
        try (OnnxEncoder enc = new OnnxEncoder(MODEL, true, 256, true, OnnxEncoder.hiddenSizeOf(MODEL))) {
            String text = "海边城市冬天不太冷";
            float[] doc = enc.encode(text);
            float[] query = enc.encodeQuery(text);
            float[] sameWithoutInstruction = enc.encode(text);
            assertArrayEquals(doc, sameWithoutInstruction, 0f, "document encoding must be stable");
            double dot = 0;
            for (int i = 0; i < doc.length; i++) dot += doc[i] * query[i];
            assertTrue(dot < 0.999, "the query instruction should move the vector, got cos=" + dot);
            assertEquals(512, enc.dimension(), "bge-small hidden size");
        } catch (Exception ex) {
            fail("model should load: " + ex);
        }
    }

    private static Engine.Options modelOptions() {
        Engine.Options o = new Engine.Options();
        o.modelPath = MODEL.toString();
        o.knn = "brute";
        return o;
    }
}
