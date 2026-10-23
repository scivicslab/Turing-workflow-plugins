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
 * The material of each section's concrete frame ({@code FrameOntology_261021_oo01}): the concepts worth collecting
 * rules for, and for each the rules of the section's rule file that say its properties (what it is, what it has) and
 * its behaviors (what it does, what can be done with it).
 *
 * <p>The list is a hypothesis to be tried ({@link #candidates}): the concepts that stand in a relation after step 7's
 * review, and those in the section's title. {@link #collectAll} asks the model, one question per section, which
 * sentences say a property or a behavior of each; {@link #pruneAll} then takes off the list a concept that got fewer
 * rules than asked for, since the section says little about it.</p>
 */
public final class FrameMaterial {

    private FrameMaterial() {
    }

    /**
     * @return the concepts of one section to collect rules for, without their hierarchy, in the order step 4 listed
     *         them: those step 5 kept that name a relation standing after step 7, or are in the title
     */
    public static List<String> candidates(List<BookStore.JudgedConcept> judged, List<BookStore.Relation> relations) {
        Set<String> related = new LinkedHashSet<>();
        for (BookStore.Relation r : relations) {
            BookStore.Relation after = r.reviewed();
            if (after == null) continue;
            related.add(after.child());
            related.add(after.parent());
        }
        List<String> out = new ArrayList<>();
        for (BookStore.JudgedConcept c : judged) {
            if (Boolean.FALSE.equals(c.kept())) continue;
            boolean title = c.reason() != null && c.reason().startsWith("in the title");
            if (title || related.contains(c.concept())) out.add(c.concept());
        }
        return out;
    }

    /** @return the question for one section */
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
                + "For each concept, find the sentences that tell the reader about it, and say for each whether it tells\n"
                + "property   what the concept is or has: its definition, its parts, its values, its rules (\"A string is a sequence of Unicode characters.\")\n"
                + "behavior   what the concept does, or what a program can do with it: its operations, methods, uses (\"The substring method extracts a part of a string.\")\n"
                + "Write one line for each, as \"concept | property | sentence numbers\" or \"concept | behavior | sentence numbers\", "
                + "for example \"String | property | 1, 2\". Use the concepts exactly as listed. Leave out a sentence that only "
                + "mentions a concept, or that gives an example, a comparison or a remark. Write nothing else.";
    }

    /**
     * @return for each concept listed, the rules of the units the reply names, as properties and behaviors, without
     *         repeats; a concept the reply names nothing for has none
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
     * Collects the rules of every section that has candidates and no material yet ({@code only} empty), or of the
     * sections named, {@code parallel} at a time; each section's list is stored with its rules, not reviewed yet.
     *
     * @return how many sections were asked and how many rules were collected, in one line
     */
    public static String collectAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<BookStore.JudgedConcept>> judged = store.judgedConcepts(book);
        Map<String, List<BookStore.Relation>> relations = store.relations(book);
        Set<String> done = store.frameConcepts(book).keySet();
        Set<String> withRules = store.sectionsWithRules(book);
        Map<String, List<String>> todo = new LinkedHashMap<>();
        for (Map.Entry<String, List<BookStore.JudgedConcept>> e : judged.entrySet()) {
            if (!withRules.contains(e.getKey())) continue;
            if (only.isEmpty() ? done.contains(e.getKey()) : !only.contains(e.getKey())) continue;
            List<String> c = candidates(e.getValue(), relations.getOrDefault(e.getKey(), List.of()));
            if (!c.isEmpty()) todo.put(e.getKey(), c);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger collected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : todo.entrySet()) {
                fs.add(pool.submit(() -> {
                    String number = e.getKey();
                    try {
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<ConceptImportance.Unit> units = ConceptImportance.units(r.rules(), r.code());
                        Map<String, List<BookStore.FrameRule>> got = read(writer.apply(question(nodes.get(number), units, e.getValue())), e.getValue(), units);
                        List<BookStore.FrameConcept> list = new ArrayList<>();
                        for (String c : e.getValue()) list.add(new BookStore.FrameConcept(c, got.get(c), null));
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
     * Reviews every section's list: a concept that got fewer than {@code min} rules is taken off, since the section says
     * little about it; the others stay. No model is asked.
     *
     * @return how many concepts stay and how many were taken off, in one line
     */
    public static String pruneAll(BookStore store, String book, int min) throws Exception {
        int kept = 0, off = 0, sections = 0;
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            List<BookStore.FrameConcept> list = new ArrayList<>();
            for (BookStore.FrameConcept c : e.getValue()) {
                boolean stays = c.rules().size() >= min;
                if (stays) kept++; else off++;
                list.add(new BookStore.FrameConcept(c.concept(), c.rules(), stays));
            }
            store.saveFrameConcepts(book, e.getKey(), list);
            sections++;
        }
        return sections + " section(s) of " + book + ": " + kept + " concept(s) stay, " + off + " taken off (fewer than " + min + " rule(s))";
    }
}
