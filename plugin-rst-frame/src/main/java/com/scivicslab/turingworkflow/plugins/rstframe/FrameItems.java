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
 * Generates the questions and answers that form a concept's concrete frame by prompting an LLM with textbook sentences
 * and parsing its response.
 *
 * <ul>
 * <li>{@link #question(BookOutline.Node, String, List)} returns a prompt that presents a concept and its associated
 * sentences to an LLM, asking it to identify the questions those sentences answer.</li>
 * <li>{@link #read(String, List)} returns a list of {@link BookStore.FrameItem} extracted from an LLM's reply, where
 * each item pairs a question with the rules that answer it.</li>
 * <li>{@link #askAll(BookStore, String, List, Function, int, Consumer)} asks the LLM for the items of every concept in
 * a book that lacks them (or is in a specified list), saving the results to the store.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * // Example of asking for items for a specific list of concepts
 * FrameItems.askAll(store, "core-java-14-vol1", List.of("identifier"), llm::call, 4, System.out::println);
 * }</pre>
 *
 * <p>If the LLM's reply contains no items that correspond to the provided rules, {@link #read} returns an empty list
 * and {@link #askAll} reports a failure for that concept.</p>
 */
public final class FrameItems {

    private FrameItems() {
    }

    /**
     * Returns a formatted text prompt that asks a reader to identify the questions answered by a set of sentences
     * about a specific concept. The text includes the section number and title from {@code node}, the name of the
     * {@code concept}, and a list of the sentences from {@code rules} formatted as "[rule-name] sentence-text".
     *
     * <pre>{@code
     * String prompt = FrameItems.question(node, "identifier", rules);
     * }</pre>
     *
     * @param node the section of the book containing the sentences
     * @param concept the name of the concept the sentences describe
     * @param rules the list of sentences and their associated rule names to be included in the prompt
     * @return a string containing the section context, the concept name, the list of sentences, and instructions for
     * the reader
     */
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

    /**
     * Parses a text reply to extract the questions and the rules that answer them.
     *
     * Returns a list of {@link BookStore.FrameItem} objects, where each item contains a question string and a list of
     * rule names that answer it.
     *
     * A question is included only if at least one rule in the reply is associated with it.
     *
     * The method does not require the reply to be well-formed; it simply extracts all matching patterns and ignores
     * those that do not correspond to the provided rules.
     *
     * <pre>{@code
     * List<BookStore.FrameItem> items = FrameItems.read(
     *     "Q1: What is it?\nQ2: How it works?\nb1 | Q1\nb2 | Q2",
     *     rules
     * );
     * }</pre>
     *
     * @param reply the text containing the questions (prefixed with Q followed by a number) and the rule-to-question
     * mappings (rule name followed by a pipe and the question number).
     *
     * @param rules the list of valid {@link BookStore.FrameRule} objects used to validate the rule names found in the
     * reply.
     *
     * @return a list of items, each consisting of a question and the list of rules that answer it.
     */
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
     * Populates the frame items for concepts in a book by asking a writer to identify what questions the sentences in
     * a section answer.
     *
     * <ul>
     * <li>If {@code only} is empty, it asks for the items of every concept in the book that has no items yet;
     * otherwise, it only asks for the concepts listed in {@code only}.</li>
     * <li>If {@code parallel} is greater than 1, it executes the requests in parallel using that many threads.</li>
     * </ul>
     *
     * <p>A concept that fails to produce a valid reply is logged and skipped, allowing the process to continue with
     * the remaining concepts.</p>
     *
     * @param store the book store to read from and write to
     * @param book the name of the book
     * @param only a list of specific concepts to process; if empty, all concepts without existing items are processed
     * @param writer a function that takes a question string and returns a reply string
     * @param parallel the maximum number of concurrent requests to make
     * @param log a consumer for progress and error messages
     * @return a string stating how many concepts were processed, how many items were collected, and how many failed
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
