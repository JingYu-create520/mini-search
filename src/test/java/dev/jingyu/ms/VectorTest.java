package dev.jingyu.ms;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Lexicon;
import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.semantic.DistributedThesaurus;
import dev.jingyu.ms.vector.BruteForce;
import dev.jingyu.ms.vector.EmbeddingModel;
import dev.jingyu.ms.vector.Encoder;
import dev.jingyu.ms.vector.Hnsw;
import dev.jingyu.ms.vector.Idf;
import dev.jingyu.ms.vector.VectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The vector layer: exact vs approximate neighbours, embedding behaviour, thesaurus expansion. */
class VectorTest {

    private static List<float[]> randomVectors(int n, int dim, long seed) {
        Random rnd = new Random(seed);
        List<float[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[dim];
            for (int d = 0; d < dim; d++) v[d] = rnd.nextFloat() * 2 - 1;
            out.add(Encoder.normalize(v));
        }
        return out;
    }

    @Test
    @DisplayName("HNSW 正确性：recall@10 相对暴力 KNN 必须 ≥ 0.95")
    void hnswRecallAgainstBruteForce() {
        int dim = 48, n = 1500, queries = 60;
        List<float[]> data = randomVectors(n, dim, 11);
        BruteForce exact = new BruteForce(dim);
        Hnsw approx = new Hnsw(dim, 16, 200, 42);
        for (int i = 0; i < n; i++) {
            exact.add(i, data.get(i));
            approx.add(i, data.get(i));
        }
        List<float[]> qs = randomVectors(queries, dim, 12);
        double sum = 0;
        for (float[] q : qs) {
            Set<Integer> truth = new HashSet<>();
            for (VectorIndex.Neighbor x : exact.search(q, 10)) truth.add(x.docId());
            int hit = 0;
            for (VectorIndex.Neighbor x : approx.search(q, 10, 64)) if (truth.contains(x.docId())) hit++;
            sum += hit / 10.0;
        }
        double recall = sum / queries;
        assertTrue(recall >= 0.95, "HNSW recall@10 = " + recall);

        // ef is the recall/speed dial: shrinking it must measurably cost recall, which is what makes
        // the structure tunable rather than merely fast.
        double narrow = 0;
        for (float[] q : qs) {
            Set<Integer> truth = new HashSet<>();
            for (VectorIndex.Neighbor x : exact.search(q, 10)) truth.add(x.docId());
            int hit = 0;
            for (VectorIndex.Neighbor x : approx.search(q, 10, 10)) if (truth.contains(x.docId())) hit++;
            narrow += hit / 10.0;
        }
        assertTrue(narrow / queries <= recall + 1e-9,
                "a narrower candidate queue cannot be more accurate than a wider one");
        assertTrue(approx.edges() > 0 && approx.levels() >= 1);
    }

    @Test
    void hnswFindsIdenticalVectorImmediately() {
        int dim = 16;
        Hnsw g = new Hnsw(dim, 16, 100, 7);
        List<float[]> vs = randomVectors(200, dim, 3);
        for (int i = 0; i < vs.size(); i++) g.add(i, vs.get(i));
        List<VectorIndex.Neighbor> top = g.search(vs.get(77), 1, 32);
        assertEquals(77, top.get(0).docId());
        assertEquals(1.0, top.get(0).score(), 1e-4);
    }

    @Test
    void bruteForceAndHnswAgreeOnTinyData() {
        int dim = 8;
        List<float[]> vs = randomVectors(12, dim, 5);
        BruteForce b = new BruteForce(dim);
        Hnsw h = new Hnsw(dim, 8, 64, 5);
        for (int i = 0; i < vs.size(); i++) { b.add(i, vs.get(i)); h.add(i, vs.get(i)); }
        List<Integer> eb = b.search(vs.get(0), 5).stream().map(VectorIndex.Neighbor::docId).toList();
        List<Integer> eh = h.search(vs.get(0), 5, 12).stream().map(VectorIndex.Neighbor::docId).toList();
        assertEquals(new HashSet<>(eb), new HashSet<>(eh), "with ef = n the graph cannot miss");
    }

    private static ChineseAnalyzer testAnalyzer() {
        return new ChineseAnalyzer(Lexicon.core().seal());
    }

    private static java.util.Map<String, Integer> emptyDf() { return new java.util.HashMap<>(); }

    @Test
    @DisplayName("文档向量：单位长度、去均值后按维度求和应接近零")
    void vectorsAreNormalisedAndCentred() {
        List<String> corpus = new ArrayList<>(List.of(
                "今天的天气很好适合出门散步", "明天天气不错适合出去走走",
                "这台机器的索引速度很快", "那台机器检索速度很快",
                "倒排索引让检索变快", "向量检索把句子映射成空间中的点"));
        EmbeddingModel m = trainedModelWithDf(corpus, 24);
        ChineseAnalyzer an = m.analyzer();
        List<float[]> docs = new ArrayList<>();
        float[] mean = new float[24];
        for (String s : corpus) docs.add(m.raw(an.terms(s)));
        for (float[] v : docs) for (int d = 0; d < 24; d++) mean[d] += v[d];
        for (int d = 0; d < 24; d++) mean[d] /= docs.size();
        m.setCenter(mean);
        for (float[] raw : docs) {
            float[] v = m.toVector(raw);
            double norm = 0;
            for (float x : v) norm += x * x;
            if (norm > 0) assertEquals(1.0, Math.sqrt(norm), 1e-4, "vectors are stored unit length");
        }
        double[] residual = new double[24];
        for (float[] raw : docs) {
            float[] v = m.toVector(raw);
            for (int d = 0; d < 24; d++) residual[d] += v[d];
        }
        double longest = 0;
        for (int d = 0; d < 24; d++) longest = Math.max(longest, Math.abs(residual[d]) / docs.size());
        assertTrue(longest < 1.0, "after centring the mean direction must shrink, got " + longest);
    }

    private static EmbeddingModel trainedModelWithDf(List<String> sentences, int dim) {
        Lexicon lex = Lexicon.core();
        ChineseAnalyzer a = new ChineseAnalyzer(lex.seal());
        List<List<String>> corpus = new ArrayList<>();
        for (String s : sentences) corpus.add(a.terms(s));
        Map<String, Integer> df = new java.util.HashMap<>();
        for (List<String> c : corpus) for (String t : new HashSet<>(c)) df.merge(t, 1, Integer::sum);
        Idf idf = new Idf(df, sentences.size());
        EmbeddingModel.Config cfg = new EmbeddingModel.Config();
        cfg.dim = dim;
        cfg.epochs = 25;
        cfg.lr = 0.05;
        EmbeddingModel m = new EmbeddingModel(a, idf, cfg);
        m.train(corpus);
        return m;
    }

    private static boolean isZero(float[] v) {
        for (float x : v) if (x != 0) return false;
        return true;
    }

    @Test
    void embeddingTrainingIsDeterministicForASeed() {
        List<String> corpus = List.of("中文分词与倒排索引", "向量检索与混合排序", "混合排序融合两路结果");
        EmbeddingModel one = trainedModelWithDf(new ArrayList<>(corpus), 24);
        EmbeddingModel two = trainedModelWithDf(new ArrayList<>(corpus), 24);
        float[] a = one.raw(one.analyzer().terms("索引"));
        float[] b = two.raw(two.analyzer().terms("索引"));
        assertArrayEquals(a, b, 1e-6f, "the same seed must reproduce the same eval numbers");
    }

    @Test
    void modelSaveAndLoadPreservesVectors() throws IOException {
        EmbeddingModel m = trainedModelWithDf(new ArrayList<>(List.of("索引 检索 排序", "检索 排序 融合")), 16);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        m.save(bos);
        ChineseAnalyzer a = new ChineseAnalyzer(Lexicon.core().seal());
        EmbeddingModel.Config cfg = new EmbeddingModel.Config();
        cfg.dim = 16;
        EmbeddingModel back = EmbeddingModel.load(new ByteArrayInputStream(bos.toByteArray()), a,
                new Idf(emptyDf(), 1), cfg);
        assertArrayEquals(m.raw(a.terms("检索")), back.raw(a.terms("检索")), 1e-6f);
    }

    @Test
    @DisplayName("分布式同源词典要能给出真正的共现词")
    void thesaurusExpandsWithCoOccurringTerms() {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options().vectors(false));
        InvertedIndex idx = e.index();
        DistributedThesaurus th = DistributedThesaurus.build(idx, idx.allDocs(), e.analyzer(), 8, 2, 0.05);
        assertTrue(th.size() > 0, "a corpus of 84 documents must yield some co-occurrence structure");
        List<String> rel = th.related("索引");
        assertFalse(rel.isEmpty(), "索引 co-occurs with plenty of terms in this corpus");
        List<String> expanded = th.expand(List.of("索引"), 6);
        assertFalse(expanded.isEmpty());
        assertFalse(expanded.contains("索引"), "a query must not be expanded with itself");
    }

    @Test
    void thesaurusExpansionIsBounded() {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options().vectors(false));
        DistributedThesaurus th = DistributedThesaurus.build(e.index(), e.index().allDocs(), e.analyzer(), 8, 2, 0.02);
        assertTrue(th.expand(List.of("检索", "索引", "分词", "向量"), 5).size() <= 5,
                "the expansion budget is what keeps query time bounded");
    }
}
