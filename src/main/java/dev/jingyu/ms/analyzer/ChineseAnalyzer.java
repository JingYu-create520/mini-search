package dev.jingyu.ms.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chinese/English mixed analyser: normalise, then cut.
 *
 * <p>Pipeline, in the order it runs, because every later layer (index, BM25, embeddings, highlight)
 * depends on these exact semantics:
 * <ol>
 *   <li><b>Normalise</b> full-width ASCII to half-width, fold latin case, keep everything else verbatim
 *       so that {@code start}/{@code end} offsets still address the original string.</li>
 *   <li><b>Segment the character stream</b> into runs of {han}/{latin}/{digit}/{other}. Anything in
 *       {@code other} (punctuation, spaces) is a boundary and is dropped.</li>
 *   <li><b>Cut han runs</b> with bidirectional maximum matching against the {@link Lexicon}: run
 *       forward and backward, then pick the better cut (fewer tokens, then fewer single characters,
 *       then the larger spread of word lengths). Unknown characters become single-character tokens,
 *       which is what keeps unseen text searchable instead of throwing it away.</li>
 *   <li><b>Cut latin/digit runs</b> at the letter/digit boundary, except decimals such as
 *       {@code 3.14} which survive as one term.</li>
 * </ol>
 *
 * <p>Deliberately <b>no stopword removal by default</b>: BM25's IDF already discounts words that
 * appear everywhere, while dropping them breaks phrase queries ("中华人民共和国" style exact matches).
 * {@link #setStopwords(boolean)} exists so the effect can be measured rather than believed; the eval
 * harness reports both settings.
 */
public final class ChineseAnalyzer {

    static final int OTHER = 0, HAN = 1, LATIN = 2, DIGIT = 3;

    /** Closed-class particles that hurt nothing when removed and help precision when kept out. */
    static final List<String> STOPWORDS = List.of(
            "的", "了", "着", "过", "吗", "呢", "吧", "啊", "呀", "哦", "嗯", "啦", "么", "么");

    private final Lexicon lexicon;
    private boolean stopwords;

    public ChineseAnalyzer(Lexicon lexicon) { this.lexicon = lexicon; }

    public ChineseAnalyzer setStopwords(boolean on) { this.stopwords = on; return this; }

    public boolean stopwordsEnabled() { return stopwords; }

    public Lexicon lexicon() { return lexicon; }

    public List<Token> analyze(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        if (!lexicon.isSealed()) lexicon.seal();     // first cut freezes the dictionary
        char[] raw = text.toCharArray();
        char[] buf = new char[raw.length];
        int n = 0;
        int[] map = new int[raw.length + 1]; // normalised index -> original index
        for (int i = 0; i < raw.length; i++) {
            char c = raw[i];
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0); // full-width ASCII
            else if (c == '\u3000') c = ' ';
            else if (c == '\u2018' || c == '\u2019' || c == '\u00B4') c = '\'';
            else if (c == '\u201C' || c == '\u201D') c = '"';
            else if (c == '\u2013' || c == '\u2014' || c == '\uFF0D') c = '-';
            if (c < 0x80 && Character.isUpperCase(c)) c = Character.toLowerCase(c);
            map[n] = i;
            buf[n++] = c;
        }
        map[n] = raw.length;

        int[] type = new int[n];
        for (int i = 0; i < n; i++) type[i] = typeOf(buf[i]);

        int pos = 0;
        int i = 0;
        while (i < n) {
            int t = type[i];
            if (t == OTHER) { i++; continue; }
            int j = i;
            while (j < n && type[j] == t) j++;
            if (t == HAN) {
                pos = cutHan(buf, i, j, out, pos, map, raw.length);
            } else if (t == LATIN) {
                pos = emitWord(buf, i, j, out, pos, map, raw.length);
            } else {
                // digits: absorb a decimal point / thousands separators between digits
                int k = j;
                while (k + 1 < n && (buf[k] == '.' || buf[k] == ',') && type[k + 1] == DIGIT) {
                    int m = k + 1;
                    while (m < n && type[m] == DIGIT) m++;
                    k = m;
                }
                pos = emitWord(buf, i, k, out, pos, map, raw.length);
                i = k;
                continue;
            }
            i = j;
        }
        return out;
    }

    static int typeOf(char c) {
        if (c >= 0x4E00 && c <= 0x9FFF) return HAN;
        if (c >= 0x3400 && c <= 0x4DBF) return HAN;          // CJK ext. A
        if (c >= 0xF900 && c <= 0xFAFF) return HAN;          // compatibility ideographs
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return LATIN;
        if (c >= '0' && c <= '9') return DIGIT;
        return OTHER;
    }

    private int emitWord(char[] buf, int from, int to, List<Token> out, int pos, int[] map, int len) {
        String term = new String(buf, from, to - from);
        out.add(new Token(term, map[from], map[to], pos));
        return pos + 1;
    }

    // ------------------------------------------------------------------ han cutting

    private void add(String term, int from, int to, List<Token> out, int[] pos, int[] map) {
        if (stopwords && STOPWORDS.contains(term)) return;
        out.add(new Token(term, map[from], map[to], pos[0]++));
    }

    /** Forward maximum matching over [from,to). */
    private List<int[]> fmm(char[] s, int from, int to) {
        List<int[]> cuts = new ArrayList<>();
        int i = from;
        while (i < to) {
            int end = lexicon.longestMatch(s, i, to);
            if (end < 0) end = i + 1;
            cuts.add(new int[]{i, end});
            i = end;
        }
        return cuts;
    }

    /** Backward maximum matching over [from,to): longest suffix word, walking right to left. */
    private List<int[]> bmm(char[] s, int from, int to) {
        List<int[]> cuts = new ArrayList<>();
        int i = to;
        while (i > from) {
            int bestStart = i - 1;
            int maxLen = Math.min(lexicon.maxWordLen(), i - from);
            for (int len = maxLen; len >= 2; len--) {
                if (lexicon.contains(s, i - len, i)) { bestStart = i - len; break; }
            }
            cuts.add(0, new int[]{bestStart, i});
            i = bestStart;
        }
        return cuts;
    }

    private int cutHan(char[] buf, int from, int to, List<Token> out, int pos, int[] map, int len) {
        List<int[]> f = fmm(buf, from, to);
        List<int[]> b = bmm(buf, from, to);
        List<int[]> chosen = better(f, b);
        int[] p = {pos};
        for (int[] c : chosen) add(new String(buf, c[0], c[1] - c[0]), c[0], c[1], out, p, map);
        return p[0];
    }

    /** Ambiguity resolution, in priority order: fewer tokens, then fewer single characters,
     *  then the wider spread of word lengths. The returned int is "higher is better". */
    static List<int[]> better(List<int[]> a, List<int[]> b) {
        int sa = score(a), sb = score(b);
        if (sa != sb) return sa > sb ? a : b;
        int va = variance(a), vb = variance(b);
        return va >= vb ? a : b;
    }

    private static int score(List<int[]> cuts) {
        int singles = 0;
        for (int[] c : cuts) if (c[1] - c[0] == 1) singles++;
        return 100_000 - cuts.size() * 100 - singles;
    }

    private static int variance(List<int[]> cuts) {
        if (cuts.size() < 2) return 0;
        double mean = 0;
        for (int[] c : cuts) mean += c[1] - c[0];
        mean /= cuts.size();
        double v = 0;
        for (int[] c : cuts) { double d = (c[1] - c[0]) - mean; v += d * d; }
        return (int) Math.round(v * 1000 / cuts.size());
    }

    // ------------------------------------------------------------------ convenience

    /** Just the terms, no offsets -- what BM25 and the vector layer consume. */
    public List<String> terms(String text) {
        List<Token> ts = analyze(text);
        List<String> out = new ArrayList<>(ts.size());
        for (Token t : ts) out.add(t.term());
        return out;
    }
}
