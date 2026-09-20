package dev.jingyu.ms.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The word set the analyser performs maximum matching against.
 *
 * <p>Stored as a flat trie over UTF-16 code units: after {@link #seal()} every node's outgoing
 * edges are sorted by character, so a walk is a binary search per level with no allocation and no
 * {@code substring()} until a match actually completes. That is what lets a 50k-document index
 * build finish in seconds on a laptop.
 *
 * <p>Two sources feed it: the shipped general dictionary ({@code /dict/core.dict}) and whatever
 * {@link TermMining} discovered in the corpus being indexed. Custom user dictionaries are just a
 * third source ({@link #loadFile}).
 */
public final class Lexicon {

    private static final class Node {
        Map<Character, Integer> edges;   // filled while building, flattened by seal()
        int freq;                       // corpus frequency of the whole word (0 = dictionary only)
        boolean term;
    }

    private final List<Node> nodes = new ArrayList<>();
    private int maxWordLen = 1;
    private boolean frozen = false;
    private int wordCount;

    // flattened view, valid only when frozen
    private char[] edgeCh;
    private int[] edgeTo;
    private int[] childStart;
    private int[] childEnd;
    private int[] nodeFreq;

    public Lexicon() {
        newNode();
    }

    private int newNode() {
        nodes.add(new Node());
        return nodes.size() - 1;
    }

    public Lexicon add(String word) { return add(word, 0); }

    public Lexicon add(String word, int freq) {
        if (word == null || word.isEmpty()) return this;
        if (frozen) throw new IllegalStateException("lexicon already frozen");
        int node = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            Node n = nodes.get(node);
            if (n.edges == null) n.edges = new HashMap<>(4);
            Integer next = n.edges.get(c);
            if (next == null) {
                next = newNode();
                n.edges.put(c, next);
            }
            node = next;
        }
        Node n = nodes.get(node);
        if (!n.term) wordCount++;
        n.term = true;
        n.freq = Math.max(n.freq, freq);
        maxWordLen = Math.max(maxWordLen, word.length());
        return this;
    }

    /** Freeze the mutable builder maps into binary-searchable parallel arrays. */
    public synchronized Lexicon seal() {
        if (frozen) return this;
        int n = nodes.size();
        childStart = new int[n + 1];
        List<char[]> perNodeChars = new ArrayList<>(n);
        List<int[]> perNodeTo = new ArrayList<>(n);
        int total = 0;
        for (int i = 0; i < n; i++) {
            Node node = nodes.get(i);
            char[] chs = new char[0];
            int[] tos = new int[0];
            if (node.edges != null) {
                List<Character> keys = new ArrayList<>(node.edges.keySet());
                keys.sort(Character::compareTo);
                chs = new char[keys.size()];
                tos = new int[keys.size()];
                for (int k = 0; k < keys.size(); k++) {
                    chs[k] = keys.get(k);
                    tos[k] = node.edges.get(keys.get(k));
                }
            }
            perNodeChars.add(chs);
            perNodeTo.add(tos);
            childStart[i] = total;
            total += chs.length;
        }
        childStart[n] = total;
        edgeCh = new char[total];
        edgeTo = new int[total];
        for (int i = 0; i < n; i++) {
            System.arraycopy(perNodeChars.get(i), 0, edgeCh, childStart[i], perNodeChars.get(i).length);
            System.arraycopy(perNodeTo.get(i), 0, edgeTo, childStart[i], perNodeTo.get(i).length);
        }
        childEnd = childStart.clone();
        nodeFreq = new int[n];
        for (int i = 0; i < n; i++) nodeFreq[i] = nodes.get(i).freq;
        termAt = new boolean[n];
        for (int i = 0; i < n; i++) termAt[i] = nodes.get(i).term;
        nodes.clear();
        frozen = true;
        return this;
    }

    private boolean[] termAt;

    public boolean isSealed() { return frozen; }

    public int size() { return wordCount; }

    public int maxWordLen() {
        if (!frozen) seal();
        return maxWordLen;
    }

    /** Does the exact character range [from,to) form a dictionary word? */
    public boolean contains(String word) {
        return contains(word.toCharArray(), 0, word.length());
    }

    public boolean contains(char[] s, int from, int to) {
        int node = 0;
        for (int i = from; i < to; i++) {
            node = child(node, s[i]);
            if (node < 0) return false;
        }
        return termAt[node];
    }

    public int freq(char[] s, int from, int to) {
        int node = 0;
        for (int i = from; i < to; i++) {
            node = child(node, s[i]);
            if (node < 0) return 0;
        }
        return termAt[node] ? nodeFreq[node] : 0;
    }

    /** Longest dictionary prefix of {@code s[from..]}, or -1 if the first char is not a prefix. */
    public int longestMatch(char[] s, int from, int limit) {
        int node = 0;
        int best = termAt[0] ? 0 : -1;
        int max = Math.min(limit, from + maxWordLen);
        for (int i = from; i < max; i++) {
            node = child(node, s[i]);
            if (node < 0) break;
            if (termAt[node]) best = i + 1;
        }
        return best;
    }

    /** Is {@code c} the first character of at least one word? Cheap pre-filter for callers. */
    public boolean startsWith(char c) { return child(0, c) >= 0; }

    private int child(int node, char c) {
        int lo = childStart[node], hi = childStart[node + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            char ec = edgeCh[mid];
            if (ec < c) lo = mid + 1;
            else if (ec > c) hi = mid - 1;
            else return edgeTo[mid];
        }
        return -1;
    }

    // ------------------------------------------------------------------ sources

    public static Lexicon core() {
        Lexicon lex = new Lexicon();
        try (InputStream in = Lexicon.class.getResourceAsStream("/dict/core.dict")) {
            if (in == null) throw new IllegalStateException("missing bundled /dict/core.dict");
            ingest(lex, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bundled dictionary", e);
        }
        return lex;
    }

    public Lexicon loadFile(Path p) throws IOException {
        ingest(this, Files.readString(p, StandardCharsets.UTF_8));
        return this;
    }

    private static void ingest(Lexicon lex, String text) {
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            for (String w : l.split("[\\s,，、]+")) {
                if (w.isEmpty() || w.startsWith("#")) continue;
                String word = normalize(w);
                if (!word.isEmpty()) lex.add(word);
            }
        }
    }

    private static String normalize(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);
            if (Character.isLetter(c) && c < 0x3000) c = Character.toLowerCase(c);
            if (Character.isWhitespace(c) || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
