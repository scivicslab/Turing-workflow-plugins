package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Each section of a book, as {@code book-sections.yaml} cut it, made into an rst-on-lisp rule file
 * ({@code FrameOntology_261021_oo01}, step 3). The section's markdown goes through {@link BookFileToRules}: the sentences
 * are the book's own, and a writer is asked only each paragraph's OCR misreadings and how its sentences relate. The
 * section's number and title are put in front as its heading, so the rule file's title is the section's.
 */
public final class SectionTagging {

    private SectionTagging() {
    }

    /** @return the converter for one section, ready to be asked its questions */
    public static BookFileToRules converter(String book, BookOutline.Node node, BookSections.Section section) {
        String text = "## " + node.number() + ". " + node.title() + "\n\n" + section.text() + "\n";
        String id = ("Book_" + book + "_" + node.number()).replaceAll("[^A-Za-z0-9]+", "_");
        return new BookFileToRules(text, "h2:" + book + "/" + node.number(), id);
    }

    /** Asks the writer every question of one section's converter and returns the rule file it made. */
    public static BookStore.SectionRules tag(String book, BookOutline.Node node, BookSections.Section section, Function<String, String> writer) {
        BookFileToRules c = converter(book, node, section);
        String q;
        while ((q = c.nextQuestion()) != null) c.answer(writer.apply(q));
        return new BookStore.SectionRules(node.number(), c.ruleFile(), c.codeFiles(), c.asked());
    }

    /**
     * Makes a rule file of every section of the book that has none yet ({@code only} empty), or of the sections named
     * in {@code only}, {@code parallel} at a time, and stores each as soon as it is made. A section that fails is
     * reported and left without a rule file, so a run started again goes on with it.
     *
     * @return how many were made and how many failed, in one line
     */
    public static String tagAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new java.util.LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        java.util.Set<String> done = store.sectionsWithRules(book);
        List<BookSections.Section> todo = new ArrayList<>();
        for (BookSections.Section s : store.sections(book)) {
            if (only.isEmpty() ? !done.contains(s.number()) : only.contains(s.number())) todo.add(s);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger made = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (BookSections.Section s : todo) {
                fs.add(pool.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        BookStore.SectionRules r = tag(book, nodes.get(s.number()), s, writer);
                        store.saveSectionRules(book, r);
                        log.accept("done " + made.incrementAndGet() + "/" + todo.size() + " " + s.number() + " (" + r.questions() + " questions, "
                                + (System.currentTimeMillis() - t0) / 1000 + "s)");
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + s.number() + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return made.get() + " section(s) of " + book + " made into rule files, " + failed.get() + " failed";
    }
}
