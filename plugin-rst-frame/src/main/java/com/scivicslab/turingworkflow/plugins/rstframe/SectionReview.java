package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Generates questions for a local LLM to review the relations between concepts in a textbook section and processes the
 * resulting verdicts.
 *
 * <ul>
 * <li>{@code question} returns a text block containing the section's title, numbered sentences, the concepts it
 * explains, and a list of relations (e.g., {@code R1: A | kind | B}) for the LLM to judge.</li>
 * <li>{@code read} returns a list of verdicts for the given number of relations, where each verdict is one of {@link
 * BookStore.Relation} values.</li>
 * <li>{@code reviewAll} reviews the specified sections in a book, either all unreviewed sections or a specific subset,
 * and saves the results to the provided {@link BookStore}.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <p>Example of a workflow call to review a book:</p>
 * <pre>{@code
 * reviewAll(store, "core-java-14-vol1", List.of(), llm::ask, 4, log::info)
 * }</pre>
 *
 * <p>If a section fails during {@code reviewAll}, the error is logged and the section is skipped, allowing the process
 * to continue with the remaining sections.</p>
 */
public final class SectionReview {

    private static final Set<String> VERDICTS = Set.of(BookStore.Relation.KEEP, BookStore.Relation.IS_A, BookStore.Relation.PART_OF,
            BookStore.Relation.FEATURE, BookStore.Relation.OPERATION, BookStore.Relation.OTHER, BookStore.Relation.REVERSE, BookStore.Relation.DROP);

    private SectionReview() {
    }

    /**
     * Returns a text prompt that asks a human or LLM to review the relations between concepts in a specific textbook
     * section.
     *
     * The returned string contains the section's number and title, the text of the section's units (numbered), the
     * list of concepts explained in the section, and the list of existing relations (each prefixed with R1, R2, etc.)
     * to be judged. The prompt includes instructions for the user to provide a verdict for each relation using
     * specific keywords: {@code keep}, {@code is-a}, {@code part-of}, {@code feature}, {@code operation}, {@code
     * other}, {@code reverse}, or {@code drop}.
     */
    public static String question(BookOutline.Node node, List<ConceptImportance.Unit> units, List<String> concepts,
            List<BookStore.Relation> relations) {
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
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < relations.size(); i++) {
            BookStore.Relation r = relations.get(i);
            rel.append("R").append(i + 1).append(": ").append(r.child()).append(" | ").append(r.kind()).append(" | ").append(r.parent());
            if (r.sentences() != null && !r.sentences().isEmpty()) {
                rel.append("   (sentences ").append(String.join(", ", r.sentences().stream().map(String::valueOf).toList())).append(')');
            }
            rel.append('\n');
        }
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its sentences numbered.\n\n"
                + text + "\nThe concepts it explains are: " + String.join("; ", concepts) + "\n\n"
                + "Someone read the section and wrote these relations between its concepts. \"A | is-a | B\" says A is a kind of B; "
                + "\"A | part-of | B\" says A is a part or a component of B; \"A | feature | B\" says A is a property or an attribute of B; "
                + "\"A | operation | B\" says A is an operation or a method that acts on B; "
                + "\"A | other | B\" says they are related in another way (A is a way of doing B, a use of B, a rule about B).\n"
                + rel + "\n"
                + "Check each relation against the section and against what the terms mean. For each, write one line: its number, \" | \", "
                + "and one of these words:\n"
                + "keep      the relation is right\n"
                + "is-a, part-of, feature, operation or other   the two concepts are related, but in that way instead; other when it is none of the four\n"
                + "reverse   the relation is right but runs the other way (B is the kind, part or feature of A)\n"
                + "drop      the two concepts are not related so, or one does not belong to the other at all\n"
                + "Check a relation marked other hardest: keep it other only when it is none of the four. When A is a kind or a "
                + "form of B, write is-a (\"Int conversion | other | binary operand conversion\" is is-a); a part or a component, part-of; "
                + "a property, a rule or a variant of B's use, feature (\"snippet replacement | other | code snippets\" is feature); "
                + "an operation, a method or a constructor that acts on B or is called on it, operation (\"BigInteger constructor | other | "
                + "BigInteger\" and \"Scanner.hasNextInt | other | console input\" are operation). When A and B are the same thing under "
                + "two names (\"casting\" and \"cast\"), write drop.\n"
                + "For example \"R1 | keep\", \"R2 | is-a\", \"R3 | drop\". Write nothing else.";
    }

    /**
     * Returns a list of verdicts for the relations, where each verdict is one of the allowed words or {@code keep} if
     * the reply says nothing of a relation.
     *
     * The list contains exactly {@code count} elements, where the element at index {@code n - 1} corresponds to the
     * relation numbered {@code n}.
     *
     * A verdict is a success if it is one of the words specified in {@link #question(BookOutline.Node, List, List,
     * List)}, otherwise the relation is treated as {@code keep}.
     *
     * @param reply the text from the LLM containing the numbered verdicts
     * @param count the number of relations to process
     * @return a list of {@code count} strings, each being a verdict or {@code keep}
     */
    /** Returns whether the reply has at least one line giving relation 1..count one of the verdicts. */
    static boolean givesAVerdict(String reply, int count) {
        Matcher m = Pattern.compile("(?im)^\\W*R?(\\d+)\\s*[|:.)\\-]+\\s*([a-z][a-z _-]*)").matcher(reply);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String v = m.group(2).strip().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
            if (v.startsWith("part-of")) v = BookStore.Relation.PART_OF;
            if (n >= 1 && n <= count && VERDICTS.contains(v)) return true;
        }
        return false;
    }

    public static List<String> read(String reply, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(BookStore.Relation.KEEP);
        Matcher m = Pattern.compile("(?im)^\\W*R?(\\d+)\\s*[|:.)\\-]+\\s*([a-z][a-z _-]*)").matcher(reply);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String v = m.group(2).strip().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
            if (v.equals("part-of-") || v.startsWith("part-of")) v = BookStore.Relation.PART_OF;
            if (n >= 1 && n <= count && VERDICTS.contains(v)) out.set(n - 1, v);
        }
        return out;
    }

    /**
     * Reviews the relations of textbook sections by asking a writer to verify them, and saves the results to the
     * provided store.
     *
     * <p>Returns a summary string stating how many sections were reviewed, a tally of the verdicts (e.g., {@code
     * keep}, {@code is-a}, {@code drop}), and how many sections failed.
     *
     * <p>If {@code only} is empty, it reviews only those sections that have relations but no existing reviews;
     * otherwise, it reviews only the sections whose numbers are in {@code only}.
     *
     * <p>A section that causes an error during the review process is logged and skipped, so the caller can retry the
     * run to address the failure.
     *
     * @param store the database to read sections and relations from, and to save the verdicts into
     * @param book the name of the book to process
     * @param only a list of section numbers to review; if empty, all unreviewed sections with relations are processed
     * @param writer a function that takes the generated question string and returns the reply string
     * @param parallel the maximum number of sections to review simultaneously
     * @param log a consumer for progress and error messages
     * @return a summary of the number of sections reviewed, the counts of each verdict type, and the number of
     * failures
     */
    public static String reviewAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<BookStore.Relation>> all = store.relations(book);
        Map<String, List<BookStore.JudgedConcept>> judged = store.judgedConcepts(book);
        List<String> todo = new ArrayList<>();
        for (Map.Entry<String, List<BookStore.Relation>> e : all.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            boolean reviewed = e.getValue().stream().allMatch(r -> r.review() != null);
            if (only.isEmpty() ? !reviewed : only.contains(e.getKey())) todo.add(e.getKey());
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        BatchFailures failures = new BatchFailures();
        Map<String, AtomicInteger> tally = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<BookStore.Relation> rel = all.get(number);
                        List<String> concepts = judged.getOrDefault(number, List.of()).stream().filter(c -> !Boolean.FALSE.equals(c.kept()))
                                .map(BookStore.JudgedConcept::concept).toList();
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<ConceptImportance.Unit> units = ConceptImportance.units(r.rules(), r.code());
                        String reply = writer.apply(question(nodes.get(number), units, concepts, rel));
                        // read() leaves a relation with no verdict kept; a reply with no verdict at all is not a review
                        if (!givesAVerdict(reply, rel.size())) throw new IllegalStateException("the reply gives no verdict on any relation");
                        List<String> verdicts = read(reply, rel.size());
                        store.saveReview(book, number, verdicts);
                        for (String v : verdicts) tally.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + String.join(", ", verdicts));
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
        return failures.outcome(todo.size(), done.get() + " section(s) of " + book + " reviewed: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed");
    }
}
