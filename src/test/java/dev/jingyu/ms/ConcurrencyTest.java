package dev.jingyu.ms;

import dev.jingyu.ms.core.Corpus;
import dev.jingyu.ms.core.Engine;
import dev.jingyu.ms.search.Searcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine is read by a thread pool and written by the same pool: {@code POST /api/index},
 * {@code /api/crawl} and a running search all overlap. Nothing in a single-threaded test says whether
 * that overlap is safe, and the index is mutated in place rather than swapped, so this is the load
 * shape that has to stay green.
 *
 * <p>What this test does NOT do is prove the lock it is guarding. With the read side of
 * {@link Engine}'s lock removed it still passes here, and it is reported as such on purpose: the
 * postings are plain {@code int[]}s that are replaced before the slot that indexes them is written,
 * and on x86 a store-release ordering makes a reader miss the newest documents rather than read past
 * the end of an array. Missing the newest document during a concurrent add is a tolerable answer; a
 * term lost mid-resize of the {@code HashMap}s is not, it is just rare enough that no 2-second test
 * wins that lottery. The lock is there because the Java Memory Model does not promise the benign
 * version, and because a future change to any of these structures -- an {@code ArrayList} where a
 * reader iterates, a two-phase write -- turns "usually fine" into a production bug with no warning.
 *
 * <p>So: a canary for the shape, and a check that readers and writers can actually run at the same
 * time without the engine throwing, corrupting counts, or dropping a document that was already there.
 */
class ConcurrencyTest {

    @Test
    @DisplayName("读写并发压 1.5 秒：引擎不抛异常，已写入的文档不会读丢")
    void searchesStayCorrectWhileTheIndexIsBeingWritten() throws Exception {
        Engine engine = Engine.build(Corpus.loadDemo(), new Engine.Options().mining(false).vectors(false));

        // 400 documents with 300 fresh terms each: the writers keep inserting brand-new keys, so the
        // postings maps resize underneath the readers for the whole run. Steady-state writes without
        // resizes hide the race instead of testing it.
        for (int i = 0; i < 400; i++) {
            engine.add("seed-" + i, "u" + i, "并发种子标题" + i, filler("s" + i, 300), "");
        }
        // One latin-only token that no writer ever emits, so "exactly one hit" is a fact about the
        // index rather than a moving target: if a reader ever sees anything else, the read tore.
        String probe = "zqkxpluniq";
        engine.add("probe", "up", "探针", probe + " 倒排索引", "");
        int expected = 1;

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(6);
        List<Thread> threads = new java.util.ArrayList<>();

        for (int r = 0; r < 6; r++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                awaitQuietly();
                while (running.get()) {
                    try {
                        // (1) A term every document contains, so its posting list is being appended to
                        // while this walk reads docs[]/posStart[]/pool[]. This is the half that can
                        // actually tear: an ArrayIndexOutOfBoundsException, or a hit count of zero.
                        Searcher.Result shared = engine.searcher()
                                .search("倒排索引", Searcher.Mode.BM25, 10, 0, false, false);
                        assertTrue(shared.hits().size() > 0, "thousands of documents contain this term");
                        // (2) A term only one document contains: its answer cannot legitimately move,
                        // so a lost entry in the term map shows up here as a missing hit.
                        Searcher.Result lone = engine.searcher()
                                .search(probe, Searcher.Mode.BM25, 10, 0, false, true);
                        assertEquals(expected, lone.hits().size(),
                                "the probed term lives in exactly one document, and no writer touches it");
                        engine.stats();
                        engine.numDocs();
                        reads.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                        running.set(false);
                        return;
                    }
                }
            }, "reader-" + r);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int w = 0; w < 2; w++) {
            final int slot = w;
            Thread t = new Thread(() -> {
                int n = 0;
                while (running.get()) {
                    try {
                        String id = "w" + slot + "-" + (n++);
                        engine.add(id, "u" + id, "并发写入" + id, filler(id, 300), "");
                        if (n % 40 == 0) engine.refreshVectors();
                        engine.delete("w" + slot + "-" + Math.max(0, n - 200));
                        writes.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                        running.set(false);
                        return;
                    }
                }
            }, "writer-" + w);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        Thread.sleep(1_500);
        running.set(false);
        for (Thread t : threads) t.join(5_000);

        assertNull(failure.get(), () -> "concurrent read/write broke the engine: " + failure.get());
        assertTrue(reads.get() > 50, "the readers must actually have run, got " + reads.get());
        assertTrue(writes.get() > 20, "the writers must actually have run, got " + writes.get());
        // Deleting while searching must not strand a hit either: the probe is still there.
        assertEquals(expected, engine.searcher().search(probe, Searcher.Mode.BM25, 10, 0, false, true)
                .hits().size());
    }

    private static String filler(String salt, int terms) {
        StringBuilder sb = new StringBuilder("倒排索引 与 分词 在 写入 时 也 在 被 读取 ");
        for (int j = 0; j < terms; j++) sb.append("并发填充").append(salt).append('c').append(j).append(' ');
        return sb.toString();
    }

    private static void awaitQuietly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
