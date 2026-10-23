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
 * whose items are only listed): one question per section of two blocks or more, showing the blocks by name, asking for
 * each block what the thing it takes up is to the concept: a kind of it (is-a), a part of it, a step of it, or an aspect
 * of it. The relation most blocks bear gives the section's pattern:
 * <ul>
 * <li>{@code partition}: most blocks take up a kind of it; they divide it, a set, into its kinds;</li>
 * <li>{@code structure}: most take up a part of it; they take it apart;</li>
 * <li>{@code sequence}: most take up a step of it, a process; they go through its steps in order;</li>
 * <li>{@code aspects}: most take up an aspect of it (what it is, its syntax, how it is used, what to watch out for):
 * the slots of the concept's frame.</li>
 * </ul>
 *
 * <p>The model names the main concept first, from the section's title and its text: the thing the section as a whole
 * explains.
 */
public final class SectionPattern {

    public static final String SEQUENCE = "sequence";
    public static final String STRUCTURE = "structure";
    public static final String PARTITION = "partition";
    public static final String ASPECTS = "aspects";

    /**
     * One block of the section and what the thing it takes up is to the concept: a kind of it, a part of it, a step of
     * it, or an aspect of it.
     */
    public record Member(String block, String relation, String name) {
        public static final String KIND = "kind";
        public static final String PART = "part";
        public static final String STEP = "step";
        public static final String ASPECT = "aspect";
        /** A method or an operation that acts on the concept: what can be done with it, one of its aspects. */
        public static final String OPERATION = "operation";
    }

    /** How the section explains {@code concept}: the pattern, and the blocks that are the concept's steps, parts or kinds. */
    public record Layout(String concept, String pattern, List<Member> members) {}

    /** One block of the section: its rule name and its text, the sentences and code of the rules under it. */
    public record Block(String name, String text) {}

    private SectionPattern() {
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
    public static String question(BookOutline.Node node, List<Block> blocks) {
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
                + "First name the section's main concept C: the one thing the section as a whole explains, from its title and its "
                + "text. When the title names a group of things (\"Efficient Maps, Sets, and Queues\", \"Relationships between "
                + "Classes\"), C is the group (\"concurrent collection\", \"relationship between classes\"), not one of its members. "
                + "Write \"concept: \" and C.\n"
                + "Then, for each block, say what the thing it takes up is to C, with one of these five words:\n"
                + "kind     the thing is a kind of C: you can say \"X is a C\" (\"the byte type is an integer type\", \"the // "
                + "comment is a comment\"). A method, a constructor or an operation of C is not a kind of it; it is an operation.\n"
                + "part     the thing is a part or a component of C (\"its state is a part of an object\", \"a field is a part of "
                + "a class\").\n"
                + "step     C is a procedure or a process that runs in time, and the thing is one of its steps (\"compiling is a "
                + "step of building a program\").\n"
                + "aspect   the thing is something said of the whole of C: what it is, why it is needed, its syntax, how it "
                + "behaves, how it is used, what to watch out for.\n"
                + "operation  the thing is a method, a constructor or an operation that acts on C or is called on it. \"add\" and "
                + "\"offer\" are operations of a blocking queue, not kinds of it; \"readln\" and \"Integer.parseInt\" are operations "
                + "of reading input; \"sqrt\" and \"pow\" are operations of the Math class.\n"
                + "A block that introduces C and then takes up one kind, part or step of it is that kind, part or step.\n"
                + "Write one line for each block, and one line for each thing when a block takes up two or more (a table of the "
                + "kinds, a list of the parts): write each row of a table, each item of a list, and each of several things named in one "
                + "sentence (\"ConcurrentHashMap, ConcurrentSkipListMap and ConcurrentLinkedQueue\") on a line of its own. Each "
                + "line is the block's name, \" | \", the word, \" | \", and what the thing is, in a few words; "
                + "for an aspect, the aspect's name (definition, motivation, syntax, behavior, usage, caveat, or another). Leave out a "
                + "block that only supports another (an example, a listing, a note). For example, for section \"Comments\"\n"
                + "concept: comment\nb1 | kind | end-of-line comment (//)\nb3 | kind | block comment (/* */)\nb4 | kind | documentation "
                + "comment (/** */)\n"
                + "and for section \"Constants\"\n"
                + "concept: constant\nb1 | aspect | definition\nb3 | aspect | behavior\nb4 | aspect | usage\n"
                + "Write nothing else.";
    }

    /** The pattern each relation makes when most blocks bear it, in the order a tie is settled. */
    private static final Map<String, String> PATTERN_OF = new LinkedHashMap<>();
    static {
        PATTERN_OF.put(Member.KIND, PARTITION);
        PATTERN_OF.put(Member.PART, STRUCTURE);
        PATTERN_OF.put(Member.STEP, SEQUENCE);
        PATTERN_OF.put(Member.ASPECT, ASPECTS);
    }

    /**
     * @return what the reply says: the things the blocks take up with their relation to the concept, in block order, and
     *         the pattern of the relation most
     *         of them bear (a tie goes to kind, then part, then step; kind, part and step count only when two blocks or
     *         more bear them), with the concept the reply names; the pattern is {@code null} when the reply names no
     *         concept or relates no block to it
     */
    public static Layout read(String reply, List<Block> blocks) {
        Matcher c = Pattern.compile("(?im)^\\W*concept\\W*[:=]\\s*(.+)$").matcher(reply);
        String concept = c.find() ? c.group(1).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "") : null;
        List<String> names = blocks.stream().map(Block::name).toList();
        Map<String, List<Member>> members = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?\\s*\\|\\s*\\W*(kind|part|step|aspect|operation)s?\\W*\\|\\s*(.+)$").matcher(reply);
        while (m.find()) {
            String b = m.group(1);
            if (names.contains(b)) members.computeIfAbsent(b, k -> new ArrayList<>()).add(new Member(b, m.group(2).toLowerCase(Locale.ROOT), m.group(3).strip()));
        }
        List<Member> out = new ArrayList<>();
        for (String b : names) if (members.containsKey(b)) out.addAll(members.get(b));
        String pattern = null;
        long most = 0;
        for (Map.Entry<String, String> e : PATTERN_OF.entrySet()) {
            // an operation is what can be done with the concept: something said of the whole of it, as an aspect is
            long n = out.stream().filter(x -> x.relation().equals(e.getKey())
                    || e.getKey().equals(Member.ASPECT) && x.relation().equals(Member.OPERATION)).count();
            // one kind does not divide a set, one part does not take a thing apart, one step is no sequence; a block
            // that takes up two kinds (a table of them) counts twice
            if (n < 2 && !e.getKey().equals(Member.ASPECT)) continue;
            if (n > most) {
                most = n;
                pattern = e.getValue();
            }
        }
        return new Layout(concept, concept == null ? null : pattern, out);
    }

    /**
     * Asks the pattern of every section of two blocks or more that has none yet ({@code only} empty), or of the
     * sections named, {@code parallel} at a time, and stores each as soon as it is read. A section that fails, or
     * whose reply relates no block to the concept, is reported and left out, so a run started again goes on with it.
     *
     * @return how many sections were asked and how many took each pattern, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, Layout> have = store.patterns(book);
        Map<String, List<Block>> todo = new LinkedHashMap<>();
        int single = 0;
        for (String number : nodes.keySet()) {
            if (only.isEmpty() ? have.containsKey(number) : !only.contains(number)) continue;
            BookStore.SectionRules r = store.sectionRules(book, number);
            if (r == null || r.rules() == null) continue;
            List<Block> blocks = blocks(r.rules(), r.code());
            if (blocks.size() < 2) {
                single++;
                continue;
            }
            todo.put(number, blocks);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + " (" + single + " of one block or none left out), "
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
                        Layout l = read(writer.apply(question(nodes.get(number), e.getValue())), e.getValue());
                        if (l.pattern() == null) throw new IllegalStateException("the reply names no concept or relates no block to it");
                        store.savePattern(book, number, l);
                        tally.computeIfAbsent(l.pattern(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + l.concept() + " by " + l.pattern() + ", " + l.members().size() + " member(s)");
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
