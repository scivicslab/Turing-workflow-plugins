package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies the pattern by which a book section explains its main concept and extracts the relationship of each block
 * to that concept.
 *
 * <ul>
 * <li>{@code blocks(String rules, Map<String, String> code)} returns the blocks of the section's heading rule in
 * order; returns an empty list if the file has no heading rule.</li>
 * <li>{@code question(BookOutline.Node node, List<Block> blocks)} returns a text prompt for a local LLM to identify
 * the section's concept and the role of each block.</li>
 * <li>{@code question(BookOutline.Node node, List<Block> blocks, String examples)} returns a text prompt for a local
 * LLM, including the provided examples.</li>
 * <li>{@code read(String reply, List<Block> blocks)} returns a {@link Layout} containing the identified concept, the
 * dominant pattern, and the list of members extracted from the LLM's reply.</li>
 * <li>{@code askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
 * Consumer<String> log, String examples)} asks the pattern of every section of two blocks or more that has none yet,
 * or the specified sections, and stores each as soon as it is read; returns a summary line of how many sections were
 * asked and how many took each pattern.</li>
 * </ul>
 *
 * <p>The pattern is determined by the relation most blocks bear, with a tie-breaking order of kind, part, step, and
 * then aspect. The patterns are:
 * <ul>
 * <li>{@code partition}: most blocks are a kind of the concept, dividing it into a set;</li>
 * <li>{@code structure}: most blocks are a part of the concept, taking it apart;</li>
 * <li>{@code sequence}: most blocks are a step in a process, following an order;</li>
 * <li>{@code aspects}: most blocks describe an aspect of the concept, such as its definition, syntax, or
 * behavior.</li>
 * </ul>
 * </p>
 */
public final class SectionPattern {

    public static final String SEQUENCE = "sequence";
    public static final String STRUCTURE = "structure";
    public static final String PARTITION = "partition";
    public static final String ASPECTS = "aspects";

    /**
     * Represents a single block of a section and its relationship to the section's main concept.
     *
     * @param block the name of the block (e.g., b1, b2)
     * @param relation one of {@link #KIND}, {@link #PART}, {@link #STEP}, {@link #ASPECT}, or {@link #OPERATION}
     * @param name the name of the thing the block takes up (e.g., a specific type, a part, or a step)
     */
    public record Member(String block, String relation, String name) {
        public static final String KIND = "kind";
        public static final String PART = "part";
        public static final String STEP = "step";
        public static final String ASPECT = "aspect";
        /** A method, a constructor, or an operation that acts on the concept. */
        public static final String OPERATION = "operation";
    }

    /**
     * The pattern of how a section explains its {@code concept}, including the pattern and the blocks that are the
     * concept's steps, parts, or kinds.
     *
     * @param concept the name of the main thing the section explains
     * @param pattern the pattern of the relation most blocks bear (partition, structure, sequence, or aspects)
     * @param members the list of blocks and how each relates to the concept
     */
    public record Layout(String concept, String pattern, List<Member> members) {}

    /**
     * One block of the section: its rule name and its text, the sentences and code of the rules under it.
     *
     * @param name the name of the block (e.g., b1, b2)
     * @param text the text of the block, including sentences and code
     */
    public record Block(String name, String text) {}

    private SectionPattern() {
    }

    /**
     * Returns the blocks of the section's heading rule, in order; an empty list is returned if the file has no heading
     * rule.
     *
     * <p>The blocks are extracted from the rule containing the {@code :heading} attribute. For each block, the text is
     * gathered by following the symbols in the rule, including any code from files specified via the {@code :file}
     * attribute.
     *
     * @param rules the text of the section rules
     * @param code a mapping from file names to their text content
     * @return a list of {@link Block} objects representing the heading rule's items, in order; empty if no heading
     * rule is found
     */
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

    /**
     * Returns a prompt string that instructs a local LLM to identify the main concept of a textbook section and
     * describe how its blocks relate to that concept.
     *
     * <p>The resulting string contains the section's title and number, the text of each block, and instructions to
     * name the concept and categorize each block as a kind, part, step, aspect, or operation.
     *
     * <p>If the blocks list is empty, the returned string contains only the section header and instructions without
     * any block text.
     *
     * <p>Example:
     * <pre>{@code
     * String prompt = SectionPattern.question(node, blocks);
     * }</pre>
     */
    public static String question(BookOutline.Node node, List<Block> blocks) {
        return question(node, blocks, "");
    }

    /**
     * Returns a prompt for a local LLM to identify the main concept of a textbook section and how its blocks relate to
     * that concept.
     *
     * @param node the section node containing the section number and title
     * @param blocks the list of blocks in the section, each containing a name and text
     * @param examples optional worked examples of how blocks should be answered, to be placed before the closing
     * instruction; if null or blank, no examples are included
     *
     * @return a string containing the prompt, which includes the section's title, the text of its blocks, and
     * instructions for naming the concept and the relation of each block (kind, part, step, aspect, or operation)
     */
    public static String question(BookOutline.Node node, List<Block> blocks, String examples) {
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
                + "A block that says several things (what C is, and then one kind of it) gets a line for each; do not choose "
                + "the main one.\n"
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
                + (examples == null || examples.isBlank() ? "" : "\n" + examples.strip() + "\n\n")
                + "Write nothing else.";
    }

    /**
     * Defines the priority order for resolving ties when determining a section's pattern from the relations of its
     * blocks. The order is {@code kind}, then {@code part}, then {@code step}, and finally {@code aspect}.
     */
    private static final Map<String, String> PATTERN_OF = new LinkedHashMap<>();
    static {
        PATTERN_OF.put(Member.KIND, PARTITION);
        PATTERN_OF.put(Member.PART, STRUCTURE);
        PATTERN_OF.put(Member.STEP, SEQUENCE);
        PATTERN_OF.put(Member.ASPECT, ASPECTS);
    }

    /**
     * Parses a text reply from an LLM to extract the section's main concept, the relationship of each block to that
     * concept, and the overall pattern of the section.
     *
     * @param reply the text returned by the LLM containing the concept and the block relations
     * @param blocks the list of blocks available in the section to be matched against the reply
     * @return a {@link Layout} containing the named concept, the pattern of the relation most blocks bear, and a list
     * of {@link Member}s for each block mentioned in the reply; the pattern is {@code null} if the reply names no
     * concept or relates no block to it
     */
    public static Layout read(String reply, List<Block> blocks) {
        Matcher c = Pattern.compile("(?im)^\\W*concept\\W*[:=]\\s*(.+)$").matcher(reply);
        String concept = c.find() ? c.group(1).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "") : null;
        List<String> names = blocks.stream().map(Block::name).toList();
        Map<String, List<Member>> members = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?\\s*\\|\\s*\\W*(kind|part|step|aspect|operation)s?\\W*\\|\\s*(.+)$").matcher(reply);
        while (m.find()) {
            String b = m.group(1);
            String rel = m.group(2).toLowerCase(Locale.ROOT);
            // C itself is not a kind of C
            if (rel.equals(Member.KIND) && concept != null && sameName(m.group(3), concept)) continue;
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

    private static boolean sameName(String a, String b) {
        String x = a.strip().toLowerCase(Locale.ROOT).replaceAll("\\s*\\(.*\\)$", "").replaceAll("s$", "");
        String y = b.strip().toLowerCase(Locale.ROOT).replaceAll("s$", "");
        return x.equals(y);
    }

    /** As {@link #askAll(BookStore, String, List, Function, int, Consumer, String, String)} with no examples. */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        return askAll(store, book, only, writer, parallel, log, "");
    }

    /**
     * Determines the pattern and concept of every section in a book that lacks a pattern or is specifically named,
     * using a provided writer to generate questions and a book store to save the results.
     *
     * <ul>
     * <li>If {@code only} is empty, it processes every section in the book that has two or more blocks and no existing
     * pattern; otherwise, it only processes the sections listed in {@code only}.</li>
     * <li>It executes up to {@code parallel} requests at a time.</li>
     * <li>A section that fails to produce a valid pattern or a concept is reported in the log and skipped.</li>
     * </ul>
     *
     * <p>Returns a single line stating how many sections were successfully processed, a tally of how many sections
     * fell into each pattern (e.g., {@code {partition=10, sequence=5}`), and how many sections failed.</p>
     *
     * <pre>{@code
     * askAll(store, "core-java-14-vol1", List.of("1.2.5"), writer, 4, System.out::println, "Example: ...")
     * }</pre>
     *
     * @param store the book store to read rules from and save patterns to
     * @param book the name of the book to process
     * @param only a list of section numbers to process; if empty, all sections without a pattern are processed
     * @param writer a function that takes a question string and returns a response string
     * @param parallel the maximum number of sections to process concurrently
     * @param log a consumer for progress and error messages
     * @param examples optional text to include in the questions to guide the writer
     * @return a string summarizing the number of sections processed, the count per pattern, and the number of failures
     * @throws Exception if an error occurs during the orchestration of the workflow
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log, String examples) throws Exception {
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
        BatchFailures failures = new BatchFailures();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        LimitedVirtualThreads threads = new LimitedVirtualThreads(parallel);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, List<Block>> e : todo.entrySet()) {
                String number = e.getKey();
                fs.add(threads.submit(() -> {
                    try {
                        Layout l = read(writer.apply(question(nodes.get(number), e.getValue(), examples)), e.getValue());
                        if (l.pattern() == null) throw new IllegalStateException("the reply names no concept or relates no block to it");
                        store.savePattern(book, number, l);
                        tally.computeIfAbsent(l.pattern(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + l.concept() + " by " + l.pattern() + ", " + l.members().size() + " member(s)");
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + number + ": " + ex.getMessage());
                        failures.add(number, ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            threads.shutdown();
        }
        return failures.outcome(todo.size(), done.get() + " section(s) of " + book + " given a pattern: " + new java.util.TreeMap<>(tally) + ", " + failed.get()
                + " failed");
    }
}
