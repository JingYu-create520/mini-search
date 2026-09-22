package dev.jingyu.ms;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Lexicon;
import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.hybrid.Rrf;
import dev.jingyu.ms.index.InvertedIndex;
import dev.jingyu.ms.index.PostingList;
import dev.jingyu.ms.ranking.Bm25;
import dev.jingyu.ms.search.Searcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Index structure, BM25 algebra, phrase matching, fusion and snapshot round-trip. */
class IndexTest {

    private static InvertedIndex fresh() {
        return new InvertedIndex(new ChineseAnalyzer(Lexicon.core().seal()));
    }

    private static Map<String, String> fields(String title, String body) {
        Map<String, String> f = new HashMap<>();
        f.put("title", title);
        f.put("body", body);
        f.put("tags", "");
        return f;
    }

    @Test
    void postingsRecordDocIdsAndPositions() {
        InvertedIndex idx = fresh();
        idx.add("a", "", fields("倒排索引", "倒排索引 让 检索 变快 ； 索引 很 重要"));
        PostingList p = idx.postings("body", "索引");
        assertNotNull(p);
        assertEquals(1, p.size());
        assertArrayEquals(new int[]{1, 6}, p.positions(0), "positions must be absolute and ascending");
        assertEquals(2, p.freq(0));
    }

    @Test
    @DisplayName("删除要删干净：平均文档长度和打分都不能留下被删文档的痕迹")
    void deletionLeavesNoStatisticalTrace() {
        Map<String, String> a = fields("倒排索引", "索引 让 检索 变快");
        Map<String, String> b = fields("向量检索", "向量 的 相似 度 计算");
        Map<String, String> big = fields("长文档", "无关 内容 ".repeat(60));

        InvertedIndex plain = fresh();
        plain.add("a", "", a);
        plain.add("b", "", b);

        InvertedIndex tombstoned = fresh();
        tombstoned.add("a", "", a);
        tombstoned.add("b", "", b);
        tombstoned.add("c", "", big);
        assertTrue(tombstoned.delete(2), "the long document is the one being removed");

        for (int f = 0; f < InvertedIndex.FIELDS.size(); f++) {
            String name = InvertedIndex.FIELDS.get(f);
            assertEquals(plain.avgFieldLength(f), tombstoned.avgFieldLength(f), 1e-9,
                    "avg length of " + name + " must forget the deleted document, not just stop"
                            + " counting it in the denominator");
        }
        Bm25 bm25 = new Bm25();
        assertEquals(bm25.score(plain, List.of("索引")), bm25.score(tombstoned, List.of("索引")),
                "deleting an unrelated document must not re-weight the survivors");
    }

    @Test
    void deleteHidesADocumentWithoutReindexing() {
        InvertedIndex idx = fresh();
        idx.add("a", "", fields("标题一", "内容里有索引这个词"));
        idx.add("b", "", fields("标题二", "内容里有索引这个词"));
        Bm25 bm25 = new Bm25();
        assertEquals(2, bm25.score(idx, List.of("索引")).size());
        idx.deleteByExternalId("a");
        assertEquals(1, idx.numDocs());
        assertEquals(List.of(1), new ArrayList<>(bm25.score(idx, List.of("索引")).keySet()));
    }

    @Test
    void compactActuallyRemovesTombstones() {
        InvertedIndex idx = fresh();
        for (int i = 0; i < 12; i++) idx.add("d" + i, "", fields("标题", "索引 " + i));
        for (int i = 0; i < 6; i++) idx.deleteByExternalId("d" + i);
        idx.compact();
        assertEquals(6, idx.numDocs());
        assertEquals(6, idx.postings("body", "索引").size(), "tombstones must be physically gone");
        assertNull(idx.postings("body", "0"), "a deleted document's unique term must be gone");
        assertNotNull(idx.postings("body", "6"));
        assertEquals(6, new Bm25().score(idx, List.of("索引")).size());
    }

    @Test
    void titleHitOutweighsBodyHit() {
        InvertedIndex idx = fresh();
        idx.add("in-title", "", fields("倒排索引", "其他内容"));
        idx.add("in-body", "", fields("别的东西", "这里提到倒排索引一次"));
        Map<Integer, Double> s = new Bm25().score(idx, List.of("索引"));
        assertTrue(s.get(0) > s.get(1), "same single occurrence, title must win: " + s);
    }

    @Test
    @DisplayName("IDF：罕见词的权重必须高于常见词")
    void rareTermDominates() {
        assertTrue(Bm25.idf(1000, 1) > 3 * Bm25.idf(1000, 500),
                "a term in 1 document must beat one in half the collection");
        InvertedIndex idx = fresh();
        idx.add("a", "", fields("搜索 引擎", "搜索 疫苗 数据"));
        idx.add("b", "", fields("搜索 系统", "搜索 数据 资料"));
        idx.add("c", "", fields("搜索 平台", "搜索 数据 资料"));
        Bm25 bm25 = new Bm25(1.2, 0.75, new double[]{1, 1, 1});
        double byRare = bm25.score(idx, List.of("疫苗")).get(0);
        double byCommon = bm25.score(idx, List.of("资料")).get(1);
        assertTrue(byRare > byCommon, "df 1 must outrank df 2 at equal tf: " + byRare + " vs " + byCommon);
    }

    @Test
    void bm25SaturatesInTF() {
        InvertedIndex idx = fresh();
        idx.add("once", "", fields("x", "索引 内容"));
        idx.add("ten", "", fields("x", "索引 索引 索引 索引 索引 索引 索引 索引 索引 索引"));
        Map<Integer, Double> s = new Bm25(1.2, 0.75, new double[]{1, 1, 1}).score(idx, List.of("索引"));
        double once = s.get(0), ten = s.get(1);
        assertTrue(ten > once && ten < once * 10, "tf must help with diminishing returns: " + once + " vs " + ten);
    }

    @Test
    void phraseRequiresAdjacentOrderedPositions() {
        InvertedIndex idx = fresh();
        idx.add("ordered", "", fields("t", "中文 分词 是 第一步"));
        idx.add("reversed", "", fields("t", "分词 中文 是 第一步"));
        List<String> phrase = List.of("中文", "分词");
        List<Integer> hits = idx.phrase(1, phrase);
        assertEquals(List.of(0), hits, "only the document with 中文 immediately before 分词 may match");
    }

    @Test
    void rrfPrefersAgreementBetweenLists() {
        List<Integer> a = List.of(10, 11, 12);
        List<Integer> b = List.of(11, 13, 10);
        List<Integer> fused = Rrf.fuse(List.of(a, b), null, 60, 3);
        assertEquals(11, fused.get(0), "the document ranked high by both lists should win");
    }

    @Test
    void rrfToleratesDisagreeingLists() {
        List<Integer> a = List.of(1, 2, 3);
        List<Integer> b = List.of(9);
        assertEquals(List.of(1, 2, 3, 9), Rrf.fuse(List.of(a, b), null, 60, 10).stream().sorted().toList());
    }

    @Test
    @DisplayName("索引不变量：随机增删改后，搜索结果必须与暴力扫描一致")
    void randomOperationsMatchBruteForce() {
        InvertedIndex idx = fresh();
        Random rnd = new Random(7);
        String[] vocab = {"索引", "检索", "中文", "分词", "向量", "排序", "召回"};
        List<int[]> live = new ArrayList<>();      // [docId] with its terms
        List<List<String>> texts = new ArrayList<>();
        int nextId = 0;
        for (int round = 0; round < 400; round++) {
            int op = rnd.nextInt(10);
            if (op < 6) {
                StringBuilder sb = new StringBuilder();
                List<String> picked = new ArrayList<>();
                for (int k = 0; k < 1 + rnd.nextInt(4); k++) {
                    String t = vocab[rnd.nextInt(vocab.length)];
                    picked.add(t);
                    sb.append(t).append(' ');
                }
                idx.add("doc" + nextId, "", fields("标题", sb.toString()));
                live.add(new int[]{nextId});
                texts.add(picked);
                nextId++;
            } else if (op < 8 && !live.isEmpty()) {
                int i = rnd.nextInt(live.size());
                idx.delete(live.get(i)[0]);
                live.remove(i);
                texts.remove(i);
            } else if (!live.isEmpty()) {
                int i = rnd.nextInt(live.size());
                String q = vocab[rnd.nextInt(vocab.length)];
                List<Integer> viaIndex = new ArrayList<>();
                PostingList p = idx.postings("body", q);
                if (p != null) {
                    for (int k = 0; k < p.size(); k++) {
                        int d = p.doc(k);
                        boolean alive = false;
                        for (int[] l : live) if (l[0] == d) alive = true;
                        if (alive) viaIndex.add(d);
                    }
                }
                List<Integer> viaScan = new ArrayList<>();
                for (int j = 0; j < texts.size(); j++) {
                    if (texts.get(j).contains(q)) viaScan.add(live.get(j)[0]);
                }
                assertEquals(new TreeSet<>(viaScan), new TreeSet<>(viaIndex),
                        "round " + round + " diverged for query " + q);
            }
        }
        assertEquals(idx.numDocs(), live.size());
    }

    @Test
    void snapshotRoundTripServesIdenticalResults() throws IOException {
        List<Corpus.RawDoc> docs = Corpus.loadDemo();
        assertFalse(docs.isEmpty(), "the bundled corpus must be on the test classpath");
        Engine.Options o = new Engine.Options();
        o.trainVectors = false;
        Engine built = Engine.build(docs, o);
        Path file = Files.createTempFile("ms-snapshot", ".msnap");
        try {
            built.save(file);
            Engine loaded = Engine.restore(file, o, docs);
            assertEquals(built.index().numDocs(), loaded.index().numDocs());
            assertEquals(built.index().vocabularySize(), loaded.index().vocabularySize(),
                    "term count must survive serialisation");
            for (String q : List.of("倒排索引", "中文分词", "评测集 召回率", "板块 俯冲 海沟")) {
                List<Integer> a = built.searcher().rankedIds(q, Searcher.Mode.BM25, 10);
                List<Integer> b = loaded.searcher().rankedIds(q, Searcher.Mode.BM25, 10);
                assertEquals(a, b, "ranking changed after a save/load cycle for: " + q);
            }
            assertEquals(built.fingerprint(), loaded.fingerprint());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("自定义词典跟着快照走：不带 --dict 重启也还能搜到那个词")
    void customDictionarySurvivesTheSnapshot() throws IOException {
        Path dict = Files.createTempFile("ms-dict", ".txt");
        Path snap = Files.createTempFile("ms-dict-snap", ".msnap");
        try {
            Files.writeString(dict, "貔貅\n");
            // One occurrence, under the mining threshold, with mining off as well: nothing but the
            // dictionary can make 貔貅 a single term.
            List<Corpus.RawDoc> docs = List.of(new Corpus.RawDoc("d1", "", "摆件笔记",
                    "桌上放了一只貔貅，只提这一次。", "杂记"));

            Engine.Options with = new Engine.Options().vectors(false).mining(false);
            with.dictPath = dict.toString();
            Engine built = Engine.build(docs, with);
            built.save(snap);

            Engine.Options bare = new Engine.Options().vectors(false).mining(false);
            Engine restored = Engine.restore(snap, bare, docs);
            assertFalse(restored.searcher().rankedIds("貔貅", Searcher.Mode.BM25, 5).isEmpty(),
                    "the postings hold 貔貅 as one term; a restore that forgets the dictionary splits the"
                            + " query into two characters and finds nothing -- silently");
            assertTrue(restored.analyzer().terms("一只貔貅").contains("貔貅"),
                    "the query side must cut the way the stored index was cut");
        } finally {
            Files.deleteIfExists(dict);
            Files.deleteIfExists(snap);
        }
    }

    @Test
    @DisplayName("快照记住了它当初是怎么切的：选项变了要说出来")
    void restoreWarnsWhenTheTermStreamWouldChange() throws IOException {
        List<Corpus.RawDoc> docs = Corpus.loadDemo();
        Engine.Options base = new Engine.Options().vectors(false);
        Engine built = Engine.build(docs, base);
        Path file = Files.createTempFile("ms-drift", ".msnap");
        try {
            built.save(file);

            Engine same = Engine.restore(file, new Engine.Options().vectors(false), docs);
            assertNull(same.tokenizationDrift(), "identical flags must not produce a warning");

            Engine.Options stopped = new Engine.Options().vectors(false);
            stopped.stopwords = true;
            Engine drifted = Engine.restore(file, stopped, docs);
            assertNotNull(drifted.tokenizationDrift(),
                    "stopwords change which terms exist, so a restore under them is a different index");
            assertTrue(drifted.tokenizationDrift().contains("stopwords=false")
                            && drifted.tokenizationDrift().contains("stopwords=true"),
                    () -> "the message should name both sides: " + drifted.tokenizationDrift());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("快照在真实规模上也要逐位一致：3000 篇文档、多字节变长编码")
    void snapshotRoundTripHoldsAtScale() throws IOException {
        // Small corpora only ever exercise single-byte varbytes and short postings. Positions past 127,
        // delta chains across hundreds of documents and multi-byte field lengths are where a coding
        // mistake in SnapshotStore would show up, and a snapshot that silently reorders is worse than
        // one that fails to load.
        List<Corpus.RawDoc> docs = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            docs.add(new Corpus.RawDoc("d" + i, "u" + i, "标题" + i,
                    "倒排索引 与 分词 在 大规模 下 也 要 逐位 一致 " + filler(i), "标签" + (i % 17)));
        }
        Engine.Options o = new Engine.Options().vectors(false).mining(false);
        Engine built = Engine.build(docs, o);
        assertTrue(built.index().avgFieldLength(1) > 30, "the bodies must be long enough to matter");

        Path file = Files.createTempFile("ms-scale", ".msnap");
        try {
            built.save(file);
            Engine back = Engine.restore(file, o, docs);
            assertEquals(built.index().numDocs(), back.index().numDocs());
            assertEquals(built.index().vocabularySize(), back.index().vocabularySize());
            for (String q : List.of("倒排索引", "大规模 分词", "标签", "索引 一致")) {
                assertEquals(built.searcher().rankedIds(q, Searcher.Mode.BM25, 50),
                        back.searcher().rankedIds(q, Searcher.Mode.BM25, 50),
                        "top-50 must survive the round trip for: " + q);
                assertEquals(built.searcher().search(q, Searcher.Mode.BM25, 50, 0, true, false).total(),
                        back.searcher().search(q, Searcher.Mode.BM25, 50, 0, true, false).total(),
                        "phrase matching reads the position pool; it must agree too for: " + q);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A body whose length and term positions vary, so the encoding sees more than one byte width. */
    private static String filler(int i) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 8 + (i % 23); k++) {
            for (int r = 0; r < 1 + (k * 7 + i) % 9; r++) sb.append("填充词").append(k).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    void corruptedSnapshotIsDetectedNotTrusted() throws IOException {
        Engine e = Engine.build(Corpus.loadDemo(), new Engine.Options().vectors(false));
        Path file = Files.createTempFile("ms-corrupt", ".msnap");
        try {
            e.save(file);
            byte[] raw = Files.readAllBytes(file);
            raw[raw.length / 2] ^= 0x5A;                       // flip a byte inside a section
            Files.write(file, raw);
            Engine back = Engine.restore(file, new Engine.Options().vectors(false), Corpus.loadDemo());
            assertTrue(back.index().numDocs() > 0, "must fall back to a rebuild, not serve a broken index");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rankingIsStableAcrossEqualScores() {
        InvertedIndex idx = fresh();
        for (int i = 0; i < 5; i++) idx.add("d" + i, "", fields("t" + i, "共同词 只此一篇" + i));
        Bm25 bm25 = new Bm25();
        Searcher s = new Searcher(idx, bm25);
        List<Integer> first = s.rankedIds("共同词", Searcher.Mode.BM25, 10);
        List<Integer> second = s.rankedIds("共同词", Searcher.Mode.BM25, 10);
        assertEquals(first, second, "equal scores must break ties by doc id, never by hash order");
        assertEquals(List.of(0, 1, 2, 3, 4), first);
    }
}
