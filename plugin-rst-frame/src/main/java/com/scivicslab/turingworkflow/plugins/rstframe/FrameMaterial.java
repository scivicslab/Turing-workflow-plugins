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
 * Identifies the concepts in a section worth collecting rules for and the specific rules that describe their
 * properties and behaviors.
 *
 * <ul>
 * <li>{@link #candidates(List, List)} returns a map of concepts to their constituent parts, features, or operations,
 * where each concept is either a primary subject or an owner of related sub-concepts.</li>
 * <li>{@link #question(BookOutline.Node, List, Map)} returns a formatted text prompt for a local LLM that lists the
 * section's sentences and the target concepts.</li>
 * <li>{@link #read(String, List, List)} returns a map of concepts to their extracted {@link BookStore.FrameRule}s
 * based on a model's text response.</li>
 * <li>{@link #collectAll(BookStore, String, List, Function, int, Consumer)} collects rules for sections that currently
 * lack them, using a writer to ask a model and a store to save the results.</li>
 * <li>{@link #pruneAll(BookStore, String, int)} removes concepts from the store that have fewer than the specified
 * minimum number of rules.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class FrameMaterial {

    private FrameMaterial() {
    }

    /**
     * Identifies the concepts in a section that are worth collecting rules for, grouping them by their primary
     * concept.
     *
     * <p>The map keys are the primary concepts, and the values are lists of their subordinate features, parts, or
     * operations. A concept is included if it is in the section title, is a parent of a relation found after step 7,
     * or is a kind of another concept. Subordinate items are folded into their owners: a feature, part, or operation
     * is listed under its parent concept unless that subordinate item is itself a primary concept in the section. If a
     * subordinate item is not listed as a primary concept, it is listed on its own account.</p>
     *
     * @param judged the list of concepts in the section that have been judged for importance
     * @param relations the list of relations found in the section's rule file
     * @return a map where each key is a primary concept and the value is a list of its subordinate features, parts, or
     * operations
     */
    public static Map<String, List<String>> candidates(List<BookStore.JudgedConcept> judged, List<BookStore.Relation> relations) {
        Set<String> own = new LinkedHashSet<>();
        Map<String, String> ownerOf = new LinkedHashMap<>();
        for (BookStore.Relation r : relations) {
            BookStore.Relation after = r.reviewed();
            if (after == null) continue;
            own.add(after.parent());
            // a kind, or a concept related another way (a way of doing the parent, a use of it), is a concept of its own;
            // a feature, a part or an operation is the parent's
            if (after.kind().equals(BookStore.Relation.IS_A) || after.kind().equals(BookStore.Relation.OTHER)) own.add(after.child());
            else ownerOf.putIfAbsent(after.child(), after.parent());
        }
        Set<String> kept = new LinkedHashSet<>();
        for (BookStore.JudgedConcept c : judged) {
            if (Boolean.FALSE.equals(c.kept())) continue;
            kept.add(c.concept());
            if (c.reason() != null && c.reason().startsWith("in the title")) own.add(c.concept());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String c : kept) if (own.contains(c)) out.put(c, new ArrayList<>());
        for (String c : kept) {
            if (out.containsKey(c) || !ownerOf.containsKey(c)) continue;
            // up the chain of owners to the first that is listed: a part of a part of String is String's
            String owner = ownerOf.get(c);
            Set<String> seen = new LinkedHashSet<>();
            while (owner != null && !out.containsKey(owner) && seen.add(owner)) owner = ownerOf.get(owner);
            if (owner != null && out.containsKey(owner)) out.get(owner).add(c);
            else out.put(c, new ArrayList<>());
        }
        return out;
    }

    /**
     * Returns a text prompt that instructs a model to identify the properties and behaviors of specific concepts
     * within a textbook section.
     *
     * The prompt includes the section's title and number, the numbered text of the section (with code blocks
     * formatted), and a list of target concepts to be analyzed. It instructs the model to categorize sentences as
     * either property (what the concept is or has) or behavior (what the concept does or can be done with) and to
     * format the response as "concept | kind | sentence numbers".
     *
     * @param node the section's position and title in the book's outline
     * @param units the list of text blocks (sentences or code) that make up the section
     * @param concepts the map of target concepts to their associated features, parts, or operations
     * @return a formatted string containing the section text, the list of concepts, and the instructions for the model
     */
    public static String question(BookOutline.Node node, List<ConceptImportance.Unit> units, Map<String, List<String>> concepts) {
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
        for (Map.Entry<String, List<String>> c : concepts.entrySet()) {
            list.append("- ").append(c.getKey());
            if (!c.getValue().isEmpty()) list.append("   (with its features, parts and operations: ").append(String.join(", ", c.getValue())).append(')');
            list.append('\n');
        }
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its sentences numbered.\n\n"
                + text + "\nThe concepts it explains are:\n" + list + "\n"
                + "A sentence about a feature, a part or an operation listed with a concept tells about that concept: \"The length method yields the "
                + "number of char values\" tells a behavior of String when String is listed with String.length.\n"
                + "For each concept, find the sentences that tell the reader about it, and say for each whether it tells\n"
                + "property   what the concept is or has: its definition, its parts, its values, its rules (\"A string is a sequence of Unicode characters.\")\n"
                + "behavior   what the concept does, or what a program can do with it: its operations, methods, uses (\"The substring method extracts a part of a string.\")\n"
                + "Write one line for each, as \"concept | property | sentence numbers\" or \"concept | behavior | sentence numbers\", "
                + "for example \"String | property | 1, 2\". Use the concepts exactly as listed. Leave out a sentence that only "
                + "mentions a concept, or that gives an example, a comparison or a remark. Write nothing else.";
    }

    /**
     * Parses a text reply from a model into a mapping of concepts to their extracted rules.
     *
     * @param reply the text containing lines of the form "concept | property | sentence numbers" or "concept |
     * behavior | sentence numbers"
     *
     * @param concepts the list of concept names to look for in the reply
     * @param units the list of units used to resolve sentence numbers into rules
     * @return a map where each key is a concept from {@code concepts} and the value is a list of its properties and
     * behaviors found in the reply; a concept that is not mentioned in the reply maps to an empty list
     */
    public static Map<String, List<BookStore.FrameRule>> read(String reply, List<String> concepts, List<ConceptImportance.Unit> units) {
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String c : concepts) byLower.put(c.strip().toLowerCase(Locale.ROOT), c);
        Map<String, List<BookStore.FrameRule>> out = new LinkedHashMap<>();
        for (String c : concepts) out.put(c, new ArrayList<>());
        for (String line : reply.split("\\R")) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            String concept = byLower.get(p[0].strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").replaceAll("^[`*\"']+|[`*\"'.]+$", "")
                    .strip().toLowerCase(Locale.ROOT));
            String kind = p[1].strip().toLowerCase(Locale.ROOT);
            if (concept == null || !(kind.equals(BookStore.FrameRule.PROPERTY) || kind.equals(BookStore.FrameRule.BEHAVIOR))) continue;
            Matcher m = Pattern.compile("\\d+").matcher(String.join("|", java.util.Arrays.copyOfRange(p, 2, p.length)));
            while (m.find()) {
                int n = Integer.parseInt(m.group());
                if (n < 1 || n > units.size()) continue;
                ConceptImportance.Unit u = units.get(n - 1);
                BookStore.FrameRule r = new BookStore.FrameRule(kind, u.rule() == null ? "[" + n + "]" : u.rule(), u.text().strip());
                if (out.get(concept).stream().noneMatch(x -> x.rule().equals(r.rule()) && x.kind().equals(r.kind()))) out.get(concept).add(r);
            }
        }
        return out;
    }

    /**
     * Asks a writer to extract properties and behaviors for the concepts in every section of a book that currently
     * lacks material or is specified in {@code only}, and saves the results to the {@code store}.
     *
     * @param store the database to read from and write to
     * @param book the name of the book to process
     * @param only a list of section numbers to process; if empty, all sections lacking material are processed
     * @param writer a function that takes a question string and returns the reply string
     * @param parallel the maximum number of sections to process at a time
     * @param log a consumer for progress and error messages
     * @return a string stating how many sections were asked, how many rules were collected, and how many failed
     * @throws Exception if an error occurs during the processing of a section
     */
    public static String collectAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<BookStore.JudgedConcept>> judged = store.judgedConcepts(book);
        Map<String, List<BookStore.Relation>> relations = store.relations(book);
        Set<String> done = store.frameConcepts(book).keySet();
        Set<String> withRules = store.sectionsWithRules(book);
        Map<String, Map<String, List<String>>> todo = new LinkedHashMap<>();
        for (Map.Entry<String, List<BookStore.JudgedConcept>> e : judged.entrySet()) {
            if (!withRules.contains(e.getKey())) continue;
            if (only.isEmpty() ? done.contains(e.getKey()) : !only.contains(e.getKey())) continue;
            Map<String, List<String>> c = candidates(e.getValue(), relations.getOrDefault(e.getKey(), List.of()));
            if (!c.isEmpty()) todo.put(e.getKey(), c);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger collected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<String>>> e : todo.entrySet()) {
                fs.add(pool.submit(() -> {
                    String number = e.getKey();
                    try {
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<ConceptImportance.Unit> units = ConceptImportance.units(r.rules(), r.code());
                        List<String> names = new ArrayList<>(e.getValue().keySet());
                        Map<String, List<BookStore.FrameRule>> got = read(writer.apply(question(nodes.get(number), units, e.getValue())), names, units);
                        List<BookStore.FrameConcept> list = new ArrayList<>();
                        for (String c : names) list.add(new BookStore.FrameConcept(c, got.get(c), null, e.getValue().get(c)));
                        store.saveFrameConcepts(book, number, list);
                        int n = got.values().stream().mapToInt(List::size).sum();
                        collected.addAndGet(n);
                        log.accept("done " + asked.incrementAndGet() + "/" + todo.size() + " " + number + ": "
                                + String.join("; ", list.stream().map(x -> x.concept() + " " + x.rules().size()).toList()));
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + number + ": " + ex.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return asked.get() + " section(s) of " + book + " asked, " + collected.get() + " rule(s) collected, " + failed.get() + " failed";
    }

    /**
     * Removes concepts from the stored frame data of a book if they have fewer than min rules.
     *
     * <p>Returns a string stating how many sections of the book were processed, how many concepts remain, and how many
     * were removed. A min of 0 has no effect.
     *
     * @param store the H2 database containing the book's frame data
     * @param book the name of the book to process
     * @param min the minimum number of rules a concept must have to stay
     * @return a string in the form "N section(s) of <book>: K concept(s) stay, M taken off (fewer than min rule(s))"
     * @throws Exception if a database error occurs</p>
     */
    public static String pruneAll(BookStore store, String book, int min) throws Exception {
        int kept = 0, off = 0, sections = 0;
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            List<BookStore.FrameConcept> list = new ArrayList<>();
            for (BookStore.FrameConcept c : e.getValue()) {
                boolean stays = c.rules().size() >= min;
                if (stays) kept++; else off++;
                list.add(new BookStore.FrameConcept(c.concept(), c.rules(), stays, c.folded()));
            }
            store.saveFrameConcepts(book, e.getKey(), list);
            sections++;
        }
        return sections + " section(s) of " + book + ": " + kept + " concept(s) stay, " + off + " taken off (fewer than " + min + " rule(s))";
    }
}
