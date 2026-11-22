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
 * Divides the blocks of a section into the smallest units of an explanatory text, where each unit is a run of
 * consecutive blocks explaining one subject along a single axis.
 *
 * <ul><li>{@code sequence}: the blocks go through the subject in order, such as steps in a procedure, stages in time,
 * or topics like syntax and usage;</li><li>{@code structure}: the blocks take the subject apart into its
 * parts;</li><li>{@code set}: the blocks take up its members or kinds, one by one.</li></ul>
 *
 * <p>The class provides the following actions:
 * <ul><li>{@code blocks}: returns the blocks of a section's heading rule in order, with their sentences labelled by
 * their relation to the nucleus;</li><li>{@code groupQuestion}: returns a prompt for a local LLM to split a section's
 * blocks into units and assign each an axis;</li><li>{@code readGroups}: returns the groups described in an LLM's
 * reply, where each group is a range of consecutive blocks with a type;</li><li>{@code nameQuestion}: returns a prompt
 * for a local LLM to name each group's main concept and each of its blocks' roles;</li><li>{@code readNames}: returns
 * the groups with their main concepts and block names as provided in the reply;</li><li>{@code askAll}: executes the
 * full grouping process for a book, asking the LLM questions and saving the results to an H2 database, and returns a
 * summary of the work done.</li></ul>
 *
 * <p>One instance may serve several workflows or threads at once.
 *
 * <p>Example of a workflow step using the class:
 * <pre>{@code
 * List<Block> blocks = SectionGroups.blocks(rules, code);
 * String question = SectionGroups.groupQuestion(node, blocks);
 * // ... call LLM with question ...
 * List<Group> groups = SectionGroups.readGroups(reply, blocks);
 * }</pre>
 */
public final class SectionGroups {

    public static final String SEQUENCE = "sequence";
    public static final String STRUCTURE = "structure";
    public static final String SET = "set";

    /**
     * Represents a single block within an explanatory unit and its role in that unit, such as a step, a part, or a
     * member.
     *
     * @param block the name of the block (e.g., b1, b2)
     * @param name the name of the block's role in the group, such as a step, a part, or a member, or an empty string
     * if no role is specified
     */
    public record Member(String block, String name) {}

    /**
     * One group of consecutive blocks.
     *
     * @param ordinal its place in the section, from 1
     * @param type    its axis: sequence, structure or set
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

    /**
     * One block of a section: its rule name and its sentences, each labelled with its relation to the nucleus.
     *
     * @param name the name of the block from the rule file
     * @param text the block's sentences, where a nucleus or satellite is marked as "relation: text" and a list item as
     * "- text"
     */
    public record Block(String name, String text) {}

    private SectionGroups() {
    }

    /**
     * Returns the blocks of the section's heading rule, in order, with their labelled sentences; empty if the rule has
     * no heading or no blocks.
     *
     * @param rules the text of the rule file
     * @param code a map of rule names to their corresponding code text
     * @return a list of blocks, where each block contains its name and its sentences labelled by their relation to the
     * nucleus or as list items
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
            if (t.kind() != RuleFile.Token.Kind.SYMBOL || f.rule(t.raw()) == null) continue;
            out.add(new Block(t.raw(), labelled(f, f.rule(t.raw()), code).strip()));
        }
        return out;
    }

    /**
     * Returns a string that labels the sentences of a block with their relationship to the nucleus or marks list
     * items.
     *
     * The result is a string where each sentence is preceded by a label such as "relation: " (e.g., "cause: ",
     * "result: ") or, for items in a list, by "- ". If the rule refers to an external file, the string contains the
     * text from that file prefixed with "(code)\n".
     *
     * @param f the rule file containing the structure and relations
     * @param r the specific rule for the block
     * @param code a map of file names to their actual source code text
     * @return a string of labelled sentences or code, or an empty string if the rule is not found or the code is
     * missing
     */
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

    /**
     * Returns a string that asks a local LLM to split the blocks of a section into the smallest units of explanation,
     * each with an axis.
     *
     * <p>The string contains a description of the section's blocks and instructions to group them into units based on
     * one of three axes:
     * <ul>
     * <li>{@code sequence}: blocks that follow an order, such as steps in a procedure or topics taken one after
     * another;</li>
     * <li>{@code structure}: blocks that take a subject apart into its parts;</li>
     * <li>{@code set}: blocks that represent the members or kinds of a subject.</li>
     * </ul>
     * The instructions specify that a unit should be as large as the subject allows, and that the reply must follow a
     * specific format, such as {@code b1-b4 | set} or {@code b5-b7 | sequence}. A block that belongs to no unit should
     * be labeled as {@code none}.</p>
     */
    public static String groupQuestion(BookOutline.Node node, List<Block> blocks) {
        return shown(node, blocks)
                + "Split the blocks into units. A unit is a run of consecutive blocks, one block or more, that explains one "
                + "subject along one axis:\n"
                + "sequence   the blocks go through the subject in order: the steps of a procedure, stages in time (how it "
                + "began, what went wrong, how it is now), or topics about it taken one after another (what it is, its syntax, "
                + "how it is used, what to watch out for).\n"
                + "structure  the blocks take the subject apart into its parts or components.\n"
                + "set        the blocks take up the members or the kinds of the subject one by one; a block that introduces "
                + "them (\"Java has three ways of marking comments\") belongs to the same unit.\n"
                + "Make the units as large as the subject goes: the members of a set, the parts of a structure and the steps or "
                + "topics of a sequence are inside one unit, not units of their own. A section on comments that takes up the "
                + "end-of-line comment, the block comment and the documentation comment in turn is one unit, a set; a section that "
                + "says what a thing is, how it is written and what to watch out for is one unit, a sequence. Start a new unit "
                + "only where the text turns to another subject. A block that belongs to no unit (a note aside, a sentence "
                + "leading to another topic) gets the word none.\n"
                + "Every block is in exactly one line. Write one line per unit, as the first block, \"-\", the last block, "
                + "\" | \", and the axis; a unit of one block is just its name. For example\n"
                + "b1-b4 | set\nb5-b7 | sequence\nb8 | none\n"
                + "Write nothing else.";
    }

    /**
     * Parses a text reply to extract the groups of consecutive blocks defined by the user, returning them as a list of
     * {@link Group} objects.
     *
     * @param reply the text containing the group definitions
     * @param blocks the list of available blocks in the section
     * @return a list of groups in the order they appear in the reply, where each group contains its axis type and its
     * constituent blocks; if a block is not assigned to any group, it is omitted; if a range in the reply extends
     * beyond the last block, it is truncated to the end of the section; lines that name no existing blocks, run
     * backwards, or overlap previously read ranges are skipped.
     */
    public static List<Group> readGroups(String reply, List<Block> blocks) {
        List<String> names = blocks.stream().map(Block::name).toList();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?[\\]*_`]*(?:\\s*[-–]\\s*[*_`]*\\[?([a-z][\\w/]*)\\]?[*_`]*)?\\s*\\|\\s*\\W*(sequence|structure|set|none)\\b").matcher(reply);
        List<Group> out = new ArrayList<>();
        int next = 0;
        while (m.find()) {
            int a = names.indexOf(m.group(1)), b = m.group(2) == null ? a : names.indexOf(m.group(2));
            // a line naming no block of the section is skipped; a range running past the last block stops at it
            if (a < 0) continue;
            if (b < 0) b = names.size() - 1;
            // a range that runs backwards, or starts inside one already read, is skipped
            if (b < a || a < next) continue;
            next = b + 1;
            String type = m.group(3).toLowerCase(Locale.ROOT);
            if (type.equals("none")) continue;
            List<Member> members = new ArrayList<>();
            for (int i = a; i <= b; i++) members.add(new Member(names.get(i), null));
            out.add(new Group(out.size() + 1, type, null, members));
        }
        return out;
    }

    /** Returns true if the reply names every block of the section as none, and contains no other information. */
    static boolean allNone(String reply, List<Block> blocks) {
        for (Block b : blocks) {
            if (!Pattern.compile("(?im)^\\W*\\[?" + Pattern.quote(b.name()) + "\\]?[*_`]*\\s*\\|\\s*\\W*none\\b").matcher(reply).find()) return false;
        }
        return true;
    }

    /**
     * Returns a string that is a prompt for a local LLM to identify the main concept of each group and the role of
     * each block within its group.
     *
     * The prompt includes a description of the section, the list of groups identified in the previous step (showing
     * their range and axis), and instructions for naming the subject of each unit and the role of each block (such as
     * a step, part, or member).
     *
     * @param node the section node from the book outline
     * @param blocks the blocks of the section with their labelled sentences
     * @param groups the groups of consecutive blocks identified in the first step
     * @return a string containing the prompt for the second question
     */
    public static String nameQuestion(BookOutline.Node node, List<Block> blocks, List<Group> groups) {
        StringBuilder g = new StringBuilder();
        for (Group x : groups) {
            g.append("g").append(x.ordinal()).append(": ").append(x.first()).append(x.blocks().size() > 1 ? "-" + x.last() : "")
              .append(", ").append(x.type()).append('\n');
        }
        return shown(node, blocks)
                + "The blocks have been split into these units, each explaining one subject along one axis (sequence: in order; "
                + "structure: by its parts; set: by its members or kinds):\n" + g + "\n"
                + "For each unit, name the subject it explains, its main concept, in a few words: the one thing its blocks are "
                + "about together, not the title of the whole section unless the group explains the whole section's subject. Name the thing "
                + "itself, as a reader would look it up, not what is said of it: a group that gives the rules for forming identifiers "
                + "explains \"identifier\", not \"identifier composition and character rules\"; a group that explains encapsulation "
                + "explains \"encapsulation\", not \"principles of object-oriented programming\"; a group that tells why Java "
                + "stresses security explains \"Java security\" or the reason itself; a unit on the three kinds of comment explains \"comment\". Write \"g\", the unit's number, \": \", "
                + "and the concept.\n"
                + "Then, for each block of a unit of two blocks or more, say what the block is in it, in a few words: in a sequence, "
                + "its step, stage or topic; in a structure, the part it takes up; in a set, the member or kind it takes up, or "
                + "\"(introduction)\" for a block that introduces the list. In a set or a structure, a block that does not take up one "
                + "member or one part (it introduces them, gives a table's title, or says something of the whole) gets its name in "
                + "parentheses: \"(introduction)\", \"(table title)\", \"(use of the whole)\". A block that lists several members gets "
                + "one line for each. "
                + "Write the block's name, \" | \", and that. For example\n"
                + "g1: reason Java puts weight on security\ng2: attacks Java prevents\nb2 | (introduction)\nb3 | overrunning the runtime "
                + "stack\nb3 | corrupting memory outside its own process space\ng3: history of Java security\nb4 | the sandbox at first\n"
                + "b5 | bugs found by security experts\n"
                + "Write nothing else.";
    }

    /**
     * Returns a list of groups where each group's main concept and each of its blocks' names are populated from the
     * reply.
     *
     * <p>The result is a list of {@link Group} objects where the concept and block names are extracted from the text
     * of the reply, matching the group numbers and block names provided in the input list. If the reply does not name
     * a group, that group is returned with a null concept. If a block is not named in the reply, it is returned with
     * an empty name.</p>
     *
     * @param reply the text containing the names of the groups and the roles of the blocks
     * @param groups the existing list of groups to be populated
     * @return a new list of groups with concepts and block names filled in from the reply
     */
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
     * Processes the sections of a book to split them into explanatory units by asking an LLM two questions per
     * section.
     *
     * Returns a single line stating how many sections were successfully grouped, the count of groups for each axis
     * type (sequence, structure, or set), and how many sections failed.
     *
     * If a section's reply results in no groups and all blocks are marked as {@code none}, the section is skipped and
     * will be processed again in a subsequent run.
     *
     * A section that fails to produce a valid group or encounters an error is logged and omitted from the results.
     *
     * <pre>{@code
     * askAll(store, "core-java-14-vol1", List.of("1.2.5"), writer, 4, log);
     * }</pre>
     *
     * @param store the data store containing the book's outline, rules, and existing groups
     * @param book the identifier of the book to process
     * @param only the list of section numbers to process; if empty, all sections without existing groups are processed
     * @param writer a function that takes a question string and returns the LLM's response
     * @param parallel the maximum number of sections to process simultaneously
     * @param log a consumer for progress and error messages
     * @return a string summarizing the number of successful sections, the distribution of group types, and the number
     * of failures
     *
     * @throws Exception if an error occurs during the execution of the workflow steps
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
        BatchFailures failures = new BatchFailures();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, List<Block>> e : todo.entrySet()) {
                String number = e.getKey();
                fs.add(pool.submit(() -> {
                    try {
                        BookOutline.Node node = nodes.get(number);
                        String reply = writer.apply(groupQuestion(node, e.getValue()));
                        List<Group> groups = readGroups(reply, e.getValue());
                        if (groups.isEmpty() && allNone(reply, e.getValue())) {
                            // a section that only leads into its subsections ("In the following sections, we discuss ...")
                            // explains nothing itself; it is asked again on the next run, at the cost of one question
                            log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": no unit (every block is none)");
                            return;
                        }
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
                        failures.add(number, ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return failures.outcome(todo.size(), done.get() + " section(s) of " + book + " split into groups: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed");
    }
}
