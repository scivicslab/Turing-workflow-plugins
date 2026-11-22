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
 * Identifies and links pairs of sentences in a section's rule file that express causal or motivational relationships
 * between concepts.
 *
 * <ul><li>{@link #pairs(String, Map<String, String})} returns a list of {@link Pair} objects representing every
 * nucleus and satellite joined by a cause or motive relation.</li><li>{@link #question(BookOutline.Node, List,
 * Map<String, String>, Map<String, String>)} returns a text prompt for an LLM that presents the section's blocks and
 * asks for the concepts tied by each pair.</li><li>{@link #read(String, List, Map<String, Integer>)} returns a list of
 * {@link Link} objects extracted from an LLM's reply, matching the input pairs.</li><li>{@link #askAll(BookStore,
 * String, List, Function, int, Consumer)} asks the LLM about every section in a book that lacks causal data and saves
 * the results to the database.</li></ul>
 *
 * <p>One instance of this class is stateless and may serve several workflows or threads at once.</p>
 */
public final class ConceptCauses {

    /** The set of relation names used to identify cause and motive connections in a section's rule file. */
    public static final Set<String> RELATIONS = Set.of("reason", "consequence-n", "consequence-s", "result", "cause", "purpose",
            "enablement", "means", "problem-solution-n", "problem-solution-s", "explanation-argumentative");

    /** Represents the label for a relation where the first concept makes the second happen or leads to it. */
    public static final String CAUSE = "cause";
    public static final String MOTIVE = "motive";

    /**
     * A single nucleus and satellite sentence from a rule file, joined by a relation of cause or motive, and the name
     * of the block containing them.
     *
     * @param block the name of the block in which these sentences appear
     * @param relation the name of the relation joining the nucleus and satellite
     * @param nucleusRule the name of the rule for the nucleus sentence
     * @param nucleus the text of the nucleus sentence
     * @param satelliteRule the name of the rule for the satellite sentence
     * @param satellite the text of the satellite sentence
     */
    public record Pair(String block, String relation, String nucleusRule, String nucleus, String satelliteRule, String satellite) {}

    /**
     * Represents the connection between two concepts identified in a pair of sentences, specifying whether the
     * relationship is a cause or a motive.
     *
     * @param group the ordinal of the group the pair's block is in, or 0 if it is in no group
     * @param relation the name of the relation from the rule file
     * @param kind either {@link #CAUSE} or {@link #MOTIVE}
     * @param from the concept on the side of the cause or the motive
     * @param to the concept on the side of the effect, or of what is done for the motive
     * @param nucleusRule the name of the nucleus rule
     * @param satelliteRule the name of the satellite rule
     */
    public record Link(int group, String relation, String kind, String from, String to, String nucleusRule, String satelliteRule) {}

    private ConceptCauses() {
    }

    /**
     * Returns the pairs of sentences from the rule file that are joined by a relation of cause or motive, in file
     * order.
     */
    public static List<Pair> pairs(String rules, Map<String, String> code) {
        return pairs(rules, code, RELATIONS);
    }

    /**
     * Returns a list of sentence pairs from the rule file where the satellite is joined to the nucleus by one of the
     * specified relations.
     *
     * @param rules the text of the section's rule file
     * @param code a map of symbol names to their source code text
     * @param relations a set of relation names to filter by, such as those in {@link #RELATIONS}
     * @return a list of {@link Pair} objects containing the nucleus and satellite sentences, their relation, and the
     * block name, in the order they appear in the file
     */
    public static List<Pair> pairs(String rules, Map<String, String> code, Set<String> relations) {
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
                if (!relations.contains(rel)) continue;
                String sat = items.get(i + 1).raw();
                int slash = r.name().indexOf('/');
                out.add(new Pair(slash < 0 ? r.name() : r.name().substring(0, slash), rel, nucleus, text(f, nucleus, code), sat, text(f, sat, code)));
            }
        }
        return out;
    }

    static String text(RuleFile f, String name, Map<String, String> code) {
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

    /**
     * Returns a string that is a prompt for an LLM to identify the causal or motivational links between concepts in a
     * textbook section, showing only the blocks that contain the given pairs.
     *
     * @param node the section node containing the book number and title
     * @param pairs the list of nucleus-satellite sentence pairs to be presented
     * @param concepts a mapping from block names to the main concept of the group they belong to
     * @return a formatted prompt containing the section title, block names, main concepts, and the specific sentence
     * pairs to be analyzed
     */
    public static String question(BookOutline.Node node, List<Pair> pairs, Map<String, String> concepts) {
        return question(node, pairs, concepts, Map.of());
    }

    /**
     * Returns a string that is a prompt for an LLM to identify the concepts tied by cause or motive in a textbook
     * section.
     *
     * @param node the section object containing the number and title
     * @param pairs the list of nucleus-satellite sentence pairs to be queried
     * @param concepts a mapping from block names to the main concept of the group each block belongs to
     * @param blocks a mapping from block names to the text of the sentences in those blocks
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

    /**
     * Parses a text reply into a list of identified cause and motive links.
     *
     * The result is a list of {@link Link} objects corresponding to the pairs in the input list that were successfully
     * matched in the reply, in the order they appear in the input list.
     *
     * A pair that is not mentioned in the reply, or is formatted incorrectly, is omitted from the result.
     *
     * @param reply the text response from the LLM
     * @param pairs the list of available sentence pairs to be matched
     * @param groups a mapping from block names to their group ordinals (0 if the block is in no group)
     * @return a list of links representing the identified relations between concepts
     */
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
     * Asks the local LLM to identify the causal and motivational links between concepts in the specified book's
     * sections and saves the results to the {@code store}.
     *
     * <p>If {@code only} is empty, it asks only for sections that have not yet been processed; otherwise, it asks only
     * for the sections listed in {@code only}.
     *
     * <p>Returns a single line stating the total number of sections asked, the number of links identified as causes,
     * the number of links identified as motives, and the number of sections that failed.
     *
     * <p>A section with no pairs of cause or motive is still processed and stored with an empty list of links, so
     * subsequent runs will skip it.
     *
     * @param store the database containing the book's outline, rules, and results
     * @param book the name of the book to process
     * @param only a list of section numbers to process; if empty, all unprocessed sections are processed
     * @param writer a function that takes the generated question text and returns the LLM's response
     * @param parallel the maximum number of sections to process simultaneously
     * @param log a consumer for progress and error messages
     * @return a summary string: "X section(s) of [book] asked: Y cause(s), Z motive(s), W failed"
     * @throws Exception if an error occurs during the orchestration or database operations
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
        BatchFailures failures = new BatchFailures();
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
                        failures.add(number, ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return failures.outcome(todo.size(), done.get() + " section(s) of " + book + " asked: " + causes.get() + " cause(s), " + motives.get() + " motive(s), " + failed.get() + " failed");
    }
}
