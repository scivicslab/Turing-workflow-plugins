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
 * A simple review of step 6 ({@code FrameOntology_261021_oo01}, step 7): one question per section, showing its
 * sentences numbered, its concepts, and the relations step 6 found, numbered {@code R1}, {@code R2}, ..., asking for
 * each whether it stands ({@code keep}), is of another kind ({@code is-a}, {@code part-of}, {@code feature}, {@code operation}, {@code other}), runs the
 * other way ({@code reverse}), or is wrong ({@code drop}). A relation the reply says nothing of is kept.
 */
public final class SectionReview {

    private static final Set<String> VERDICTS = Set.of(BookStore.Relation.KEEP, BookStore.Relation.IS_A, BookStore.Relation.PART_OF,
            BookStore.Relation.FEATURE, BookStore.Relation.OPERATION, BookStore.Relation.OTHER, BookStore.Relation.REVERSE, BookStore.Relation.DROP);

    private SectionReview() {
    }

    /** @return the question for one section */
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
                + "For example \"R1 | keep\", \"R2 | is-a\", \"R3 | drop\". Write nothing else.";
    }

    /** @return the verdict on each of {@code count} relations, {@code keep} for one the reply says nothing of */
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
     * Reviews the relations of every section that has some and none reviewed yet ({@code only} empty), or of the
     * sections named, {@code parallel} at a time, and stores the verdicts as soon as each is read. A section that fails
     * is reported and left out, so a run started again goes on with it.
     *
     * @return how many sections were reviewed and what was made of their relations, in one line
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
                        List<String> verdicts = read(writer.apply(question(nodes.get(number), units, concepts, rel)), rel.size());
                        store.saveReview(book, number, verdicts);
                        for (String v : verdicts) tally.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + String.join(", ", verdicts));
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + number + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " section(s) of " + book + " reviewed: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed";
    }
}
