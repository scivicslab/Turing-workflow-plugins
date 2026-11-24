package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A batch runs its items on virtual threads, at most {@code parallel} at once, and starts no real
 * (platform) thread of its own. {@code SectionTagging.tagAll} is the batch here; the other fifteen
 * batches use the same helper, {@link LimitedVirtualThreads}.
 */
class BatchOnVirtualThreadsTest {

    private static final int SECTIONS = 6;
    private static final int PARALLEL = 2;

    /** The real threads H2's MVStore starts for its background writes, by their exact names. */
    private static final java.util.Set<String> H2_WRITERS = java.util.Set.of("H2-serialization", "H2-save");

    private static String fair(String q) {
        if (q.contains("misread")) return q.substring(q.indexOf("The paragraph:\n") + 15, q.indexOf("\n\nParagraph after:"));
        long n = q.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
        StringBuilder sb = new StringBuilder("1: nucleus\n");
        for (int i = 2; i <= n; i++) sb.append(i).append(": elaboration-additional\n");
        return sb.toString();
    }

    /** Starts the JDK's virtual-thread carriers (real threads it keeps), so the count below sees only the batch's own. */
    private static void startCarriers() throws Exception {
        List<Thread> spinners = new ArrayList<>();
        for (int i = 0; i < 4 * Runtime.getRuntime().availableProcessors(); i++) {
            spinners.add(Thread.ofVirtual().start(() -> {
                long end = System.nanoTime() + 20_000_000L;
                while (System.nanoTime() < end) Thread.onSpinWait();
            }));
        }
        for (Thread t : spinners) t.join();
        // A virtual thread that sleeps starts the JDK's timer for virtual threads (ForkJoinPool-N-delayScheduler).
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).join();
    }

    @Test
    void itemsRunOnVirtualThreadsAtMostParallelAtOnce(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap04/p001");
        Files.createDirectories(b);
        StringBuilder md = new StringBuilder("## Chapter 4 ▪ Objects and Classes\n\nIn this chapter.\n\n");
        for (int i = 1; i < SECTIONS; i++) {
            md.append("## 4.1.").append(i).append(". Part ").append(i).append("\n\nA class is a template. Objects are made from it.\n\n");
        }
        Files.writeString(b.resolve("p001.md"), md.toString());
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");

        List<Boolean> virtual = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        java.util.Set<Long> idsBefore;
        java.util.List<String> newRealThreads;

        String summary;
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            // Counted from here: the store is open, and the JDK's virtual-thread carriers and timer are running.
            startCarriers();
            idsBefore = new java.util.HashSet<>();
            for (long id : threads.getAllThreadIds()) idsBefore.add(id);
            summary = SectionTagging.tagAll(store, "core", List.of(), q -> {
                virtual.add(Thread.currentThread().isVirtual());
                mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(100);   // the writer's wait, so that items overlap
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return fair(q);
            }, PARALLEL, line -> { });
            // Real threads that are new and still alive after the batch. Not counted: the JVM's own JIT compiler
            // threads, which it adds when it compiles more, and H2's two background writers, which H2 starts by
            // itself when it decides to write in the background; their exact names are H2's own.
            newRealThreads = new java.util.ArrayList<>();
            for (java.lang.management.ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
                if (info != null && !idsBefore.contains(info.getThreadId())
                        && !info.getThreadName().contains("CompilerThread")
                        && !H2_WRITERS.contains(info.getThreadName())) {
                    newRealThreads.add(info.getThreadName());
                }
            }
        }

        assertEquals(SECTIONS + " section(s) of core made into rule files, 0 failed", summary);
        assertTrue(virtual.size() >= SECTIONS, "the writer was asked " + virtual.size() + " times");
        assertTrue(virtual.stream().allMatch(v -> v), "every item ran on a virtual thread: " + virtual);
        assertEquals(PARALLEL, mostAtOnce.get(), "at most, and up to, " + PARALLEL + " items at once");
        assertTrue(newRealThreads.isEmpty(), "real threads started during the batch: " + newRealThreads);
    }
}
