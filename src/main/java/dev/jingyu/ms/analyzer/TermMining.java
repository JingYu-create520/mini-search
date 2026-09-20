package dev.jingyu.ms.analyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unsupervised new-word extraction, so the analyser can cut vocabulary it has never been told.
 *
 * <p>Three signals, the classical trio used by statistical Chinese segmentation:
 * <ul>
 *   <li><b>frequency</b> -- how often the character string occurs at all;</li>
 *   <li><b>c cohesion</b> (凝固度) -- pointwise mutual information between the string and its best
 *       internal split. "搜索引擎" is far more likely than its halves would predict independently,
 *       so it coheres; "研究的方" does not;</li>
 *   <li><b>freedom</b> (自由度) -- entropy of the characters observed on the left and on the right.
 *       A real word stands next to many different neighbours (low constraint, high entropy);
 *       a accidental string is usually pinned by grammar (high constraint, low entropy). We take the
 *       smaller of the two, because a word must be free on <i>both</i> sides.</li>
 * </ul>
 *
 * <p>A candidate is accepted when all three clear their thresholds. The thresholds are corpus-size
 * dependent (cohesion grows with total n-gram count), which is why {@link Options} is explicit and
 * {@code TermMiningTest} reports the measured distribution instead of hiding it.
 */
public final class TermMining {

    public record Options(int maxLength, int minLength, int minFreq, double minCohesion,
                          double minFreedom, int maxWords) {

        /** Sensible defaults for a corpus of a few thousand documents; see TermMiningTest. */
        public static Options defaults() { return new Options(6, 2, 3, 0.5, 0.8, 60_000); }

        public static Options of(int minFreq, double minCohesion, double minFreedom) {
            return new Options(6, 2, minFreq, minCohesion, minFreedom, 60_000);
        }
    }

    public record Word(String term, int freq, double cohesion, double freedom) {}

    private TermMining() {}

    public static List<Word> mine(List<String> texts, Options o) {
        Counting c = count(texts, o);
        List<String> candidates = new ArrayList<>();
        c.counts.forEach((gram, freq) -> {
            if (gram.length() >= o.minLength() && gram.length() <= o.maxLength() && freq >= o.minFreq())
                candidates.add(gram);
        });

        List<Word> accepted = new ArrayList<>();
        for (String g : candidates) {
            double coh = cohesion(g, c);
            if (coh < o.minCohesion()) continue;
            double free = freedom(g, c);
            if (free < o.minFreedom()) continue;
            accepted.add(new Word(g, c.counts.getInt(g), coh, free));
        }
        accepted.sort(Comparator.comparingInt((Word w) -> w.freq()).reversed()
                .thenComparing(Comparator.comparingDouble((Word w) -> w.cohesion()).reversed()));
        if (accepted.size() > o.maxWords()) accepted = new ArrayList<>(accepted.subList(0, o.maxWords()));
        return accepted;
    }

    /** Mine and install into a lexicon in one step. Returns how many words were added. */
    public static int enrich(Lexicon lexicon, List<String> texts, Options o) {
        int added = 0;
        for (Word w : mine(texts, o)) {
            if (lexicon.contains(w.term())) continue;
            lexicon.add(w.term(), w.freq());
            added++;
        }
        return added;
    }

    // ------------------------------------------------------------------ scoring

    /**
     * Normalised pointwise mutual information, minimised over the string's internal splits.
     *
     * <p>Raw PMI grows with corpus size, which makes any fixed threshold meaningless the moment you
     * change the amount of text -- the failure mode that bites most hand-rolled miners. NPMI divides
     * PMI by the self-information of the whole string, so it lands in [-1, 1] regardless of size:
     * about 1 for a compound whose parts only ever occur together, about 0 for chance co-occurrence,
     * negative when the parts are more common apart than together.
     *
     * <p>Epsilon smoothing on the halves matters too: a half that never occurs alone makes the string
     * MORE likely to be a word, not less, so treating a zero count as zero would throw away every
     * genuinely novel compound.
     */
    private static double cohesion(String g, Counting c) {
        int fg = c.counts.getInt(g);
        if (fg <= 0 || c.total <= 0) return 0;
        double pw = (double) fg / c.total;
        double selfInfo = -Math.log(pw) / Math.log(2);
        if (selfInfo <= 0) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < g.length(); i++) {
            String l = g.substring(0, i), r = g.substring(i);
            double fl = Math.max(c.counts.getInt(l), 0.5);
            double fr = Math.max(c.counts.getInt(r), 0.5);
            double pmi = Math.log(((double) fg * c.total) / (fl * fr)) / Math.log(2);
            best = Math.min(best, pmi / selfInfo);
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    private static double freedom(String g, Counting c) {
        return Math.min(entropy(c.left.get(g)), entropy(c.right.get(g)));
    }

    private static double entropy(Map<Character, Integer> neighbours) {
        if (neighbours == null || neighbours.isEmpty()) return 0;
        int total = 0;
        for (int v : neighbours.values()) total += v;
        double h = 0;
        for (int v : neighbours.values()) {
            double p = (double) v / total;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    // ------------------------------------------------------------------ counting

    private static final class Counting {
        final MergeMap counts = new MergeMap();
        final Map<String, Map<Character, Integer>> left = new HashMap<>();
        final Map<String, Map<Character, Integer>> right = new HashMap<>();
        long total;
    }

    /** Two passes: gram frequencies, then neighbour distributions for the surviving candidates. */
    private static Counting count(List<String> texts, Options o) {
        Counting c = new Counting();
        for (String text : texts) {
            char[] s = text.toCharArray();
            int n = s.length;
            int i = 0;
            while (i < n) {
                if (ChineseAnalyzer.typeOf(s[i]) != ChineseAnalyzer.HAN) { i++; continue; }
                int j = i;
                while (j < n && ChineseAnalyzer.typeOf(s[j]) == ChineseAnalyzer.HAN) j++;
                for (int p = i; p < j; p++) {
                    for (int len = 2; len <= o.maxLength() && p + len <= j; len++) {
                        c.counts.add(new String(s, p, len));
                        c.total++;
                    }
                    c.counts.add(String.valueOf(s[p]));
                    c.total++;
                }
                i = j;
            }
        }
        // pass 2: neighbour distributions, only for grams frequent enough to be candidates
        for (String text : texts) {
            char[] s = text.toCharArray();
            int n = s.length;
            for (int i = 0; i < n; i++) {
                if (ChineseAnalyzer.typeOf(s[i]) != ChineseAnalyzer.HAN) continue;
                for (int len = o.minLength(); len <= o.maxLength() && i + len <= n; len++) {
                    String g = new String(s, i, len);
                    if (c.counts.getInt(g) < o.minFreq()) continue;
                    if (i > 0 && ChineseAnalyzer.typeOf(s[i - 1]) == ChineseAnalyzer.HAN)
                        c.left.computeIfAbsent(g, k -> new HashMap<>()).merge(s[i - 1], 1, Integer::sum);
                    int after = i + len;
                    if (after < n && ChineseAnalyzer.typeOf(s[after]) == ChineseAnalyzer.HAN)
                        c.right.computeIfAbsent(g, k -> new HashMap<>()).merge(s[after], 1, Integer::sum);
                }
            }
        }
        return c;
    }

    /** Open-addressed String -> int counter; boxing-free and ~3x faster than HashMap<String,Integer>. */
    static final class MergeMap {
        private String[] keys;
        private int[] vals;
        private int size, mask, threshold;

        MergeMap() { alloc(1 << 16); }

        private void alloc(int cap) {
            keys = new String[cap];
            vals = new int[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7);
        }

        void add(String k) { merge(k, 1); }

        void merge(String k, int d) {
            int i = probe(k);
            if (keys[i] == null) {
                keys[i] = k;
                vals[i] = d;
                if (++size > threshold) grow();
                return;
            }
            if (keys[i].equals(k)) { vals[i] += d; return; }
            while (keys[i] != null && !keys[i].equals(k)) i = (i + 1) & mask;
            if (keys[i] == null) { keys[i] = k; vals[i] = d; size++; } else vals[i] += d;
        }

        private int probe(String k) {
            int h = k.hashCode();
            h ^= h >>> 16;
            return h & mask;
        }

        private void grow() {
            String[] ok = keys; int[] ov = vals;
            alloc(ok.length << 1);
            for (int i = 0; i < ok.length; i++) if (ok[i] != null) mergeExisting(ok[i], ov[i]);
        }

        private void mergeExisting(String k, int v) {
            int i = probe(k);
            while (keys[i] != null) i = (i + 1) & mask;
            keys[i] = k;
            vals[i] = v;
            size++;
        }

        int getInt(String k) {
            int i = probe(k);
            while (keys[i] != null) {
                if (keys[i].equals(k)) return vals[i];
                i = (i + 1) & mask;
            }
            return 0;
        }

        Set<String> keySet() {
            Set<String> out = new HashSet<>(size * 2);
            for (String k : keys) if (k != null) out.add(k);
            return out;
        }

        void forEach(java.util.function.BiConsumer<String, Integer> body) {
            for (int i = 0; i < keys.length; i++) if (keys[i] != null) body.accept(keys[i], vals[i]);
        }

        int size() { return size; }
    }
}
