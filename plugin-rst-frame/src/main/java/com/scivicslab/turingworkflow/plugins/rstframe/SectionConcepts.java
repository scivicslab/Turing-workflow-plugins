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
 * showing its number, title and its sentences numbered as its rule file (step 3) has them, asking for the concepts it
 * explains to its reader, one per line as {@code term | sentence numbers}, the most central first. The numbers let
 * step 5 judge a concept by the role of the sentences that explain it, whatever words they use. The answer is read
 * line by line; bullets, numbering and remarks in brackets are taken off, repeats dropped, and at most {@link #MAX} kept.
 */
public final class SectionConcepts {

    /** The most concepts kept for one section. */
    public static final int MAX = 15;
    /** The most characters of a section's text shown in the question. */
    static final int MAX_TEXT = 12000;

    private SectionConcepts() {
    }

    /** @return the question for one section, its sentences numbered from 1 in the order of {@code units} */
    public static String question(BookOutline.Node node, List<ConceptImportance.Unit> units) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            ConceptImportance.Unit u = units.get(i);
            String one = u.role().equals("code") ? "(code)\n" + u.text().strip() : u.text().strip().replaceAll("\\s+", " ");
            text.append('[').append(i + 1).append("] ").append(one).append('\n');
            if (text.length() > MAX_TEXT) {
                text.append("...\n");
                break;
            }
        }
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its sentences numbered.\n\n"
                + text + "\n"
                + "List the concepts this section explains to its reader: the things or ideas the reader learns about here, such as "
                + "a term it defines, a feature of the language or of its library that it describes, or a technique it teaches. "
                + "Leave out what it only mentions in passing, and what it uses only as an illustration (a class written for an "
                + "example, a sample value). Name each concept as a term, the way the index at the back of the book would list it: "
                + "\"integer literal\" or \"unsigned integer\", not \"Lack of unsigned integer types in Java\". A concept is the thing "
                + "itself, not a statement about it. After each term write \" | \" and the numbers of the sentences that explain it, "
                + "whatever words they use, for example \"integer literal | 3, 4\". Write one term per line, in English, the most "
                + "central first. Write nothing else.";
    }

    /** @return the concepts read from a reply, in its order, without bullets, numbering, bracketed remarks or repeats */
    public static List<String> read(String reply) {
        List<String> out = new ArrayList<>();
        for (BookStore.ListedConcept c : readListed(reply, Integer.MAX_VALUE)) out.add(c.concept());
        return out;
    }

    /**
     * @param sentences how many sentences the question showed; a number past it is dropped
     * @return the concepts read from a reply, each with the sentence numbers after its {@code |} ({@code null} when the
     *         line has no {@code |}), in its order, without bullets, numbering, bracketed remarks or repeats
     */
    public static List<BookStore.ListedConcept> readListed(String reply, int sentences) {
        Set<String> seen = new LinkedHashSet<>();
        List<BookStore.ListedConcept> out = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            int bar = line.indexOf('|');
            String term = bar < 0 ? line : line.substring(0, bar);
            String c = term.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "")
                    .replaceAll("\\s*\\([^)]*\\)\\s*$", "")      // a remark in brackets: "Lambda expressions (implicit in the code)"
                    .replaceAll("^[`*\"']+|[`*\"'.]+$", "").replace("`", "").strip();
            if (c.isEmpty() || c.length() > 120 || c.endsWith(":")) continue;
            List<Integer> at = null;
            if (bar >= 0) {
                at = new ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(line.substring(bar + 1));
                while (m.find()) {
                    int n = Integer.parseInt(m.group());
                    if (n >= 1 && n <= sentences && !at.contains(n)) at.add(n);
                }
            }
            if (seen.add(c.toLowerCase(Locale.ROOT))) out.add(new BookStore.ListedConcept(c, at));
            if (out.size() == MAX) break;
        }
        return out;
    }

    /**
     * Asks for the concepts of every section of the book that has a rule file and no concepts listed with their
     * sentences yet ({@code only} empty), or of the sections named, {@code parallel} at a time, and stores each as soon
     * as it is read. A section whose rule file holds no sentence is stored with no concept. A section that fails is reported and left out, so a run started again goes on with it.
     *
     * @return how many were listed and how many failed, in one line
     */
    public static String listAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new java.util.LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Set<String> done = new java.util.HashSet<>();
        for (Map.Entry<String, List<BookStore.ListedConcept>> e : store.listedConcepts(book).entrySet()) {
            if (e.getValue().stream().allMatch(c -> c.sentences() != null)) done.add(e.getKey());
        }
        Set<String> withRules = store.sectionsWithRules(book);
        List<BookSections.Section> todo = new ArrayList<>();
        for (BookSections.Section s : store.sections(book)) {
            if (!withRules.contains(s.number())) continue;
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
                        BookStore.SectionRules r = store.sectionRules(book, s.number());
                        List<ConceptImportance.Unit> units = ConceptImportance.units(r.rules(), r.code());
                        List<BookStore.ListedConcept> concepts = units.isEmpty() ? List.of()
                                : readListed(writer.apply(question(nodes.get(s.number()), units)), units.size());
                        store.saveListedConcepts(book, s.number(), concepts);
                        log.accept("done " + listed.incrementAndGet() + "/" + todo.size() + " " + s.number() + ": "
                                + String.join("; ", concepts.stream().map(c -> c.concept() + " " + c.sentences()).toList()));
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
