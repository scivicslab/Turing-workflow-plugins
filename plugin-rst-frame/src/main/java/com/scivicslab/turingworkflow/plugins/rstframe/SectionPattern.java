package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * How a section explains its main concept, by the blocks of its heading rule ({@code (defrule s1 -> b1 b2 b3 b4 :heading ...)},
 * whose items are only listed): one question per section of two blocks or more, showing the blocks by name, asking which
 * of three ways of explaining the section takes. The model must choose one of the three.
 * <ul>
 * <li>{@code sequence}: the blocks go through steps in order;</li>
 * <li>{@code structure}: the blocks take one thing apart into its parts;</li>
 * <li>{@code partition}: the blocks divide a set into its kinds, which together make up the set.</li>
 * </ul>
 * The reply also says, for each block that is a step, a part or a kind of the concept, what it is.
 *
 * <p>The main concept ({@link #mainConcept}) is the one in the section's title that got the most rules for its concrete
 * frame; in a section whose title names none, the one that got the most rules.
 */
public final class SectionPattern {

    public static final String SEQUENCE = "sequence";
    public static final String STRUCTURE = "structure";
    public static final String PARTITION = "partition";

    /** One block of the section that is a step, a part or a kind: the block's rule name and what it is. */
    public record Member(String block, String name) {}

    /** How the section explains {@code concept}: the pattern, and the blocks that are the concept's steps, parts or kinds. */
    public record Layout(String concept, String pattern, List<Member> members) {}

    /** One block of the section: its rule name and its text, the sentences and code of the rules under it. */
    public record Block(String name, String text) {}

    private SectionPattern() {
    }

    /**
     * @param judged   the section's concepts as step 5 judged them
     * @param concepts the section's concrete-frame concepts with the rules collected for each
     * @return the concept in the title with the most rules, else the concept with the most rules; {@code null} when no
     *         concept on the list got a rule
     */
    public static String mainConcept(List<BookStore.JudgedConcept> judged, List<BookStore.FrameConcept> concepts) {
        java.util.Set<String> title = new java.util.HashSet<>();
        for (BookStore.JudgedConcept c : judged) if (c.reason() != null && c.reason().startsWith("in the title")) title.add(c.concept());
        String best = null;
        int most = 0;
        for (boolean inTitle : new boolean[] {true, false}) {
            for (BookStore.FrameConcept c : concepts) {
                if (Boolean.FALSE.equals(c.kept()) || (inTitle && !title.contains(c.concept()))) continue;
                if (c.rules().size() > most) {
                    best = c.concept();
                    most = c.rules().size();
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** @return the blocks of the section's heading rule, in order; empty when the file has no heading rule */
    public static List<Block> blocks(String rules, Map<String, String> code) {
        RuleFile f = RuleFile.parse(rules);
        RuleFile.Rule head = null;
        for (RuleFile.Rule r : f.rules()) {
            if (r.attr(":heading") != null) {
                head = r;
                break;
            }
        }
        List<Block> out = new ArrayList<>();
        if (head == null) return out;
        for (RuleFile.Token t : head.items()) {
            if (t.kind() != RuleFile.Token.Kind.SYMBOL) continue;
            StringBuilder sb = new StringBuilder();
            textOf(f, t.raw(), code, sb, 0);
            out.add(new Block(t.raw(), sb.toString().strip()));
        }
        return out;
    }

    private static void textOf(RuleFile f, String name, Map<String, String> code, StringBuilder sb, int depth) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || depth > 20) return;
        if (r.attr(":file") != null) {
            String text = code.get(r.attr(":file"));
            if (text != null) sb.append("(code)\n").append(text.strip()).append('\n');
            return;
        }
        for (RuleFile.Token t : r.items()) {
            if (t.kind() == RuleFile.Token.Kind.STRING) sb.append(t.value().strip().replaceAll("\\s+", " ")).append(' ');
            else if (t.kind() == RuleFile.Token.Kind.SYMBOL) textOf(f, t.raw(), code, sb, depth + 1);
        }
    }

    /** @return the question for one section */
    public static String question(BookOutline.Node node, String concept, List<Block> blocks) {
        StringBuilder text = new StringBuilder();
        for (Block b : blocks) {
            String one = b.text().length() > 1500 ? b.text().substring(0, 1500) + " ..." : b.text();
            text.append('[').append(b.name()).append("] ").append(one).append("\n\n");
            if (text.length() > SectionConcepts.MAX_TEXT) {
                text.append("...\n");
                break;
            }
        }
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its blocks named b1, b2, ...\n\n"
                + text
                + "The section's main concept is \"" + concept + "\". A textbook explains a concept in one of three ways:\n"
                + "sequence    the concept itself is a procedure or a process that runs in time, and the blocks go through its "
                + "steps in the order they happen (compiling then running a program; a loop testing its condition, running its "
                + "body, testing again). A section that only takes up one topic after another (what the concept is, its syntax, "
                + "how it is used, what to watch out for) is not a sequence: the order is the text's, not the concept's.\n"
                + "structure   the blocks take it apart, as anatomy does: each block explains one part or component of it and how "
                + "that part fits with the others.\n"
                + "partition   the blocks divide it, a set, into its kinds: each block explains one kind, and the kinds together "
                + "make up the set (a section on comments explains the // comment, the /* */ comment and the /** */ comment: three "
                + "kinds of comment).\n"
                + "Say which of the three this section uses to explain \"" + concept + "\". You must choose one of the three, the one "
                + "it is closest to, even when none fits well.\n"
                + "Write first \"pattern: \" and the word; then one line for each block that is a step, a part or a kind of it: the "
                + "block's name, \" | \", and what that step, part or kind is, in a few words. Leave out a block that only supports "
                + "another (an example, a listing, a note). For example, for \"comment\"\n"
                + "pattern: partition\nb1 | end-of-line comment (//)\nb3 | block comment (/* */)\nb4 | documentation comment (/** */)\n"
                + "Write nothing else.";
    }

    /** @return what the reply says; the pattern is {@code null} when the reply names none of the three */
    public static Layout read(String reply, String concept, List<Block> blocks) {
        String pattern = null;
        Matcher p = Pattern.compile("(?im)^\\W*pattern\\W*[:=]\\s*\\W*(sequence|structure|partition)").matcher(reply);
        if (p.find()) pattern = p.group(1).toLowerCase(Locale.ROOT);
        List<String> names = blocks.stream().map(Block::name).toList();
        Map<String, String> members = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?\\s*[|:\\-]+\\s*(.+)$").matcher(reply);
        while (m.find()) {
            String b = m.group(1);
            if (names.contains(b)) members.putIfAbsent(b, m.group(2).strip());
        }
        List<Member> out = new ArrayList<>();
        for (String b : names) if (members.containsKey(b)) out.add(new Member(b, members.get(b)));
        return new Layout(concept, pattern, out);
    }

    /**
     * Asks the pattern of every section of two blocks or more and a main concept that has none yet ({@code only} empty), or of the
     * sections named, {@code parallel} at a time, and stores each as soon as it is read. A section that fails, or
     * whose reply names none of the three, is reported and left out, so a run started again goes on with it.
     *
     * @return how many sections were asked and how many took each pattern, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, Layout> have = store.patterns(book);
        Map<String, List<BookStore.JudgedConcept>> judged = store.judgedConcepts(book);
        Map<String, List<BookStore.FrameConcept>> frames = store.frameConcepts(book);
        Map<String, List<Block>> todo = new LinkedHashMap<>();
        Map<String, String> main = new LinkedHashMap<>();
        int single = 0;
        int noConcept = 0;
        for (String number : nodes.keySet()) {
            if (only.isEmpty() ? have.containsKey(number) : !only.contains(number)) continue;
            BookStore.SectionRules r = store.sectionRules(book, number);
            if (r == null || r.rules() == null) continue;
            List<Block> blocks = blocks(r.rules(), r.code());
            if (blocks.size() < 2) {
                single++;
                continue;
            }
            String concept = mainConcept(judged.getOrDefault(number, List.of()), frames.getOrDefault(number, List.of()));
            if (concept == null) {
                noConcept++;
                continue;
            }
            todo.put(number, blocks);
            main.put(number, concept);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + " (" + single + " of one block or none and " + noConcept
                + " without a main concept left out), "
                + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, List<Block>> e : todo.entrySet()) {
                String number = e.getKey();
                fs.add(pool.submit(() -> {
                    try {
                        String concept = main.get(number);
                        Layout l = read(writer.apply(question(nodes.get(number), concept, e.getValue())), concept, e.getValue());
                        if (l.pattern() == null) throw new IllegalStateException("the reply names none of the three patterns");
                        store.savePattern(book, number, l);
                        tally.computeIfAbsent(l.pattern(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + concept + " by " + l.pattern() + ", " + l.members().size() + " member(s)");
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
        return done.get() + " section(s) of " + book + " given a pattern: " + new java.util.TreeMap<>(tally) + ", " + failed.get()
                + " failed";
    }
}
