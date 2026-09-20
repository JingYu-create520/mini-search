package dev.jingyu.ms.analyzer;

/**
 * One emitted term.
 *
 * @param term     normalised surface form that goes into the inverted index
 * @param start    character offset in the original (un-normalised) text, for highlighting
 * @param end      exclusive end offset
 * @param position ordinal of this term in the stream, for phrase queries
 */
public record Token(String term, int start, int end, int position) {
    public int length() { return end - start; }
}
