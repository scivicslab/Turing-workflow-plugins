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
 * The level above a section's groups ({@code FrameDataWorkflow_261024_oo01}, step 1, second question): consecutive groups
 * that together explain one larger thing, as its members or kinds (set), its parts (structure), or its steps or topics in
 * order (sequence). Section 3.2 "Comments" is split into groups on the end-of-line comment, the block comment and the
 * documentation comment; together they are the set of comments. One question per section of two groups or more shows
 * the groups with their main concepts and the main sentence of each of their blocks; a group that stands alone gets no
 * cluster.
 */
public final class SectionClusters {

    /**
     * Consecutive groups explaining one larger thing.
     *
     * @param ordinal its place in the section, from 1
     * @param type    sequence, structure or set
     * @param concept the larger thing
     * @param first   the ordinal of its first group
     * @param last    the ordinal of its last group
     */
    public record Cluster(int ordinal, String type, String concept, int first, int last) {}

    private SectionClusters() {
    }

    /** @return the question for one section's groups */
    public static String question(BookOutline.Node node, List<SectionGroups.Block> blocks, List<SectionGroups.Group> groups) {
        Map<String, String> nucleus = new LinkedHashMap<>();
        for (SectionGroups.Block b : blocks) {
            String first = null;
            for (String line : b.text().split("\n")) {
                if (line.startsWith("nucleus: ")) {
                    first = line.substring(9);
                    break;
                }
                if (first == null && !line.isBlank()) first = line.replaceFirst("^- ", "");
            }
            if (first != null) nucleus.put(b.name(), first.length() > 200 ? first.substring(0, 200) + " ..." : first);
        }
        StringBuilder sb = new StringBuilder();
        for (SectionGroups.Group g : groups) {
            sb.append("g").append(g.ordinal()).append(": ").append(g.concept() == null ? "(not named)" : g.concept()).append(" (").append(g.type())
              .append(", ").append(g.first()).append(g.blocks().size() > 1 && !g.first().equals(g.last()) ? "-" + g.last() : "").append(")\n");
            for (String b : g.blocks().stream().map(SectionGroups.Member::block).distinct().toList()) {
                if (nucleus.containsKey(b)) sb.append("    ").append(b).append(": ").append(nucleus.get(b)).append('\n');
            }
        }
        return "One section of a textbook, \"" + node.number() + ". " + node.title() + "\", has been split into these groups of "
                + "paragraphs, in order, each explaining one thing; under each group is the main sentence of each of its paragraphs:\n\n"
                + sb + "\n"
                + "Do some consecutive groups together explain one larger thing? Say how, with one of these words:\n"
                + "set        the groups take up the members or the kinds of the larger thing one by one (\"end-of-line comment\", "
                + "\"block comment\" and \"documentation comment\" are the kinds of \"comment\").\n"
                + "structure  the groups take the larger thing apart into its parts.\n"
                + "sequence   the groups go through the larger thing in order: its steps, its stages, or topics about it one "
                + "after another (what it is, how it is written, how it is used, what to watch out for).\n"
                + "Write one line for each such run of two groups or more: the first group, \"-\", the last group, \" | \", the word, "
                + "\" | \", and the larger thing in a few words, named as a reader would look it up. Runs do not overlap. A group "
                + "that stands alone gets no line; when the whole section explains one thing, one line may cover all its groups. "
                + "For example\n"
                + "g1-g3 | set | comment\n"
                + "Write \"none\" when no groups go together. Write nothing else.";
    }

    /** @return the clusters the reply gives, in order; a line that names no group, runs backwards or overlaps one read is skipped */
    public static List<Cluster> read(String reply, List<SectionGroups.Group> groups) {
        int n = groups.size();
        List<Cluster> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?im)^\\W*g(\\d+)\\W*[-–]\\W*g?(\\d+)\\W*\\|\\s*\\W*(set|structure|sequence)\\W*\\|\\s*(.+?)\\s*$").matcher(reply);
        int next = 1;
        while (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Math.min(n, Integer.parseInt(m.group(2)));
            if (a < next || a > n || b <= a) continue;
            next = b + 1;
            out.add(new Cluster(out.size() + 1, m.group(3).toLowerCase(Locale.ROOT), m.group(4).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", ""), a, b));
        }
        return out;
    }

    /**
     * Asks the clusters of every section of two groups or more not asked yet ({@code only} empty), or of the sections
     * named, {@code parallel} at a time, and stores each as soon as it is read; a section with none is stored with none.
     *
     * @return how many sections were asked and how many clusters of each type they gave, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node x : store.outline(book)) nodes.put(x.number(), x);
        Map<String, List<SectionGroups.Group>> grouped = store.groups(book);
        Set<String> have = store.clustersAsked(book);
        List<String> todo = new ArrayList<>();
        for (Map.Entry<String, List<SectionGroups.Group>> e : grouped.entrySet()) {
            if (e.getValue().size() < 2) continue;
            if (only.isEmpty() ? !have.contains(e.getKey()) : only.contains(e.getKey())) todo.add(e.getKey());
        }
        log.accept("start: " + todo.size() + " section(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        Map<String, AtomicInteger> tally = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String number : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        BookStore.SectionRules r = store.sectionRules(book, number);
                        List<SectionGroups.Group> groups = grouped.get(number);
                        String reply = writer.apply(question(nodes.get(number), SectionGroups.blocks(r.rules(), r.code()), groups));
                        List<Cluster> cs = read(reply, groups);
                        if (cs.isEmpty() && !reply.toLowerCase(Locale.ROOT).contains("none")) throw new IllegalStateException("the reply gives no cluster and does not say none");
                        store.saveClusters(book, number, cs);
                        StringBuilder line = new StringBuilder();
                        for (Cluster c : cs) {
                            tally.computeIfAbsent(c.type(), k -> new AtomicInteger()).incrementAndGet();
                            line.append(line.length() == 0 ? "" : "; ").append("g").append(c.first()).append("-g").append(c.last()).append(' ')
                                .append(c.type()).append(" of ").append(c.concept());
                        }
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + number + ": " + (cs.isEmpty() ? "none" : line));
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
        return done.get() + " section(s) of " + book + " asked for clusters: " + new java.util.TreeMap<>(tally) + ", " + failed.get() + " failed";
    }
}
