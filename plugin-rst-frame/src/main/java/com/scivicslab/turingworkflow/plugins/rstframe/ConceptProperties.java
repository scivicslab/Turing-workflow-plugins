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
 * The properties of a group's main concept ({@code FrameDataWorkflow_261024_oo01}, step 3): one question per group shows
 * the sentences of its blocks, each by its rule name and its relation to its nucleus, and asks which of them state what
 * the main concept is (a definition), what it has (a part, a feature, an attribute), or what it does (how it behaves).
 * Why it does so is a cause or a motive, step 2's, not a property.
 */
public final class ConceptProperties {

    public static final String IS = "is";
    public static final String HAS = "has";
    public static final String DOES = "does";

    /** One sentence of a group: its rule name, its relation to its nucleus ({@code nucleus}, {@code item}, or the relation), its text. */
    public record Sentence(String rule, String relation, String text) {}

    /** One property of a group's main concept: the group's ordinal, the concept, is/has/does, the property, the sentence's rule. */
    public record Property(int group, String concept, String kind, String property, String rule) {}

    private ConceptProperties() {
    }

    /** @return the sentences of the blocks named, in file order, each labelled by the relation that joins it to its rule's nucleus */
    public static List<Sentence> sentences(String rules, List<String> blocks) {
        RuleFile f = RuleFile.parse(rules);
        List<Sentence> out = new ArrayList<>();
        for (String b : blocks) walk(f, b, "nucleus", out, 0);
        return out;
    }

    private static void walk(RuleFile f, String name, String relation, List<Sentence> out, int depth) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || depth > 20 || r.attr(":file") != null) return;
        if (r.isSentence()) {
            out.add(new Sentence(name, relation, r.items().get(0).value().strip().replaceAll("\\s+", " ")));
            return;
        }
        List<RuleFile.Token> items = r.items();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD) {
                if (i + 1 < items.size() && items.get(i + 1).kind() == RuleFile.Token.Kind.SYMBOL && f.rule(items.get(i + 1).raw()) != null) {
                    walk(f, items.get(++i).raw(), t.raw().substring(1), out, depth + 1);
                } else if (i + 1 < items.size() && items.get(i + 1).kind() != RuleFile.Token.Kind.KEYWORD) {
                    i++;
                }
            } else if (t.kind() == RuleFile.Token.Kind.SYMBOL) {
                // an item of a list keeps the relation its list has
                walk(f, t.raw(), relation.equals("nucleus") ? "item" : relation, out, depth + 1);
            }
        }
    }

    /** @return the question for one group */
    public static String question(BookOutline.Node node, SectionGroups.Group g, List<Sentence> sentences) {
        StringBuilder sb = new StringBuilder();
        int length = 0;
        for (Sentence s : sentences) {
            String line = "[" + s.rule() + "] (" + s.relation() + ") " + s.text() + "\n";
            if ((length += line.length()) > SectionConcepts.MAX_TEXT) {
                sb.append("...\n");
                break;
            }
            sb.append(line);
        }
        String c = g.concept() == null ? "the thing these sentences explain" : g.concept();
        return "Below are the sentences of a part of one section of a textbook, \"" + node.number() + ". " + node.title() + "\", which "
                + "explains " + c + ". Each sentence has its name in brackets and, in parentheses, what it is to the main sentence of "
                + "its paragraph (nucleus: the main sentence; item: an item of a list; the others: what the sentence adds).\n\n" + sb + "\n"
                + "Which sentences state a property of " + c + " itself? Say for each such sentence which kind of property it states:\n"
                + "is    what " + c + " is: a definition, or what it is a kind of.\n"
                + "has   something " + c + " has: a part, a feature, an attribute, a rule its form follows.\n"
                + "does  what " + c + " does or how it behaves: what happens when it is used, what it allows or prevents.\n"
                + (SectionGroups.SET.equals(g.type()) ? "These sentences take up the members or kinds of " + c + " one by one; a member "
                        + "or a kind is not a property: give only what " + c + " as a whole is, has or does.\n"
                        : SectionGroups.SEQUENCE.equals(g.type()) ? "These sentences go through " + c + " in order; a step, a stage or "
                        + "an event of it is not a property: give only what " + c + " as a whole is, has or does.\n" : "")
                + "Leave out a sentence that says why (a cause, a motive), gives an example, tells the reader what to do, or is about "
                + "something else. Write one line per property: the sentence's name, \" | \", is, has or does, \" | \", the property "
                + "in a few words, with " + c + " as its subject left out. A sentence that states two properties gets two lines. "
                + "For example, for \"the identifier\"\n"
                + "b4/1 | has | letters, digits, currency symbols and connecting punctuation as its characters\n"
                + "b4/2 | has | a first character that is not a digit\n"
                + "b5/1 | does | tell upper case and lower case apart\n"
                + "Write nothing at all when no sentence states a property. Write nothing else.";
    }

    /** @return the properties the reply gives, in reply order; a line naming no sentence of the group is skipped */
    public static List<Property> read(String reply, SectionGroups.Group g, List<Sentence> sentences) {
        Set<String> names = new java.util.HashSet<>();
        for (Sentence s : sentences) names.add(s.rule());
        List<Property> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?[*_`]*\\s*\\|\\s*\\W*(is|has|does)\\W*\\|\\s*(.+?)\\s*$").matcher(reply);
        while (m.find()) {
            if (!names.contains(m.group(1))) continue;
            out.add(new Property(g.ordinal(), g.concept(), m.group(2).toLowerCase(Locale.ROOT), m.group(3).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", ""), m.group(1)));
        }
        return out;
    }

    /**
     * Asks the properties of every group of every section that has groups and whose properties have not been asked
     * ({@code only} empty), or of the sections named, one question per group, {@code parallel} at a time; a section's
     * properties are stored once all its groups are read.
     *
     * @return how many sections were asked and how many properties of each kind they gave, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, List<SectionGroups.Group>> grouped = store.groups(book);
        Set<String> have = store.propertiesAsked(book);
        List<String> todo = new ArrayList<>();
        for (String number : grouped.keySet()) if (only.isEmpty() ? !have.contains(number) : only.contains(number)) todo.add(number);
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        Map<String, AtomicInteger> tally = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<Property> props = new ArrayList<>();
                        for (SectionGroups.Group g : grouped.get(number)) {
                            List<String> blocks = g.blocks().stream().map(SectionGroups.Member::block).distinct().toList();
                            List<Sentence> ss = sentences(r.rules(), blocks);
                            if (ss.isEmpty()) continue;
                            props.addAll(read(writer.apply(question(nodes.get(number), g, ss)), g, ss));
                        }
                        store.saveProperties(book, number, props);
                        for (Property p : props) tally.computeIfAbsent(p.kind(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + props.size() + " propert"
                                + (props.size() == 1 ? "y" : "ies"));
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
        return done.get() + " section(s) of " + book + " asked: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed";
    }
}
