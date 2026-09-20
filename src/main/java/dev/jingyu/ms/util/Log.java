package dev.jingyu.ms.util;

/** Timestamped stderr logging with a quiet switch, because the engine has no logging dependency. */
public final class Log {

    private static boolean quiet;

    private Log() {}

    public static void setQuiet(boolean q) { quiet = q; }

    public static void info(String fmt, Object... args) {
        if (quiet) return;
        System.err.printf("[mini-search] %s%n", String.format(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        System.err.printf("[mini-search] WARN %s%n", String.format(fmt, args));
    }

    public static void error(String fmt, Object... args) {
        System.err.printf("[mini-search] ERROR %s%n", String.format(fmt, args));
    }

    public static long started() { return System.nanoTime(); }

    public static double since(long nanos) { return (System.nanoTime() - nanos) / 1e9; }

    /** Milliseconds elapsed since {@code startNanos}. */
    public static double ms(long startNanos) { return (System.nanoTime() - startNanos) / 1e6; }
}
