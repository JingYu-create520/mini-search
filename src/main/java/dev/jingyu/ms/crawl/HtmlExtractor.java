package dev.jingyu.ms.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main-text extraction by link density: cut the page into lines at block-level tags, record which
 * characters came from inside an anchor, drop lines that are mostly links or too short, and return
 * the consecutive run with the most non-link characters.
 *
 * Guaranteed behaviour, tested in HtmlExtractorTest: a page with no extractable prose yields an
 * empty body instead of an exception, and the caller then falls back to the meta description or the
 * title alone.
 */
public final class HtmlExtractor {

    static final char LINK_ON = '\u0001';
    static final char LINK_OFF = '\u0002';

    private static final Pattern REMOVE =
            Pattern.compile("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern META_NAME_FIRST =
            Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"']");
    private static final Pattern META_CONTENT_FIRST =
            Pattern.compile("(?is)<meta[^>]+content=[\"'](.*?)[\"'][^>]+name=[\"']description[\"']");
    private static final Pattern ANCHOR =
            Pattern.compile("(?is)<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>");
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?i)</?(p|div|li|ul|ol|br|hr|h[1-6]|tr|td|table|article|section|header|footer"
                    + "|blockquote|pre|dd|dt|main|figure)[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC = Pattern.compile("&#(\\d{1,6});|&#x([0-9a-fA-F]{1,6});");

    private HtmlExtractor() {}

    /** Title, prose, out-links and description of one page. */
    public record Page(String title, String text, List<String> links, String description) {
        public boolean hasProse() { return !text.isBlank(); }
    }

    public static Page extract(String html, String baseUrl) {
        if (html == null || html.isBlank()) return new Page("", "", List.of(), "");
        String doc = COMMENT.matcher(html).replaceAll(" ");
        doc = REMOVE.matcher(doc).replaceAll(" ");

        String title = clean(firstGroup(TITLE, doc));
        if (title.isEmpty()) title = clean(firstGroup(H1, doc));
        String desc = clean(first(META_NAME_FIRST.matcher(doc), META_CONTENT_FIRST.matcher(doc)));

        List<String> links = new ArrayList<>();
        StringBuilder marked = new StringBuilder();
        Matcher a = ANCHOR.matcher(doc);
        int cursor = 0;
        while (a.find()) {
            marked.append(doc, cursor, a.start());
            String href = a.group(1).trim();
            String anchorText = clean(a.group(2));
            if (!href.isEmpty() && !href.startsWith("#")
                    && !href.toLowerCase(java.util.Locale.ROOT).startsWith("javascript:")) {
                links.add(resolve(baseUrl, href));
            }
            marked.append(LINK_ON).append(anchorText).append(LINK_OFF);
            cursor = a.end();
        }
        marked.append(doc, cursor, doc.length());

        String body = bestRun(marked.toString());
        if (body.isBlank() && !desc.isBlank()) body = desc;
        return new Page(title, body, links, desc);
    }

    /** Replace block tags with newlines, drop blank lines, then keep the densest consecutive run. */
    private static String bestRun(String marked) {
        String withLines = BLOCK_TAG.matcher(marked).replaceAll("\n");
        String plain = TAG.matcher(withLines).replaceAll(" ");
        List<String> lines = new ArrayList<>();
        List<Integer> prose = new ArrayList<>();
        List<Boolean> linky = new ArrayList<>();
        for (String raw : plain.split("\n")) {
            String text = raw.replace(LINK_ON, ' ').replace(LINK_OFF, ' ').replaceAll("\\s+", " ").trim();
            if (text.length() < 2) continue;                      // blank lines must not break a run
            int linkChars = 0;
            boolean inLink = false;
            for (int k = 0; k < raw.length(); k++) {
                char c = raw.charAt(k);
                if (c == LINK_ON) inLink = true;
                else if (c == LINK_OFF) inLink = false;
                else if (inLink && !Character.isWhitespace(c)) linkChars++;
            }
            lines.add(text);
            prose.add(Math.max(0, text.length() - Math.min(linkChars, text.length())));
            linky.add((double) linkChars / text.length() > 0.5 || text.length() < 12);
        }
        if (lines.isEmpty()) return "";

        int bestFrom = -1, bestTo = -1, bestLen = -1, bestProse = -1;
        int runStart = -1, runProse = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (linky.get(i)) { runStart = -1; runProse = 0; continue; }
            if (runStart < 0) runStart = i;
            runProse += prose.get(i);
            boolean endOfRun = i + 1 >= lines.size() || linky.get(i + 1);
            if (endOfRun) {
                int len = i - runStart + 1;
                if (runProse > bestProse || (runProse == bestProse && len < bestLen)) {
                    bestProse = runProse;
                    bestLen = len;
                    bestFrom = runStart;
                    bestTo = i;
                }
            }
        }
        if (bestFrom < 0) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) if (!linky.get(i)) all.append(lines.get(i)).append('\n');
            return all.toString().trim();
        }
        StringBuilder out = new StringBuilder();
        for (int i = bestFrom; i <= bestTo; i++) out.append(lines.get(i)).append('\n');
        return out.toString().trim();
    }

    static String clean(String fragment) {
        if (fragment == null) return "";
        String s = TAG.matcher(fragment).replaceAll(" ");
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    /** The entities that actually show up in body text, plus numeric references. */
    static String unescape(String s) {
        String out = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'");
        return expandRefs(out);
    }

    /** {@code &#NNN;} and {@code &#xHHH;} to characters; anything unparsable is left alone. */
    private static String expandRefs(String s) {
        Matcher m = NUMERIC.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            try {
                int cp = m.group(1) != null
                        ? Integer.parseInt(m.group(1))
                        : Integer.parseInt(m.group(2), 16);
                rep = new String(Character.toChars(cp));
            } catch (RuntimeException e) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String first(Matcher m1, Matcher m2) {
        if (m1.find()) return m1.group(1);
        if (m2.find()) return m2.group(1);
        return "";
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** Resolve a possibly relative href against the page URL. */
    public static String resolve(String base, String href) {
        try {
            return java.net.URI.create(base).resolve(href).toString();
        } catch (RuntimeException e) {
            return href;
        }
    }
}
