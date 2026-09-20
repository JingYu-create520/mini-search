package dev.jingyu.ms.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Unsigned LEB128 (variable-byte) integer coding.
 *
 * <p>Posting lists store document ids in increasing order, so what actually gets coded is the delta,
 * and almost every delta fits in one byte. Small ints cost one byte instead of four, which is the
 * difference between a snapshot that is annoying to ship and one that is not.
 */
public final class VarInt {

    private VarInt() {}

    public static void write(OutputStream out, long value) throws IOException {
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) out.write(b | 0x80);
            else { out.write(b); return; }
        }
    }

    public static int read(InputStream in) throws IOException {
        int shift = 0, result = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("truncated varint");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 35) throw new IOException("varint too long");
        }
    }

    public static int encodedSize(long value) {
        int n = 1;
        long v = value >>> 7;
        while (v != 0) { n++; v >>>= 7; }
        return n;
    }
}
