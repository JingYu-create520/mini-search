package dev.jingyu.ms;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Lexicon;
import dev.jingyu.ms.analyzer.TermMining;
import dev.jingyu.ms.analyzer.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Analyser tests.
 *
 * The gold table is a golden master kept in src/test/resources/analyzer-gold.txt: one sentence per
 * line, a tab, then the expected cuts. It was reviewed by hand, and any difference now fails the
 * build, which is the only practical way to keep a hand-written segmenter from drifting. After an
 * intentional analyser change, run scripts/regold.sh, read the diff, then commit it.
 *
 * The mining tests are the counterpart: the statistical layer must discover words the shipped
 * dictionary deliberately does not contain, and must refuse strings merely glued together by grammar.
 */
class AnalyzerTest {

    private static ChineseAnalyzer plain() {
        return new ChineseAnalyzer(Lexicon.core().seal());
    }

    private static List<String[]> gold() throws IOException {
        try (InputStream in = AnalyzerTest.class.getClassLoader().getResourceAsStream("analyzer-gold.txt")) {
            assertNotNull(in, "analyzer-gold.txt must be on the test classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String[]> rows = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                assertTrue(tab > 0, "malformed gold row (no tab): " + line);
                rows.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
            }
            assertTrue(rows.size() >= 50, "the plan asks for at least 50 gold sentences, got " + rows.size());
            return rows;
        }
    }

    @Test
    @DisplayName("金样例：切分必须逐字一致")
    void goldCuts() throws IOException {
        ChineseAnalyzer a = plain();
        List<String> diffs = new ArrayList<>();
        for (String[] row : gold()) {
            String got = String.join(" ", a.terms(row[0]));
            if (!got.equals(row[1])) {
                diffs.add("\n  text     : " + row[0] + "\n  expected : " + row[1] + "\n  actual   : " + got);
            }
        }
        assertEquals(0, diffs.size(), () -> diffs.size() + " gold cut(s) changed:" + String.join("\n", diffs)
                + "\n(有意改动请跑 scripts/regold.sh 并复核 diff)");
    }

    @Test
    void offsetsPointAtTheOriginalText() {
        ChineseAnalyzer a = plain();
        String text = "BM25打分依赖文档长度";
        for (Token t : a.analyze(text)) {
            String slice = text.substring(t.start(), t.end());
            assertEquals(t.term(), slice.toLowerCase(),
                    "offsets must address the original string even after case folding");
        }
    }

    @Test
    void everyHanCharSurvivesSegmentation() {
        String text = "中文处理需要切分未登录词语";
        StringBuilder joined = new StringBuilder();
        for (Token t : plain().analyze(text)) joined.append(t.term());
        assertEquals(text, joined.toString(), "no character may be dropped or duplicated");
    }

    @Test
    void positionsAreConsecutiveWithinOneField() {
        List<Token> ts = plain().analyze("倒排索引 与 BM25 打分");
        for (int i = 0; i < ts.size(); i++) assertEquals(i, ts.get(i).position());
    }

    @Test
    void punctuationIsABoundaryNotATerm() {
        List<String> terms = plain().terms("你好，世界！hello, world 2026");
        assertTrue(terms.containsAll(List.of("你", "好", "世界", "hello", "world", "2026")), terms.toString());
        assertTrue(terms.stream().noneMatch(t -> t.equals("，") || t.equals("!") || t.equals(",")),
                "punctuation must be dropped: " + terms);
    }

    @Test
    void fullWidthAndCaseAreFolded() {
        assertEquals(List.of("apple", "iphone"), plain().terms("ＡＰＰＬＥ ｉＰｈｏｎｅ"));
    }

    @Test
    void decimalsStayOneTerm() {
        assertTrue(plain().terms("版本 3.11 已发布").contains("3.11"), "3.11 must not be cut in two");
    }

    @Test
    void stopwordsOnlyWhenAsked() {
        ChineseAnalyzer with = plain().setStopwords(true);
        assertTrue(plain().terms("我们的搜索引擎").contains("的"));
        assertFalse(with.terms("我们的搜索引擎").contains("的"));
    }

    @Test
    @DisplayName("统计新词发现必须挖出词典里没有的领域词")
    void miningDiscoversUnseenDomainWords() {
        String novel = "螺旋钻机";
        String[] left = {"这台", "那台", "国产", "进口", "一台", "两台", "新款", "二手", "推荐的", "厂里的"};
        String[] right = {"的转速很高", "正在运转", "被维修过", "价格更低", "需要换钻头", "已经停产",
                "口碑不错", "效率惊人", "刚刚到货", "即将发货"};
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                corpus.add("工地上" + left[i] + novel + right[j] + "，同时" + left[(i + 3) % left.length]
                        + "挖掘机" + right[(j + 5) % right.length] + "。");
            }
        }
        Lexicon bare = Lexicon.core().seal();
        assertFalse(bare.contains(novel), "the test is pointless if the word is already shipped");
        List<TermMining.Word> found = TermMining.mine(corpus,
                new TermMining.Options(6, 2, 3, 0.5, 0.8, 5_000));
        assertTrue(found.stream().anyMatch(w -> w.term().equals(novel)),
                "mining should surface " + novel + "; got " + found.stream().limit(30).toList());
    }

    @Test
    void miningRejectsGrammarGluedStrings() {
        String[] left = {"他", "我们", "对方", "公司", "组委会", "学校", "政府", "员工", "委员会", "小组"};
        String[] right = {"很重要", "被推迟", "已完成", "很难", "被否决", "很急", "通过了", "取消了",
                "延期了", "公布了"};
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                corpus.add(left[i] + "为了完成这件事" + right[j] + "，" + left[(i + 4) % left.length]
                        + "为了完成那件事" + right[(j + 7) % right.length] + "。");
            }
        }
        List<TermMining.Word> found = TermMining.mine(corpus,
                new TermMining.Options(6, 2, 3, 0.5, 0.8, 5_000));
        List<String> startingWithLe = found.stream().map(TermMining.Word::term)
                .filter(t -> t.startsWith("了")).toList();
        assertTrue(startingWithLe.isEmpty(),
                "a string pinned by grammar must not become a word: " + startingWithLe);
    }
}
