package dev.jingyu.ms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The argument parser, which is the part of a command-line tool people judge first.
 *
 * <p>It used to decide "does this flag take a value?" by looking at whether another token follows, so
 * any switch placed before the text swallowed it: {@code search --stopwords 中文分词} parsed the query
 * as the value of {@code --stopwords}, left the positional slot empty, and answered with a usage
 * message. A flag with no argument has to be declared as such, and the declaration has to agree with
 * what {@code --help} promises -- which is what the last test here checks, because a help text and a
 * parser that drift apart is a bug report that can never be reproduced.
 */
class CliArgsTest {

    @Test
    @DisplayName("开关不吃后面的词：search --stopwords 中文分词 要还能搜")
    void switchesDoNotStealTheQuery() {
        Map<String, String> a = MiniSearch.parseArgs(new String[]{"search", "--stopwords", "中文分词"}, 1);
        assertEquals("中文分词", a.get("_"), "--stopwords takes no argument, so the next token is the query");
        assertTrue(a.containsKey("stopwords"));

        Map<String, String> b = MiniSearch.parseArgs(new String[]{"analyze", "--quiet", "文本"}, 1);
        assertEquals("文本", b.get("_"));

        Map<String, String> c = MiniSearch.parseArgs(
                new String[]{"search", "--no-vectors", "--force-vectors", "--rebuild", "倒排索引"}, 1);
        assertEquals("倒排索引", c.get("_"), "three switches in a row must not eat the text either");
    }

    @Test
    @DisplayName("带值的选项照旧取值")
    void valuedOptionsStillTakeTheirArgument() {
        Map<String, String> a = MiniSearch.parseArgs(new String[]{"search", "--mode", "bm25", "倒排索引"}, 1);
        assertEquals("bm25", a.get("mode"));
        assertEquals("倒排索引", a.get("_"));

        Map<String, String> b = MiniSearch.parseArgs(
                new String[]{"serve", "--data", "/tmp/d", "--port", "9200"}, 1);
        assertEquals("/tmp/d", b.get("data"));
        assertEquals("9200", b.get("port"));

        Map<String, String> eq = MiniSearch.parseArgs(new String[]{"search", "--mode=vector", "x"}, 1);
        assertEquals("vector", eq.get("mode"), "--flag=value is the unambiguous spelling");
    }

    @Test
    void aMisspelledOptionIsNamedNotIgnored() {
        Map<String, String> o = MiniSearch.parseArgs(new String[]{"serve", "--datadir", "x"}, 1);
        assertTrue(MiniSearch.unknown(o).contains("--datadir"),
                "--datadir is not a thing; saying so beats quietly serving the default directory");
        assertTrue(MiniSearch.unknown(
                MiniSearch.parseArgs(new String[]{"serve", "--data", "x", "--quiet"}, 1)).isEmpty(),
                "real options must not be reported");
    }

    @Test
    @DisplayName("--help 里写的开关，解析器必须都认识；解析器认识的开关，帮助里不该藏着")
    void helpTextAndParserAgree() {
        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("--([A-Za-z][A-Za-z-]*)").matcher(MiniSearch.usage());
        while (m.find()) documented.add(m.group(1));
        assertFalse(documented.isEmpty(), "the help text should document at least something");

        for (String flag : documented) {
            assertTrue(MiniSearch.OPTIONS.contains(flag),
                    "--" + flag + " is promised by --help but the parser does not know it");
        }
        for (String sw : MiniSearch.SWITCHES) {
            assertTrue(documented.contains(sw),
                    "--" + sw + " is declared as a switch in the parser but never documented");
        }
    }
}
