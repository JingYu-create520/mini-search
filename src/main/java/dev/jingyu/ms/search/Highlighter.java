package dev.jingyu.ms.search;

import dev.jingyu.ms.analyzer.ChineseAnalyzer;
import dev.jingyu.ms.analyzer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Produces a result snippet with the matched terms wrapped in {@code <em>} tags.
 *
 * <p>The analyser already reports offsets into the <i>original</i> text, which is the only reason
 * this is possible: cutting a window around the densest cluster of matches and then inserting
 * markers from right to left keeps every earlier offset valid.
 */
public final class Highlighter {

    private Highlighter() {}

    public static String snippet(ChineseAnalyzer analyzer, String text, Set<String> terms,
                                 int tokenWindow, int maxChars) {
        if (text == null || text.isEmpty()) return "";
        List<Token> tokens = analyzer.analyze(text);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) if (terms.contains(tokens.get(i).term())) hits.add(i);

        int from = 0, to = Math.min(tokens.size(), 24);
        if (!hits.isEmpty()) {
            int bestStart = 0, bestCount = -1;
            for (int h = 0; h < hits.size(); h++) {
                int start = Math.max(0, hits.get(h) - 6);
                int end = Math.min(tokens.size(), start + tokenWindow);
                int count = 0;
                for (int x : hits) if (x >= start && x < end) count++;
                if (count > bestCount || (count == bestCount && start < bestStart)) { bestCount = count; bestStart = start; }
            }
            from = bestStart;
            to = Math.min(tokens.size(), bestStart + tokenWindow);
        }
        if (from >= to) return ellipsize(text, maxChars);

        int charFrom = tokens.get(from).start();
        int charTo = to > from ? tokens.get(to - 1).end() : charFrom;
        String body = text.substring(charFrom, charTo);
        String marked = mark(text, tokens, from, to, terms);
        String prefix = charFrom > 0 ? "…" : "";
        String suffix = charTo < text.length() ? "…" : "";
        if (marked.length() > maxChars) marked = marked.substring(0, Math.min(maxChars, marked.length())) + "…";
        return prefix + marked + suffix;
    }

    /**
     * Text from [from,to) with {@code <em>} around every query term occurrence. Built by walking the
     * tokens and escaping each untouched span, so the result is safe to drop into innerHTML and the
     * offsets never have to be re-mapped after escaping.
     */
    public static String mark(String original, List<Token> tokens, int from, int to, Set<String> terms) {
        if (tokens.isEmpty() || from >= to) return escape(original);
        to = Math.min(to, tokens.size());
        int base = tokens.get(from).start();
        int end = tokens.get(to - 1).end();
        StringBuilder sb = new StringBuilder();
        int cursor = base;
        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);
            if (!terms.contains(t.term())) continue;
            if (t.start() < cursor || t.end() > end) continue;
            sb.append(escape(original.substring(cursor, t.start())));
            sb.append("<em>").append(escape(original.substring(t.start(), t.end()))).append("</em>");
            cursor = t.end();
        }
        sb.append(escape(original.substring(cursor, end)));
        return sb.toString();
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The no-token path. It still has to escape, because "no analysable terms" is a property of the
     * text, not a promise about it: the UI drops this string straight into innerHTML, like every
     * other snippet.
     */
    private static String ellipsize(String text, int max) {
        String t = escape(text.strip());
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
