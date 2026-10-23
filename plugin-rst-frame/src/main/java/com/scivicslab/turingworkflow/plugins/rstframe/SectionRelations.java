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
 * How the concepts of one section relate ({@code FrameOntology_261021_oo01}, step 6): one question per section,
 * showing its sentences numbered as its rule file has them and the concepts step 5 kept, asking which is a kind of
 * which ({@code is-a}), a part of which ({@code part-of}), a feature of which ({@code feature}), and which are related
 * in another way ({@code other}, so that no relation is forced into the first three), one per line as
 * {@code concept | relation | concept | sentence numbers}. Only the concepts listed are taken, by their names as
 * listed; a line naming any other is dropped.
 */
public final class SectionRelations {

    private static final List<String> KINDS = List.of(BookStore.Relation.IS_A, BookStore.Relation.PART_OF, BookStore.Relation.FEATURE,
            BookStore.Relation.OTHER);

    private SectionRelations() {
    }

    /** @return the question for one section, its sentences numbered from 1 in the order of {@code units} */
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
                + "Say how these concepts relate to one another, as the section says. Write one line for each relation, in one of four forms:\n"
                + "A | is-a | B       when A is a kind of B (\"String literal | is-a | String\")\n"
                + "A | part-of | B    when A is a part or a component of B (\"Escape sequence | part-of | String\")\n"
                + "A | feature | B    when A is a property or an attribute of B (\"UTF-8 encoding | feature | String\")\n"
                + "A | other | B      when A and B are related in some other way: A is a way of doing B, a use of B, a rule about B "
                + "(\"String.join | other | string concatenation\")\n"
                + "Do not force a relation into is-a, part-of or feature when it is none of them; write other.\n"
                + "After each line write \" | \" and the numbers of the sentences that say it, for example "
                + "\"String literal | is-a | String | 5, 8\". Use only the concepts listed, written exactly as listed. "
                + "Leave out a pair the section does not relate. Write nothing else.";
    }

    /**
     * @param concepts  the concepts the question listed; a name is matched to one of them whatever its case
     * @param sentences how many sentences the question showed; a number past it is dropped
     * @return the relations read from a reply, in its order, without repeats, a concept related to itself, or a line
     *         naming a concept not listed or a relation not one of the three
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

    /** A concept's name as a reply may write it: bullets, numbering, quotes and case taken off. */
    private static String norm(String s) {
        return s.strip().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").replaceAll("^[`*\"']+|[`*\"'.]+$", "").replace("`", "")
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Asks how the concepts relate in every section of the book that has two or more concepts kept and no relations
     * asked yet ({@code only} empty), or in the sections named, {@code parallel} at a time, and stores each as soon as
     * it is read. A section that fails is reported and left out, so a run started again goes on with it.
     *
     * @return how many sections were asked, how many relations found, and how many failed, in one line
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
                        List<BookStore.Relation> rel = read(writer.apply(question(nodes.get(number), units, concepts)), concepts, units.size());
                        store.saveRelations(book, number, rel);
                        found.addAndGet(rel.size());
                        log.accept("done " + asked.incrementAndGet() + "/" + todo.size() + " " + number + ": "
                                + String.join("; ", rel.stream().map(x -> x.child() + " " + x.kind() + " " + x.parent()).toList()));
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
        return asked.get() + " section(s) of " + book + " asked, " + found.get() + " relation(s) found, " + failed.get() + " failed";
    }
}
