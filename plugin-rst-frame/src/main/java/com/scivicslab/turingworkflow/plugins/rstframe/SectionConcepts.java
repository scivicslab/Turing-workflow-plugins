package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The concepts each section of a book explains ({@code FrameOntology_261021_oo01}, step 4): one question per section,
 * showing its number, title and text, asking for the concepts it explains to its reader, one short noun phrase per
 * line, the most central first. The answer is read line by line; bullets and numbering are taken off, repeats dropped,
 * and at most {@link #MAX} kept.
 */
public final class SectionConcepts {

    /** The most concepts kept for one section. */
    public static final int MAX = 15;
    /** The most characters of a section's text shown in the question. */
    static final int MAX_TEXT = 12000;

    private SectionConcepts() {
    }

    /** @return the question for one section */
    public static String question(BookOutline.Node node, BookSections.Section section) {
        String text = section.text().length() > MAX_TEXT ? section.text().substring(0, MAX_TEXT) + "\n..." : section.text();
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\".\n\n" + text + "\n\n"
                + "List the concepts this section explains to its reader: the things or ideas the reader learns about here, such as "
                + "a term it defines, a feature of the language or of its library that it describes, or a technique it teaches. "
                + "Leave out what it only mentions in passing, and what it uses only as an illustration (a class written for an "
                + "example, a sample value). Name each concept as a term, the way the index at the back of the book would list it: "
                + "\"integer literal\" or \"unsigned integer\", not \"Lack of unsigned integer types in Java\". A concept is the thing "
                + "itself, not a statement about it. Write one term per line, in English, the most central first. Write nothing else.";
    }

    /** @return the concepts read from a reply, in its order, without bullets, numbering or repeats */
    public static List<String> read(String reply) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String c = line.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").replaceAll("^[`*\"']+|[`*\"'.]+$", "").strip();
            if (c.isEmpty() || c.length() > 120 || c.endsWith(":")) continue;
            if (seen.add(c.toLowerCase(Locale.ROOT))) out.add(c);
            if (out.size() == MAX) break;
        }
        return out;
    }

    /**
     * Asks for the concepts of every section of the book that has none yet ({@code only} empty), or of the sections
     * named, {@code parallel} at a time, and stores each as soon as it is read. A section with no text of its own is
     * stored with no concept. A section that fails is reported and left out, so a run started again goes on with it.
     *
     * @return how many were listed and how many failed, in one line
     */
    public static String listAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new java.util.LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Set<String> done = store.concepts(book).keySet();
        List<BookSections.Section> todo = new ArrayList<>();
        for (BookSections.Section s : store.sections(book)) {
            if (only.isEmpty() ? !done.contains(s.number()) : only.contains(s.number())) todo.add(s);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger listed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (BookSections.Section s : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<String> concepts = s.text().isBlank() ? List.of() : read(writer.apply(question(nodes.get(s.number()), s)));
                        store.saveConcepts(book, s.number(), concepts);
                        log.accept("done " + listed.incrementAndGet() + "/" + todo.size() + " " + s.number() + ": " + String.join("; ", concepts));
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
        return listed.get() + " section(s) of " + book + " listed, " + failed.get() + " failed";
    }
}
