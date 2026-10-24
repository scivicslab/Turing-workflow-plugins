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
 * The causes and motives of a section's concepts ({@code FrameDataWorkflow_261024_oo01}, step 2): every nucleus of the
 * section's rule file with a satellite joined to it by a relation of cause or motive (reason, consequence, result, cause,
 * purpose, enablement, means, problem-solution, explanation-argumentative) is a pair of sentences; one question per
 * section shows the pairs, numbered, each with the main concept of the group its block is in, and asks for each pair the
 * concept on the side of the cause (or the motive) and the concept on the side of the effect (or what is done for it).
 */
public final class ConceptCauses {

    /** The relations of cause and motive. */
    public static final Set<String> RELATIONS = Set.of("reason", "consequence-n", "consequence-s", "result", "cause", "purpose",
            "enablement", "means", "problem-solution-n", "problem-solution-s", "explanation-argumentative");

    /** A cause makes its effect happen; a motive is what something is done for. */
    public static final String CAUSE = "cause";
    public static final String MOTIVE = "motive";

    /** One nucleus and one satellite joined by a relation of cause or motive, in the block they are in. */
    public record Pair(String block, String relation, String nucleusRule, String nucleus, String satelliteRule, String satellite) {}

    /**
     * What a pair says of two concepts.
     *
     * @param group the ordinal of the group the pair's block is in, 0 when it is in none
     * @param kind  cause or motive
     * @param from  the concept on the side of the cause or the motive
     * @param to    the concept on the side of the effect, or of what is done for the motive
     */
    public record Link(int group, String relation, String kind, String from, String to, String nucleusRule, String satelliteRule) {}

    private ConceptCauses() {
    }

    /** @return the pairs of the rule file, in file order */
    public static List<Pair> pairs(String rules, Map<String, String> code) {
        RuleFile f = RuleFile.parse(rules);
        List<Pair> out = new ArrayList<>();
        for (RuleFile.Rule r : f.rules()) {
            String nucleus = r.symbolAfter(":nucleus");
            if (nucleus == null) continue;
            List<RuleFile.Token> items = r.items();
            for (int i = 0; i + 1 < items.size(); i++) {
                RuleFile.Token t = items.get(i);
                if (t.kind() != RuleFile.Token.Kind.KEYWORD || items.get(i + 1).kind() != RuleFile.Token.Kind.SYMBOL) continue;
                String rel = t.raw().substring(1);
                if (!RELATIONS.contains(rel)) continue;
                String sat = items.get(i + 1).raw();
                int slash = r.name().indexOf('/');
                out.add(new Pair(slash < 0 ? r.name() : r.name().substring(0, slash), rel, nucleus, text(f, nucleus, code), sat, text(f, sat, code)));
            }
        }
        return out;
    }

    private static String text(RuleFile f, String name, Map<String, String> code) {
        StringBuilder sb = new StringBuilder();
        textOf(f, name, code, sb, 0);
        return sb.toString().strip();
    }

    private static void textOf(RuleFile f, String name, Map<String, String> code, StringBuilder sb, int depth) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || depth > 20) return;
        if (r.attr(":file") != null) {
            String t = code.get(r.attr(":file"));
            if (t != null) sb.append("(code) ").append(t.length() > 400 ? t.substring(0, 400) + " ..." : t.strip()).append(' ');
            return;
        }
        for (RuleFile.Token t : r.items()) {
            if (t.kind() == RuleFile.Token.Kind.STRING) sb.append(t.value().strip().replaceAll("\\s+", " ")).append(' ');
            else if (t.kind() == RuleFile.Token.Kind.SYMBOL) textOf(f, t.raw(), code, sb, depth + 1);
        }
    }

    /** As {@link #question(BookOutline.Node, List, Map, Map)}, with the blocks shown only by their pairs. */
    public static String question(BookOutline.Node node, List<Pair> pairs, Map<String, String> concepts) {
        return question(node, pairs, concepts, Map.of());
    }

    /**
     * @param concepts the main concept of the group each block is in, by block; a block in no group is absent
     * @param blocks   each block's sentences labelled by their relation to its nucleus, by block, shown above its pairs so
     *                 that a pair is read with the rest of its block
     * @return the question for one section's pairs
     */
    public static String question(BookOutline.Node node, List<Pair> pairs, Map<String, String> concepts, Map<String, String> blocks) {
        StringBuilder sb = new StringBuilder();
        String shown = null;
        for (int i = 0; i < pairs.size(); i++) {
            Pair p = pairs.get(i);
            if (!p.block().equals(shown)) {
                shown = p.block();
                String c = concepts.get(p.block());
                sb.append("\n[").append(p.block()).append(']').append(c == null ? "" : " about " + c).append('\n');
                String text = blocks.get(p.block());
                if (text != null) sb.append(text.length() > 3000 ? text.substring(0, 3000) + " ..." : text).append('\n');
            }
            sb.append("  p").append(i + 1).append(": the ").append(p.relation()).append(" sentence (\"").append(clip(p.satellite()))
              .append("\") and its nucleus (\"").append(clip(p.nucleus())).append("\")\n");
        }
        return "Below are blocks of one section of a textbook, \"" + node.number() + ". " + node.title() + "\". In a block, \"nucleus:\" "
                + "marks its main sentence and the other labels say what each other sentence adds to it. Under each block are its pairs: "
                + "a sentence joined to the nucleus by a relation of cause or of motive.\n" + sb + "\n"
                + "For each pair, say which two things it ties, as two short noun phrases, and how:\n"
                + "cause   the first thing makes the second happen, or leads to it.\n"
                + "motive  the second thing is done, made, chosen or recommended for the sake of the first.\n"
                + "none    the pair does not tie two things by cause or motive.\n"
                + "Read the pair with the rest of its block: when a nucleus has both a cause and a result, they may be one chain (the "
                + "cause leads to the result), and the nucleus may only set the scene. Name the things themselves, not the sentences: "
                + "a concept, a property, an event or a practice, in a few words, as a reader would look it up. The thing that causes "
                + "or motivates always comes first, whichever sentence says it; for a recommendation, the practice recommended is "
                + "what is done, and the reason given is its motive.\n"
                + "Write one line per pair: the pair's name, \" | \", cause, motive or none, and for cause or motive \" | \", the "
                + "first thing, \" | \", the second thing. For example\n"
                + "p1 | motive | use in networked environments | emphasis on security\n"
                + "p2 | cause | hackers' skill at spotting implementation flaws | bug fixes falling behind\n"
                + "p3 | none\n"
                + "Write nothing else.";
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + " ..." : s;
    }

    /** @return the links the reply gives, in pair order; a pair the reply leaves out or gets wrong has none */
    public static List<Link> read(String reply, List<Pair> pairs, Map<String, Integer> groups) {
        Map<Integer, Link> by = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*p(\\d+)\\W*\\|\\s*\\W*(cause|motive)\\W*\\|\\s*(.+?)\\s*\\|\\s*(.+?)\\s*$").matcher(reply);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1)) - 1;
            if (i < 0 || i >= pairs.size() || by.containsKey(i)) continue;
            Pair p = pairs.get(i);
            by.put(i, new Link(groups.getOrDefault(p.block(), 0), p.relation(), m.group(2).toLowerCase(Locale.ROOT), clean(m.group(3)), clean(m.group(4)),
                    p.nucleusRule(), p.satelliteRule()));
        }
        List<Link> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) if (by.containsKey(i)) out.add(by.get(i));
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "");
    }

    /**
     * Asks the links of every section that has groups and none yet ({@code only} empty), or of the sections named,
     * {@code parallel} at a time, and stores each section's as soon as they are read. A section without a pair of cause
     * or motive is stored with none, so a run started again skips it.
     *
     * @return how many sections were asked and how many links of each kind they gave, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<SectionGroups.Group>> grouped = store.groups(book);
        Set<String> have = store.causesAsked(book);
        Map<String, List<Pair>> todo = new LinkedHashMap<>();
        for (String number : grouped.keySet()) {
            if (only.isEmpty() ? have.contains(number) : !only.contains(number)) continue;
            BookStore.SectionRules r = store.sectionRules(book, number);
            if (r == null || r.rules() == null) continue;
            todo.put(number, pairs(r.rules(), r.code()));
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger(), causes = new AtomicInteger(), motives = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, List<Pair>> e : todo.entrySet()) {
                String number = e.getKey();
                fs.add(pool.submit(() -> {
                    try {
                        Map<String, String> concepts = new LinkedHashMap<>();
                        Map<String, Integer> ordinals = new LinkedHashMap<>();
                        for (SectionGroups.Group g : grouped.get(number)) {
                            for (SectionGroups.Member m : g.blocks()) {
                                if (g.concept() != null) concepts.put(m.block(), g.concept());
                                ordinals.put(m.block(), g.ordinal());
                            }
                        }
                        Map<String, String> blocks = new LinkedHashMap<>();
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        for (SectionGroups.Block b : SectionGroups.blocks(r.rules(), r.code())) blocks.put(b.name(), b.text());
                        String reply = e.getValue().isEmpty() ? "" : writer.apply(question(nodes.get(number), e.getValue(), concepts, blocks));
                        List<Link> links = read(reply, e.getValue(), ordinals);
                        if (!e.getValue().isEmpty() && !Pattern.compile("(?im)^\\W*p\\d+\\W*\\|").matcher(reply).find())
                            throw new IllegalStateException("the reply answers no pair");
                        store.saveCauses(book, number, links);
                        for (Link l : links) (l.kind().equals(CAUSE) ? causes : motives).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + links.size() + " of "
                                + e.getValue().size() + " pair(s) tied");
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
        return done.get() + " section(s) of " + book + " asked: " + causes.get() + " cause(s), " + motives.get() + " motive(s), " + failed.get() + " failed";
    }
}
