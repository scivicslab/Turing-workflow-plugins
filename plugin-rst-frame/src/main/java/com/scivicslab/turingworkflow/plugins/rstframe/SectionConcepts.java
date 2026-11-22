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
 * Generates questions for a local LLM to identify the concepts explained in a book section and parses the resulting
 * list of terms and their explanatory sentence numbers.
 *
 * <ul>
 * <li>{@code question(node, units)} returns a formatted string that asks the reader to list the concepts explained in
 * the section identified by {@code node}, using the sentences in {@code units} as numbered references.</li>
 * <li>{@code read(reply)} returns a list of concept names extracted from a text reply, stripping bullets, numbering,
 * and bracketed remarks.</li>
 * <li>{@code readListed(reply, sentences)} returns a list of {@link BookStore.ListedConcept} objects from a reply,
 * where each concept is paired with the specific sentence numbers following a {@code |} separator, provided they are
 * within the range 1 to {@code sentences}.</li>
 * <li>{@code listAll(store, book, only, writer, parallel, log)} asks for and stores the concepts for every section in
 * {@code book} that has a rule file and is either not yet fully listed or is explicitly named in {@code only}, running
 * {@code parallel} tasks at a time.</li>
 * </ul>
 *
 * <p>A single instance of this class is stateless and may be used by several workflows or threads at once.</p>
 *
 * <pre>{@code
 * String q = SectionConcepts.question(node, units);
 * List<BookStore.ListedConcept> concepts = SectionConcepts.readListed(reply, units.size());
 * }</pre>
 *
 * <p>If a reply contains no {@code |} separator, {@link #readListed(String, int)} returns the concept name with a null
 * list of sentence numbers. If a line is empty, too long, or ends in a colon, it is ignored.</p>
 */
public final class SectionConcepts {

    /** The maximum number of concepts kept for one section. */
    public static final int MAX = 15;
    /** The maximum number of characters of a section's text shown in the question. */
    static final int MAX_TEXT = 12000;

    private SectionConcepts() {
    }

    /**
     * Returns a prompt that asks a reader to list the concepts explained in a specific section, providing the
     * section's number and title, and the text of its units numbered from 1.
     *
     * @param node the section's node containing its number and title
     * @param units the list of units in the section, used to provide the numbered text and the upper bound for
     * sentence numbers
     */
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

    /**
     * Returns a list of concept names extracted from a reply, in the order they appeared.
     *
     * <p>The list contains only the concept terms, with bullets, numbering, bracketed remarks, and duplicate entries
     * removed. If a line in the reply does not contain a pipe character, the entire line is treated as a concept name.
     */
    public static List<String> read(String reply) {
        List<String> out = new ArrayList<>();
        for (BookStore.ListedConcept c : readListed(reply, Integer.MAX_VALUE)) out.add(c.concept());
        return out;
    }

    /**
     * Parses a reply from an LLM into a list of concepts and their associated sentence numbers.
     *
     * Returns a list of {@link BookStore.ListedConcept} objects, where each object contains a concept name and a list
     * of sentence numbers found after a {@code |} separator. If a line does not contain a {@code |}, the list of
     * sentence numbers is {@code null}.
     *
     * The list contains at most {@link #MAX} concepts, in the order they appeared in the reply. Bullets, numbering,
     * bracketed remarks, and duplicate concepts are removed. A sentence number is only included if it is at least 1
     * and at most the value of {@code sentences}.
     *
     * <p>
     * <pre>{@code
     * List<BookStore.ListedConcept> concepts = SectionConcepts.readListed(reply, 10);
     * }</pre>
     * </p>
     *
     * @param reply the text from the LLM to be parsed
     * @param sentences the total number of sentences shown in the question, used to bound the valid sentence numbers
     * @return a list of concepts and their sentence numbers in the order they were read
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
     * Processes and stores the concepts for the specified sections of a book by asking a writer for their names and
     * sentence numbers.
     *
     * <p>If {@code only} is empty, it processes every section of {@code book} that has a rule file and currently has
     * no concepts listed (or only empty lists); otherwise, it processes only the sections named in {@code only}.
     *
     * <p>Returns a string stating how many sections were successfully listed and how many failed.
     *
     * <p>A section that fails is logged and skipped, so a subsequent call to this method will attempt it again.
     *
     * @param store the database to read from and write to
     * @param book the name of the book to process
     * @param only a list of specific section numbers to process; if empty, all sections without concepts are processed
     * @param writer a function that takes a question string and returns a reply string
     * @param parallel the number of sections to process at a time
     * @param log a consumer for progress and error messages
     * @return a string in the format "X section(s) of [book] listed, Y failed"
     * @throws Exception if an error occurs during the processing of a section
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
        BatchFailures failures = new BatchFailures();
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
                        failures.add(s.number(), e);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return failures.outcome(todo.size(), listed.get() + " section(s) of " + book + " listed, " + failed.get() + " failed");
    }
}
