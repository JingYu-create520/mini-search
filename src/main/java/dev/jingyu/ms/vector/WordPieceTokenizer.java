package dev.jingyu.ms.vector;

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
 * WordPiece tokenizer for BERT-style Chinese models -- the piece of "run a local embedding model"
 * that is not the model.
 *
 * <p>Two stages, matching what {@code BertTokenizer} does so the ids line up with the checkpoint:
 * <ol>
 *   <li><b>basic split</b>: every CJK character becomes a word of its own (the Chinese checkpoints
 *       have single characters in the vocabulary, and whitespace is not a delimiter), while a run of
 *       latin letters/digits/punctuation stays one word and is lower-cased;</li>
 *   <li><b>wordpiece</b>: greedy longest-match against the vocabulary, continuing pieces with
 *       {@code ##}. A character that is not in the vocabulary at all becomes [UNK].</li>
 * </ol>
 *
 * <p>Only {@code [CLS]}/{@code [SEP]}/{@code [UNK]}/{@code ##} semantics are implemented -- no
 * sub-word regularization, no byte-level BPE. That is the whole difference between a Chinese BERT
 * tokenizer and the GPT-family ones, which is worth knowing when you read this and not when you
 * debug a production pipeline.
 */
public final class WordPieceTokenizer {

    public static final String CLS = "[CLS]";
    public static final String SEP = "[SEP]";
    public static final String UNK = "[UNK]";
    public static final String PAD = "[PAD]";

    private final Map<String, Integer> index = new HashMap<>(40_000);
    private final int maxWordLen;

    public WordPieceTokenizer(Path vocabFile) throws IOException {
        this(Files.readAllLines(vocabFile, StandardCharsets.UTF_8));
    }

    public WordPieceTokenizer(List<String> lines) {
        int i = 0;
        int longest = 1;
        for (String line : lines) {
            String w = line.trim();
            if (w.isEmpty()) continue;
            index.put(w, i++);
            longest = Math.max(longest, w.replace("##", "").length());
        }
        this.maxWordLen = Math.max(2, longest);
        if (!index.containsKey(UNK)) throw new IllegalStateException("vocab has no [UNK]");
    }

    public static WordPieceTokenizer fromClasspath(String resource) throws IOException {
        try (InputStream in = WordPieceTokenizer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing tokenizer resource: " + resource);
            return new WordPieceTokenizer(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().collect(java.util.stream.Collectors.toList()));
        }
    }

    public int vocabularySize() { return index.size(); }

    public int id(String token) { return index.getOrDefault(token, index.get(UNK)); }

    /** One encoded sequence: model input ids plus the special-token scaffolding. */
    public record Encoded(int[] ids, int[] mask, int[] typeIds) {
        public int length() { return ids.length; }
    }

    public Encoded encode(String text, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        for (String word : basicSplit(text)) {
            List<String> p = wordPiece(word);
            if (p == null) continue;
            pieces.addAll(p);
            if (pieces.size() > maxTokens - 2) break;
        }
        if (pieces.size() > maxTokens - 2) pieces = new ArrayList<>(pieces.subList(0, maxTokens - 2));

        int n = pieces.size() + 2;
        int[] ids = new int[n];
        int[] mask = new int[n];
        int[] types = new int[n];
        ids[0] = id(CLS);
        for (int i = 0; i < pieces.size(); i++) ids[i + 1] = id(pieces.get(i));
        ids[n - 1] = id(SEP);
        java.util.Arrays.fill(mask, 1);
        return new Encoded(ids, mask, types);
    }

    /** CJK characters stand alone; latin/digit runs stay together and get lower-cased. */
    List<String> basicSplit(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                flush(out, run);
                continue;
            }
            if (isCjk(c) || isPunctuation(c)) {
                flush(out, run);
                out.add(String.valueOf(c));
            } else if (run.length() > 0 && (isCjk(run.charAt(run.length() - 1)) || isPunctuation(run.charAt(run.length() - 1)))) {
                flush(out, run);
                run.append(Character.toLowerCase(c));
            } else {
                run.append(Character.toLowerCase(c));
            }
        }
        flush(out, run);
        return out;
    }

    private static void flush(List<String> out, StringBuilder run) {
        if (run.length() > 0) {
            out.add(run.toString());
            run.setLength(0);
        }
    }

    private List<String> wordPiece(String word) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = Math.min(word.length(), start + maxWordLen);
            String found = null;
            while (end > start) {
                String cand = (start == 0 ? "" : "##") + word.substring(start, end);
                if (index.containsKey(cand)) { found = cand; break; }
                end--;
            }
            if (found == null) return null;                       // an unknown character kills the word
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static boolean isPunctuation(char c) {
        int t = Character.getType(c);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION
                || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION || t == Character.INITIAL_QUOTE_PUNCTUATION
                || t == Character.FINAL_QUOTE_PUNCTUATION                || t == Character.MODIFIER_SYMBOL
                || (c >= 0x2000 && c <= 0x206F);
    }
}
