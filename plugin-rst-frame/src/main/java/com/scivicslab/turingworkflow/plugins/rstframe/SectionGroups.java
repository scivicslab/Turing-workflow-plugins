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
 * The paragraph level of a section ({@code FrameDataWorkflow_261024_oo01}, step 1): the blocks of its heading rule
 * ({@code (defrule s1 -> b1 b2 b3 ...)}, only listed) split into groups of consecutive blocks, each explaining one thing,
 * its main concept, in one of three ways:
 * <ul>
 * <li>{@code sequence}: the blocks go through it in order: the steps of a procedure, stages in time, or topics taken one
 * after another (what it is, its syntax, how it is used, what to watch out for);</li>
 * <li>{@code structure}: the blocks take it apart into its parts;</li>
 * <li>{@code set}: the blocks take up the members or kinds of a group, one by one;</li>
 * </ul>
 * or {@code one}, a group of one block that makes one point. Two questions per section: the first splits the blocks and
 * types each group; the second names each group's main concept, and what each of its blocks is in it (a step, a part, a
 * member). Each block is shown with its sentences labelled by their relation to the block's nucleus, from the rule file.
 */
public final class SectionGroups {

    public static final String SEQUENCE = "sequence";
    public static final String STRUCTURE = "structure";
    public static final String SET = "set";
    public static final String ONE = "one";

    /** One block of a group, and what it is in the group: a step, a part, a member. */
    public record Member(String block, String name) {}

    /**
     * One group of consecutive blocks.
     *
     * @param ordinal its place in the section, from 1
     * @param type    sequence, structure, set or one
     * @param concept its main concept, or {@code null} before the second question
     * @param blocks  its blocks in order, each with its name in the group (empty before the second question)
     */
    public record Group(int ordinal, String type, String concept, List<Member> blocks) {
        public String first() {
            return blocks.get(0).block();
        }

        public String last() {
            return blocks.get(blocks.size() - 1).block();
        }
    }

    /** One block of the section: its rule name, and its sentences, each labelled with its relation to the nucleus. */
    public record Block(String name, String text) {}

    private SectionGroups() {
    }

    /** @return the blocks of the section's heading rule, in order, with their labelled sentences; empty when it has none */
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
            if (t.kind() != RuleFile.Token.Kind.SYMBOL || f.rule(t.raw()) == null) continue;
            out.add(new Block(t.raw(), labelled(f, f.rule(t.raw()), code).strip()));
        }
        return out;
    }

    /** A block's sentences: a nucleus or a satellite as "relation: text", an item of a list as "- text". */
    private static String labelled(RuleFile f, RuleFile.Rule r, Map<String, String> code) {
        if (r.attr(":file") != null) {
            String text = code.get(r.attr(":file"));
            return text == null ? "" : "(code)\n" + text.strip() + "\n";
        }
        if (r.isSentence()) return r.items().get(0).value().strip().replaceAll("\\s+", " ") + "\n";
        StringBuilder sb = new StringBuilder();
        List<RuleFile.Token> items = r.items();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD && i + 1 < items.size() && items.get(i + 1).kind() == RuleFile.Token.Kind.SYMBOL
                    && f.rule(items.get(i + 1).raw()) != null) {
                StringBuilder one = new StringBuilder();
                textOf(f, items.get(++i).raw(), code, one, 0);
                sb.append(t.raw().substring(1)).append(": ").append(one.toString().strip()).append('\n');
            } else if (t.kind() == RuleFile.Token.Kind.KEYWORD) {
                // an attribute, as :kind bullet; its value is skipped with it
                if (i + 1 < items.size() && items.get(i + 1).kind() != RuleFile.Token.Kind.KEYWORD) i++;
            } else if (t.kind() == RuleFile.Token.Kind.SYMBOL && f.rule(t.raw()) != null) {
                StringBuilder one = new StringBuilder();
                textOf(f, t.raw(), code, one, 0);
                sb.append("- ").append(one.toString().strip()).append('\n');
            } else if (t.kind() == RuleFile.Token.Kind.STRING) {
                sb.append(t.value().strip().replaceAll("\\s+", " ")).append('\n');
            }
        }
        return sb.toString();
    }

    private static void textOf(RuleFile f, String name, Map<String, String> code, StringBuilder sb, int depth) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || depth > 20) return;
        if (r.attr(":file") != null) {
            String text = code.get(r.attr(":file"));
            if (text != null) sb.append("(code) ").append(text.strip()).append(' ');
            return;
        }
        for (RuleFile.Token t : r.items()) {
            if (t.kind() == RuleFile.Token.Kind.STRING) sb.append(t.value().strip().replaceAll("\\s+", " ")).append(' ');
            else if (t.kind() == RuleFile.Token.Kind.SYMBOL) textOf(f, t.raw(), code, sb, depth + 1);
        }
    }

    private static String shown(BookOutline.Node node, List<Block> blocks) {
        StringBuilder text = new StringBuilder();
        for (Block b : blocks) {
            String one = b.text().length() > 1500 ? b.text().substring(0, 1500) + " ..." : b.text();
            text.append('[').append(b.name()).append("]\n").append(one).append("\n\n");
            if (text.length() > SectionConcepts.MAX_TEXT) {
                text.append("...\n");
                break;
            }
        }
        return "Below is one section of a textbook, \"" + node.number() + ". " + node.title() + "\", its blocks (paragraphs, lists, "
                + "code) named b1, b2, ... Inside a block, \"nucleus:\" marks its main sentence, and the other labels (purpose, "
                + "cause, result, example, elaboration-..., background, ...) say what each other sentence adds to it; \"- \" marks an "
                + "item of a list.\n\n" + text;
    }

    /** @return the first question for one section: split its blocks into groups, and say how each group explains */
    public static String groupQuestion(BookOutline.Node node, List<Block> blocks) {
        return shown(node, blocks)
                + "Split the blocks into groups. A group is one block or several consecutive blocks that together explain one "
                + "thing. Say how each group explains it, with one of these words:\n"
                + "sequence   the blocks go through the thing in order: the steps of a procedure, stages in time (how it began, "
                + "what went wrong, how it is now), or topics about it taken one after another (what it is, its syntax, how it "
                + "is used, what to watch out for).\n"
                + "structure  the blocks take the thing apart into its parts or components.\n"
                + "set        the blocks take up the members or the kinds of a group one by one, as a list of them does; a block "
                + "that introduces the list (\"among them:\") belongs to the same group.\n"
                + "one        a single block that makes one point (why the thing is needed, what it is).\n"
                + "A block that belongs to no group (a note aside, a sentence leading to another topic) gets the word none.\n"
                + "Every block is in exactly one line. Write one line per group, from the first block to the last, as the first "
                + "block, \"-\", the last block, \" | \", and the word; a group of one block is just its name. For example\n"
                + "b1 | one\nb2-b3 | set\nb4-b7 | sequence\nb8 | none\n"
                + "Write nothing else.";
    }

    /**
     * @return the groups the reply makes, in block order, each a range of consecutive blocks with its type and with its
     *         blocks named only by their rule names; empty when the reply covers no block or its ranges overlap or run
     *         backwards; the blocks it says none of are in no group
     */
    public static List<Group> readGroups(String reply, List<Block> blocks) {
        List<String> names = blocks.stream().map(Block::name).toList();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?[\\]*_`]*(?:\\s*[-–]\\s*[*_`]*\\[?([a-z][\\w/]*)\\]?[*_`]*)?\\s*\\|\\s*\\W*(sequence|structure|set|one|none)\\b").matcher(reply);
        List<Group> out = new ArrayList<>();
        int next = 0;
        while (m.find()) {
            int a = names.indexOf(m.group(1)), b = m.group(2) == null ? a : names.indexOf(m.group(2));
            if (a < 0 || b < a || a < next) return List.of();
            next = b + 1;
            String type = m.group(3).toLowerCase(Locale.ROOT);
            if (type.equals("none")) continue;
            List<Member> members = new ArrayList<>();
            for (int i = a; i <= b; i++) members.add(new Member(names.get(i), null));
            out.add(new Group(out.size() + 1, type, null, members));
        }
        return out;
    }

    /** @return the second question for one section: each group's main concept, and what each of its blocks is in it */
    public static String nameQuestion(BookOutline.Node node, List<Block> blocks, List<Group> groups) {
        StringBuilder g = new StringBuilder();
        for (Group x : groups) {
            g.append("g").append(x.ordinal()).append(": ").append(x.first()).append(x.blocks().size() > 1 ? "-" + x.last() : "")
              .append(", ").append(x.type()).append('\n');
        }
        return shown(node, blocks)
                + "The blocks have been split into these groups, each explaining one thing (sequence: in order; structure: by its "
                + "parts; set: by its members or kinds; one: one point):\n" + g + "\n"
                + "For each group, name the thing it explains, its main concept, in a few words: the one thing its blocks are about "
                + "together, not the title of the whole section unless the group explains the whole section's subject. Name the thing "
                + "itself, as a reader would look it up, not what is said of it: a group that gives the rules for forming identifiers "
                + "explains \"identifier\", not \"identifier composition and character rules\"; a group that explains encapsulation "
                + "explains \"encapsulation\", not \"principles of object-oriented programming\"; a group that tells why Java "
                + "stresses security explains \"Java security\" or the reason itself. Write \"g\", the group's number, \": \", "
                + "and the concept.\n"
                + "Then, for each block of a group of two blocks or more, say what the block is in it, in a few words: in a sequence, "
                + "its step, stage or topic; in a structure, the part it takes up; in a set, the member or kind it takes up, or "
                + "\"introduction\" for a block that introduces the list. A block that lists several members gets one line for each. "
                + "Write the block's name, \" | \", and that. For example\n"
                + "g1: reason Java puts weight on security\ng2: attacks Java prevents\nb2 | introduction\nb3 | overrunning the runtime "
                + "stack\nb3 | corrupting memory outside its own process space\ng3: history of Java security\nb4 | the sandbox at first\n"
                + "b5 | bugs found by security experts\n"
                + "Write nothing else.";
    }

    /** @return the groups with their main concepts and their blocks' names as the reply gives them; a group the reply does not name keeps no concept */
    public static List<Group> readNames(String reply, List<Group> groups) {
        Map<Integer, String> concepts = new LinkedHashMap<>();
        Matcher c = Pattern.compile("(?im)^\\W*g(\\d+)\\s*[:|]\\s*(.+)$").matcher(reply);
        while (c.find()) concepts.put(Integer.parseInt(c.group(1)), clean(c.group(2)));
        Map<String, List<String>> named = new LinkedHashMap<>();
        Matcher b = Pattern.compile("(?im)^\\W*\\[?(b[\\w/]*)\\]?[*_`]*\\s*\\|\\s*(.+)$").matcher(reply);
        while (b.find()) named.computeIfAbsent(b.group(1), k -> new ArrayList<>()).add(clean(b.group(2)));
        List<Group> out = new ArrayList<>();
        for (Group x : groups) {
            List<Member> members = new ArrayList<>();
            for (Member m : x.blocks()) {
                List<String> ns = x.blocks().size() > 1 ? named.get(m.block()) : null;
                if (ns == null || ns.isEmpty()) members.add(new Member(m.block(), ""));
                else for (String n : ns) members.add(new Member(m.block(), n));
            }
            out.add(new Group(x.ordinal(), x.type(), concepts.get(x.ordinal()), members));
        }
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "");
    }

    /**
     * Groups every section that has no groups yet ({@code only} empty), or the sections named, {@code parallel} at a
     * time, and stores each as soon as both its questions are read. A section that fails is reported and left out, so a
     * run started again goes on with it.
     *
     * @return how many sections were grouped and how many groups of each type they have, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<Group>> have = store.groups(book);
        Map<String, List<Block>> todo = new LinkedHashMap<>();
        for (String number : nodes.keySet()) {
            if (only.isEmpty() ? have.containsKey(number) : !only.contains(number)) continue;
            BookStore.SectionRules r = store.sectionRules(book, number);
            if (r == null || r.rules() == null) continue;
            List<Block> blocks = blocks(r.rules(), r.code());
            if (!blocks.isEmpty()) todo.put(number, blocks);
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
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
                        BookOutline.Node node = nodes.get(number);
                        List<Group> groups = readGroups(writer.apply(groupQuestion(node, e.getValue())), e.getValue());
                        if (groups.isEmpty()) throw new IllegalStateException("the reply makes no group of the blocks");
                        groups = readNames(writer.apply(nameQuestion(node, e.getValue(), groups)), groups);
                        store.saveGroups(book, number, groups);
                        StringBuilder line = new StringBuilder();
                        for (Group g : groups) {
                            tally.computeIfAbsent(g.type(), k -> new AtomicInteger()).incrementAndGet();
                            line.append(line.length() == 0 ? "" : "; ").append(g.first()).append(g.blocks().size() > 1 ? "-" + g.last() : "")
                                .append(' ').append(g.type()).append(" of ").append(g.concept());
                        }
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + line);
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
        return done.get() + " section(s) of " + book + " split into groups: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed";
    }
}
