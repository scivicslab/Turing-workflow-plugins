package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The relations of a section's main concepts to other concepts ({@code FrameDataWorkflow_261024_oo01}, step 2b), from two
 * sources. The units themselves, with no question: each member of a set unit is a kind of its main concept, each part of
 * a structure unit a part of it. And the rule file: every nucleus with a satellite joined to it by a relation that ties
 * two things (comparison, antithesis, analogy, elaboration-part-whole, elaboration-set-member, background,
 * elaboration-general-specific, example) is a pair of sentences; one question per section shows each pair with its
 * block and asks which two things it ties. The relation's name says how they are tied; the model may say a pair ties
 * none, as a general-specific pair that only says the same thing again.
 */
public final class ConceptRelations {

    /** How each relation of the rule file ties its two things: the kind of link, and what the first and the second thing are. */
    public record Kind(String link, String first, String second) {}

    /** The relations that tie two things, with the link each makes. */
    public static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("comparison", new Kind("compared with", "the thing compared", "what it is compared with"));
        KINDS.put("antithesis", new Kind("contrasted with", "the thing preferred or meant", "what it is set against"));
        KINDS.put("analogy", new Kind("likened to", "the thing explained", "what it is likened to"));
        KINDS.put("elaboration-part-whole", new Kind("part of", "the part", "the whole"));
        KINDS.put("elaboration-set-member", new Kind("member of", "the member", "the set"));
        KINDS.put("background", new Kind("presupposes", "the thing explained", "what the reader needs to know first"));
        KINDS.put("elaboration-general-specific", new Kind("specific case of", "the specific case or detail", "the general thing it is a case or a detail of"));
        KINDS.put("example", new Kind("example of", "the example", "the concept it is an example of"));
    }

    /** A kind of the unit's main concept: a member of a set unit. */
    public static final String KIND_OF = "kind of";
    /** A part of the unit's main concept: a part of a structure unit. */
    public static final String PART_OF = "part of";

    /**
     * One relation of a unit's main concept, or of a concept in its blocks, to another concept.
     *
     * @param group    the ordinal of the unit, 0 when the pair's block is in none
     * @param source   {@code unit} when read off the unit's axis, else the rule file's relation name
     * @param link     how the two are tied: kind of, part of, compared with, ...
     * @param from     the first concept
     * @param to       the second
     * @param rule     the satellite's rule, or the block, it was read from
     */
    public record Relation(int group, String source, String link, String from, String to, String rule) {}

    private ConceptRelations() {
    }

    /** @return the relations the units' axes give: each member of a set is a kind of its main concept, each part of a structure a part of it */
    public static List<Relation> fromUnits(List<SectionGroups.Group> units) {
        List<Relation> out = new ArrayList<>();
        for (SectionGroups.Group g : units) {
            String link = SectionGroups.SET.equals(g.type()) ? KIND_OF : SectionGroups.STRUCTURE.equals(g.type()) ? PART_OF : null;
            if (link == null || g.concept() == null) continue;
            for (SectionGroups.Member m : g.blocks()) {
                String n = m.name() == null ? "" : m.name().strip();
                // step 1 names a block that takes up no member or part (an introduction, a table's title) in parentheses
                if (n.isEmpty() || n.startsWith("(")) continue;
                out.add(new Relation(g.ordinal(), "unit", link, n, g.concept(), m.block()));
            }
        }
        return out;
    }

    /** @return the question for one section's pairs, each read with its block */
    public static String question(BookOutline.Node node, List<ConceptCauses.Pair> pairs, Map<String, String> concepts, Map<String, String> blocks) {
        StringBuilder sb = new StringBuilder();
        String shown = null;
        for (int i = 0; i < pairs.size(); i++) {
            ConceptCauses.Pair p = pairs.get(i);
            if (!p.block().equals(shown)) {
                shown = p.block();
                String c = concepts.get(p.block());
                sb.append("\n[").append(p.block()).append(']').append(c == null ? "" : " about " + c).append('\n');
                String text = blocks.get(p.block());
                if (text != null) sb.append(text.length() > 3000 ? text.substring(0, 3000) + " ..." : text).append('\n');
            }
            Kind k = KINDS.get(p.relation());
            sb.append("  p").append(i + 1).append(": the ").append(p.relation()).append(" sentence (\"").append(clip(p.satellite()))
              .append("\") and its nucleus (\"").append(clip(p.nucleus())).append("\"); name ").append(k.first()).append(", then ")
              .append(k.second()).append('\n');
        }
        return "Below are blocks of one section of a textbook, \"" + node.number() + ". " + node.title() + "\". In a block, \"nucleus:\" "
                + "marks its main sentence and the other labels say what each other sentence adds to it. Under each block are its pairs: "
                + "a sentence joined to the nucleus by a relation that may tie two things, and what to name for it.\n" + sb + "\n"
                + "For each pair, name the two things it ties, in the order asked, as short noun phrases: a concept, a construct, a "
                + "practice or a thing a reader would look up, not the sentences themselves. Read the pair with the rest of its block. "
                + "When the pair does not tie two different things (it only says the same thing again in other words, or one side "
                + "is no thing at all), write none for it.\n"
                + "Write one line per pair: the pair's name, \" | \", the first thing, \" | \", the second thing; or the pair's name, "
                + "\" | none\". For example\n"
                + "p1 | the int type | the long type\n"
                + "p2 | none\n"
                + "Write nothing else.";
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + " ..." : s;
    }

    /** @return the relations the reply gives, in pair order; a pair the reply says none of, leaves out, or ties a thing to itself has none */
    public static List<Relation> read(String reply, List<ConceptCauses.Pair> pairs, Map<String, Integer> units) {
        Map<Integer, Relation> by = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*p(\\d+)\\W*\\|\\s*(.+?)\\s*\\|\\s*(.+?)\\s*$").matcher(reply);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1)) - 1;
            if (i < 0 || i >= pairs.size() || by.containsKey(i)) continue;
            String a = clean(m.group(2)), b = clean(m.group(3));
            if (a.equalsIgnoreCase("none") || b.equalsIgnoreCase("none") || a.equalsIgnoreCase(b)) continue;
            ConceptCauses.Pair p = pairs.get(i);
            by.put(i, new Relation(units.getOrDefault(p.block(), 0), p.relation(), KINDS.get(p.relation()).link(), a, b, p.satelliteRule()));
        }
        List<Relation> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) if (by.containsKey(i)) out.add(by.get(i));
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "");
    }

    /**
     * Reads the relations of every section that has units and none asked yet ({@code only} empty), or of the sections
     * named, {@code parallel} at a time: the units' own, and the rule file's pairs asked in one question per section.
     *
     * @return how many sections were asked and how many relations of each link they gave, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<SectionGroups.Group>> grouped = store.groups(book);
        Set<String> have = store.relationsAsked(book);
        List<String> todo = new ArrayList<>();
        for (String number : grouped.keySet()) if (only.isEmpty() ? !have.contains(number) : only.contains(number)) todo.add(number);
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<SectionGroups.Group> units = grouped.get(number);
                        Map<String, String> concepts = new LinkedHashMap<>();
                        Map<String, Integer> ordinals = new LinkedHashMap<>();
                        for (SectionGroups.Group g : units) {
                            for (SectionGroups.Member m : g.blocks()) {
                                if (g.concept() != null) concepts.put(m.block(), g.concept());
                                ordinals.put(m.block(), g.ordinal());
                            }
                        }
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<ConceptCauses.Pair> pairs = ConceptCauses.pairs(r.rules(), r.code(), KINDS.keySet());
                        List<Relation> rels = new ArrayList<>(fromUnits(units));
                        if (!pairs.isEmpty()) {
                            Map<String, String> blocks = new LinkedHashMap<>();
                            for (SectionGroups.Block b : SectionGroups.blocks(r.rules(), r.code())) blocks.put(b.name(), b.text());
                            String reply = writer.apply(question(nodes.get(number), pairs, concepts, blocks));
                            if (!Pattern.compile("(?im)^\\W*p\\d+\\W*\\|").matcher(reply).find()) throw new IllegalStateException("the reply answers no pair");
                            rels.addAll(read(reply, pairs, ordinals));
                        }
                        store.saveRelationsOfConcepts(book, number, rels);
                        for (Relation x : rels) tally.computeIfAbsent(x.link(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + rels.size() + " relation(s) from "
                                + pairs.size() + " pair(s) and the units");
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
        return done.get() + " section(s) of " + book + " asked for relations: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed";
    }
}
