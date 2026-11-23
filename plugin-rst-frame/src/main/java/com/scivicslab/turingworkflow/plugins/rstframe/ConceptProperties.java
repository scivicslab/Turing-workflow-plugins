package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates and parses questions about the properties of a main concept within a section of a book, used to extract
 * what a concept is, what it has, and what it does.
 *
 * <ul><li>{@code sentences} returns a list of sentences from the specified blocks, each labeled with its relation to
 * the nucleus.</li><li>{@code question} returns a formatted string that asks an LLM to identify the properties of a
 * concept based on a list of sentences.</li><li>{@code read} returns a list of {@link Property} objects parsed from an
 * LLM's reply.</li><li>{@code askAll} asks for the properties of all or specific sections in a book, running up to
 * {@code parallel} requests at a time, and returns a summary of the results.</li></ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class ConceptProperties {

    public static final String IS = "is";
    public static final String HAS = "has";
    public static final String DOES = "does";

    /**
     * Represents a single sentence from a textbook's rule file, containing its identity and its relationship to the
     * nucleus.
     *
     * @param rule the name of the rule (e.g., {@code b4/1})
     * @param relation the relationship to the nucleus ({@code nucleus}, {@code item}, or the specific relation name)
     * @param text the actual text of the sentence
     */
    public record Sentence(String rule, String relation, String text) {}

    /**
     * One property of a group's main concept: the group's ordinal, the concept, the kind of property (is, has, or
     * does), the property description, and the rule name.
     *
     * @param group the ordinal of the group
     * @param concept the main concept of the group
     * @param kind one of {@link #IS}, {@link #HAS}, or {@link #DOES}
     * @param property the description of the property
     * @param rule the name of the rule for the sentence stating the property
     */
    public record Property(int group, String concept, String kind, String property, String rule) {}

    private ConceptProperties() {
    }

    /**
     * Extracts the sentences of the specified blocks from the given rules, ordered by their appearance in the file,
     * with each sentence labeled by its relation to its nucleus.
     *
     * @param rules the text of the rule file
     * @param blocks the names of the blocks to extract
     * @return a list of {@link Sentence} objects, each containing the rule name, the relation to the nucleus, and the
     * sentence text
     */
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

    /**
     * Returns a formatted prompt that asks an LLM to identify the properties of a main concept within a specific group
     * of sentences.
     *
     * The prompt includes the section number and title, the concept being explained, and a list of the sentences from
     * the group. Each sentence is labeled with its rule name and its relation to the nucleus (e.g., nucleus, item, or
     * a specific relation name). The prompt instructs the LLM to categorize sentences as {@code is} (definition),
     * {@code has} (part/feature/attribute), or {@code does} (behavior), while explicitly telling it to ignore causes,
     * motives, examples, or instructions.
     *
     * If the total length of the sentences exceeds {@code SectionConcepts.MAX_TEXT}, the prompt truncates the list
     * with an ellipsis.
     *
     * <pre>{@code
     * String prompt = ConceptProperties.question(node, group, sentences);
     * }</pre>
     *
     * @param node the node in the book outline containing the section number and title
     * @param g the group of sentences whose main concept is being queried
     * @param sentences the list of sentences belonging to the group, each with its rule and relation
     */
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
                + "Write NONE when no sentence states a property. Write nothing else.";
    }

    /**
     * Parses an LLM's reply into a list of properties of a group's main concept.
     *
     * <p>Returns a list of {@code Property} objects extracted from the reply, where each property links a specific
     * sentence (by its rule name) to a kind (is, has, or does) and a description. A line in the reply is ignored if it
     * does not name one of the provided {@code sentences} or if it does not follow the expected format.
     *
     * @param reply the text from the LLM containing the properties
     * @param g the group whose main concept is being described
     * @param sentences the list of sentences available in the group to validate the reply against
     * @return a list of properties found in the reply, in the order they appeared
     */
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
     * Asks a local LLM to identify the properties of the main concepts in the specified book's sections, returning a
     * summary of how many sections were processed and the count of properties found for each kind (is, has, does).
     *
     * <p>If {@code only} is empty, it asks for properties in every section of {@code book} that does not already have
     * properties recorded in {@code store}; otherwise, it only asks for the sections listed in {@code only}. It uses
     * {@code writer} to send the generated questions to the LLM and {@code log} to record progress and failures. The
     * method processes up to {@code parallel} sections simultaneously.
     *
     * <p>Returns a single line containing the number of sections successfully asked and a map of the counts of each
     * property kind found, followed by the number of sections that failed.
     *
     * <pre>{@code
     * askAll(store, "core-java-14-vol1", List.of("1.2.5"), llm::ask, 4, System.out::println);
     * }</pre>
     *
     * @param store the database containing the book's outline, rules, and property records
     * @param book the identifier of the book to process
     * @param only a list of section numbers to process; if empty, all sections without existing properties are
     * processed
     *
     * @param writer a function that takes a question string and returns the LLM's response string
     * @param parallel the maximum number of sections to process at one time
     * @param log a consumer for progress and error messages
     * @throws Exception if an error occurs during the workflow execution
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
        BatchFailures failures = new BatchFailures();
        Map<String, AtomicInteger> tally = new java.util.concurrent.ConcurrentHashMap<>();
        LimitedVirtualThreads threads = new LimitedVirtualThreads(parallel);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(threads.submit(() -> {
                    try {
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<Property> props = new ArrayList<>();
                        for (SectionGroups.Group g : grouped.get(number)) {
                            List<String> blocks = g.blocks().stream().map(SectionGroups.Member::block).distinct().toList();
                            List<Sentence> ss = sentences(r.rules(), blocks);
                            if (ss.isEmpty()) continue;
                            String reply = writer.apply(question(nodes.get(number), g, ss));
                            List<Property> got = read(reply, g, ss);
                            if (got.isEmpty() && !Replies.isNone(reply)) throw new IllegalStateException("the reply states no property and is not NONE");
                            props.addAll(got);
                        }
                        store.saveProperties(book, number, props);
                        for (Property p : props) tally.computeIfAbsent(p.kind(), k -> new AtomicInteger()).incrementAndGet();
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + props.size() + " propert"
                                + (props.size() == 1 ? "y" : "ies"));
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
        return failures.outcome(todo.size(), done.get() + " section(s) of " + book + " asked: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed");
    }
}
