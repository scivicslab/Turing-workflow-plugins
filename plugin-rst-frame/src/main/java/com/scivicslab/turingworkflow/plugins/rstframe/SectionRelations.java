package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates and records how concepts in a textbook section relate to one another by asking an LLM to identify specific
 * relational patterns.
 *
 * <ul><li><li>{@code question} returns a text prompt for a section that lists its sentences and the concepts it
 * explains, asking the LLM to describe relations in the form {@code A | relation | B | sentence
 * numbers}.</li><li>{@code read} parses an LLM's reply into a list of {@link BookStore.Relation} objects, matching
 * concept names regardless of case and filtering out invalid or duplicate relations.</li><li>{@code relateAll}
 * iterates through sections of a book, asks the LLM for relations, and saves the results to a {@link
 * BookStore}.</li></ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class SectionRelations {

    private static final List<String> KINDS = List.of(BookStore.Relation.IS_A, BookStore.Relation.PART_OF, BookStore.Relation.FEATURE,
            BookStore.Relation.OPERATION, BookStore.Relation.OTHER);

    private SectionRelations() {
    }

    /**
     * Returns a text prompt that asks an LLM to identify how the concepts in a section relate to one another.
     *
     * The text includes the section's title and number, a list of the section's sentences numbered from 1, and a list
     * of the concepts to be related. The prompt instructs the LLM to use one of five relation types—{@code is-a},
     * {@code part-of}, {@code feature}, {@code operation}, or {@code other}—and to append the sentence numbers that
     * justify each relation.
     *
     * <pre>{@code
     * String prompt = SectionRelations.question(node, units, concepts);
     * }</pre>
     */
    public static String question(BookOutline.Node node, List<ConceptImportance.Unit> units, List<String> concepts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            ConceptImportance.Unit u = units.get(i);
            String one = u.role().equals("code") ? "(code)\n" + u.text().strip() : u.text().strip().replaceAll("\\s+", " ");
            text.append('[').append(i + 1).append("] ").append(one).append('\n');
            if (text.length() > SectionConcepts.MAX_TEXT) {
                text.append("...\n");
                break;
            }
        }
        StringBuilder list = new StringBuilder();
        for (String c : concepts) list.append("- ").append(c).append('\n');
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its sentences numbered.\n\n"
                + text + "\nThe concepts it explains are:\n" + list + "\n"
                + "Say how these concepts relate to one another, as the section says. Write one line for each relation, in one of five forms:\n"
                + "A | is-a | B       when A is a kind of B (\"String literal | is-a | String\")\n"
                + "A | part-of | B    when A is a part or a component of B (\"Escape sequence | part-of | String\")\n"
                + "A | feature | B    when A is a property or an attribute of B (\"UTF-8 encoding | feature | String\")\n"
                + "A | operation | B  when A is an operation or a method that acts on B, something a program does with B "
                + "(\"containsValue method | operation | Map\", \"String.length | operation | String\")\n"
                + "A | other | B      when A and B are related in some other way: A is a way of doing B, a use of B, a rule about B "
                + "(\"String.join | other | string concatenation\")\n"
                + "Do not force a relation into the first four when it is none of them; write other.\n"
                + "After each line write \" | \" and the numbers of the sentences that say it, for example "
                + "\"String literal | is-a | String | 5, 8\". Use only the concepts listed, written exactly as listed. "
                + "Leave out a pair the section does not relate; when it relates none of them, write NONE. Write nothing else.";
    }

    /**
     * Parses a text reply into a list of relations between concepts.
     *
     * @param reply the text containing the relations, one per line
     * @param concepts the list of concept names the question provided; a name in the reply is matched to one of these
     * regardless of case
     *
     * @param sentences the number of sentences shown in the question; sentence numbers in the reply must be between 1
     * and this value inclusive
     *
     * @return a list of {@link BookStore.Relation} objects found in the reply, in the order they appeared, excluding
     * repeats, relations of a concept to itself, lines naming concepts not in the provided list, or relations of a
     * type not among the five allowed
     *
     * @implNote The method normalizes concept names by stripping bullets, numbers, quotes, and backticks, and ignores
     * case.
     */
    public static List<BookStore.Relation> read(String reply, List<String> concepts, int sentences) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String c : concepts) byLower.put(norm(c), c);
        Set<String> seen = new LinkedHashSet<>();
        List<BookStore.Relation> out = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            String child = byLower.get(norm(p[0]));
            String kind = p[1].strip().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
            String parent = byLower.get(norm(p[2]));
            if (child == null || parent == null || child.equals(parent) || !KINDS.contains(kind)) continue;
            List<Integer> at = new ArrayList<>();
            if (p.length > 3) {
                Matcher m = Pattern.compile("\\d+").matcher(String.join("|", java.util.Arrays.copyOfRange(p, 3, p.length)));
                while (m.find()) {
                    int n = Integer.parseInt(m.group());
                    if (n >= 1 && n <= sentences && !at.contains(n)) at.add(n);
                }
            }
            if (seen.add(kind + "\u0000" + child + "\u0000" + parent)) out.add(new BookStore.Relation(kind, child, parent, at));
        }
        return out;
    }

    /**
     * Normalizes a concept name by stripping whitespace, bullets, numbering, quotes, and backticks, and converting it
     * to lowercase.
     */
    private static String norm(String s) {
        return s.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").replaceAll("^[`*\"']+|[`*\"'.]+$", "").replace("`", "")
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Asks how the concepts in a book's sections relate and saves the results to the {@code BookStore}.
     *
     * <ul><li>If {@code only} is empty, it asks for relations in every section of {@code book} that has at least two
     * kept concepts, has rule files, and has not yet been asked; otherwise, it only asks for the sections listed in
     * {@code only}.</li><li>The results are written using {@code writer} and saved to the {@code store} immediately
     * after each section is processed.</li></ul>
     *
     * <p>The method runs up to {@code parallel} sections at a time. If a section fails, it is reported via {@code log}
     * and skipped, allowing the rest of the run to continue.
     *
     * @param store the database to read from and write to
     * @param book the name of the book to process
     * @param only a list of specific section numbers to process; if empty, all unasked sections are processed
     * @param writer a function that takes the question text and returns the text to be sent to the LLM
     * @param parallel the maximum number of sections to process simultaneously
     * @param log a consumer for progress and error messages
     * @return a string stating how many sections were asked, how many relations were found, and how many failed
     */
    public static String relateAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Set<String> done = store.relations(book).keySet();
        Map<String, List<BookStore.JudgedConcept>> judged = store.judgedConcepts(book);
        Set<String> withRules = store.sectionsWithRules(book);
        List<String> todo = new ArrayList<>();
        for (Map.Entry<String, List<BookStore.JudgedConcept>> e : judged.entrySet()) {
            long kept = e.getValue().stream().filter(c -> !Boolean.FALSE.equals(c.kept())).count();
            if (kept < 2 || !withRules.contains(e.getKey())) continue;
            if (only.isEmpty() ? !done.contains(e.getKey()) : only.contains(e.getKey())) todo.add(e.getKey());
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger found = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        BatchFailures failures = new BatchFailures();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<String> concepts = judged.get(number).stream().filter(c -> !Boolean.FALSE.equals(c.kept()))
                                .map(BookStore.JudgedConcept::concept).toList();
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<ConceptImportance.Unit> units = ConceptImportance.units(r.rules(), r.code());
                        String reply = writer.apply(question(nodes.get(number), units, concepts));
                        List<BookStore.Relation> rel = read(reply, concepts, units.size());
                        if (rel.isEmpty() && !Replies.isNone(reply)) throw new IllegalStateException("the reply states no relation and is not NONE");
                        store.saveRelations(book, number, rel);
                        found.addAndGet(rel.size());
                        log.accept("done " + asked.incrementAndGet() + "/" + todo.size() + " " + number + ": "
                                + String.join("; ", rel.stream().map(x -> x.child() + " " + x.kind() + " " + x.parent()).toList()));
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + number + ": " + e.getMessage());
                        failures.add(number, e);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return failures.outcome(todo.size(), asked.get() + " section(s) of " + book + " asked, " + found.get() + " relation(s) found, " + failed.get() + " failed");
    }
}
