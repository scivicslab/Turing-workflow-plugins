package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The items of a concept's concrete frame, built on the rules {@link FrameMaterial} gathered for it: one question per
 * concept that stays on the list, showing the sentences the section says of it, each by its rule's name, asking which
 * questions they answer about the concept (the items) and which item each sentence answers. The items are what an
 * explanation of the concept has to answer; the sentences are what this section answers them with.
 */
public final class FrameItems {

    private FrameItems() {
    }

    /** @return the question for one concept */
    public static String question(BookOutline.Node node, String concept, List<BookStore.FrameRule> rules) {
        StringBuilder text = new StringBuilder();
        for (BookStore.FrameRule r : rules) {
            String one = r.text().strip().replaceAll("\\s+", " ");
            if (one.length() > 600) one = one.substring(0, 600) + " ...";
            text.append('[').append(r.rule()).append("] ").append(one).append('\n');
        }
        String c = "\"" + concept + "\"";
        return "Below are the sentences a section of a textbook, \"" + node.number() + ". " + node.title() + "\", says about "
                + c + ", each named by its rule.\n\n" + text + "\n"
                + "Name the questions about " + c + " that these sentences answer: what a reader must have answered to know "
                + concept + " as this section explains it. One question for each thing the sentences tell (what it consists "
                + "of, what it may not contain, how it is written, how it behaves, how to check it, what to avoid, ...); "
                + "sentences that tell the same thing share a question. Write each question on a line of its own as \"Q1: \" "
                + "and the question, \"Q2: \", and so on. Then write one line for each sentence: its rule's name, \" | \", and "
                + "the number of the question it answers (\"Q3\"), or \"none\" when it answers none about " + c + ".\n"
                + "For example, for \"identifier\":\n"
                + "Q1: What does an identifier consist of?\nQ2: What may an identifier not begin with?\n"
                + "Q3: Which words may not be identifiers?\n"
                + "b4/1 | Q1\nb4/2 | Q2\nb6/5 | Q1\nb9/1 | Q3\n"
                + "Write nothing else.";
    }

    /** @return the items the reply names, in its order, each with the rules that answer it; an item no rule answers is left out */
    public static List<BookStore.FrameItem> read(String reply, List<BookStore.FrameRule> rules) {
        Map<String, String> questions = new LinkedHashMap<>();
        Matcher q = Pattern.compile("(?m)^\\W*Q(\\d+)\\s*[:.)]\\s*(.+)$").matcher(reply);
        while (q.find()) questions.putIfAbsent(q.group(1), q.group(2).replaceAll("^[\\s*_]+|[\\s*_]+$", ""));
        List<String> names = rules.stream().map(BookStore.FrameRule::rule).toList();
        Map<String, List<String>> answered = new LinkedHashMap<>();
        Matcher a = Pattern.compile("(?im)^\\W*\\[?([a-z][\\w/]*)\\]?\\s*\\|\\s*\\W*Q(\\d+)").matcher(reply);
        while (a.find()) {
            String rule = a.group(1);
            if (!names.contains(rule) || !questions.containsKey(a.group(2))) continue;
            List<String> list = answered.computeIfAbsent(a.group(2), k -> new ArrayList<>());
            if (!list.contains(rule)) list.add(rule);
        }
        List<BookStore.FrameItem> out = new ArrayList<>();
        for (Map.Entry<String, String> e : questions.entrySet()) {
            List<String> r = answered.get(e.getKey());
            if (r != null) out.add(new BookStore.FrameItem(e.getValue(), r));
        }
        return out;
    }

    /**
     * Asks the items of every concept that stays on its section's list and has no items yet ({@code only} empty), or of
     * the concepts of the sections named, {@code parallel} at a time, storing each as soon as it is read. A concept that
     * fails is reported and left out, so a run started again goes on with it.
     *
     * @return how many concepts were asked and how many items they got, in one line
     */
    public static String askAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) nodes.put(n.number(), n);
        Map<String, Map<String, List<BookStore.FrameItem>>> have = store.frameItems(book);
        record Todo(String number, BookStore.FrameConcept concept) {}
        List<Todo> todo = new ArrayList<>();
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            if (only.isEmpty() ? false : !only.contains(e.getKey())) continue;
            for (BookStore.FrameConcept c : e.getValue()) {
                if (Boolean.FALSE.equals(c.kept()) || c.rules().isEmpty()) continue;
                if (only.isEmpty() && have.getOrDefault(e.getKey(), Map.of()).containsKey(c.concept())) continue;
                todo.add(new Todo(e.getKey(), c));
            }
        }
        log.accept("start: " + todo.size() + " concept(s) of " + book + ", " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger items = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Todo t : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<BookStore.FrameRule> rules = t.concept().rules();
                        List<BookStore.FrameItem> got = read(writer.apply(question(nodes.get(t.number()), t.concept().concept(), rules)), rules);
                        if (got.isEmpty()) throw new IllegalStateException("the reply names no item a sentence answers");
                        store.saveFrameItems(book, t.number(), t.concept().concept(), got);
                        items.addAndGet(got.size());
                        log.accept("done " + done.incrementAndGet() + "/" + todo.size() + " " + t.number() + " " + t.concept().concept()
                                + ": " + got.size() + " item(s)");
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + t.number() + " " + t.concept().concept() + ": " + ex.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " concept(s) of " + book + " given " + items.get() + " item(s), " + failed.get() + " failed";
    }
}
