package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Builds a hierarchy of generalized concepts from the concrete frames of sections by identifying shared concepts,
 * merging their material, and climbing the concept tree.
 *
 * <ul>
 * <li>{@link #identifyAll} recognizes the concepts of the book by grouping appearances of the same name across
 * different sections into single entities.</li>
 * <li>{@link #mergeAll} combines the sentences from all sections where a shared concept appears and asks for the
 * questions those sentences answer.</li>
 * <li>{@link #generalizeAll} creates abstract concepts from those with two or more kinds, combining the material of
 * the parent and its kinds to ask for their shared questions.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class GeneralFrames {

    private GeneralFrames() {
    }

    /**
     * Represents a single sentence of gathered material, linking its source section and rule to the concept it
     * describes.
     *
     * @param number the section number where the sentence appears
     * @param concept the name of the concept the sentence describes
     * @param rule the name of the rule that produced the sentence
     * @param text the actual text of the sentence
     *
     * Returns a string that is the section number and the rule name joined by a colon, such as {@code 1.2.5:s1}.
     */
    public record Sentence(String number, String concept, String rule, String text) {
        /**
         * Returns a string that identifies the sentence in the stored items by combining the section number and the
         * rule name.
         */
        public String ref() {
            return number + ":" + rule;
        }
    }

    /**
     * Returns a string that is a lowercase version of the input name, stripped of quotes and parentheses, with extra
     * whitespace removed and a single English plural form used for the final word.
     */
    public static String norm(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", " ").replaceAll("[`\"']", "").replaceAll("\\s+", " ").strip();
        int sp = s.lastIndexOf(' ');
        String head = s.substring(0, sp + 1);
        String last = s.substring(sp + 1);
        if (last.length() > 3) {
            if (last.endsWith("ies")) last = last.substring(0, last.length() - 3) + "y";
            else if (last.matches(".*(ss|x|ch|sh)es")) last = last.substring(0, last.length() - 2);
            else if (last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is")) last = last.substring(0, last.length() - 1);
        }
        return head + last;
    }

    // ---- step 1: which concepts of the sections are one concept of the book

    /**
     * Returns a prompt that asks a model to determine if several appearances of a name refer to the same concept.
     *
     * <p>The prompt lists each appearance of {@code name} found in the provided {@code occ} list, numbering them and
     * showing the questions that the corresponding section answers about that appearance. The questions are retrieved
     * from {@code itemsOf} using the section number and concept name as a key. The prompt instructs the model to group
     * appearances that represent the same concept by writing "G: " followed by the group's numbers, and to treat any
     * appearance not mentioned in a group as a group of one.
     *
     * <p>An example of the expected reply format is:
     * <pre>{@code
     * G: 1, 2, 4
     * G: 3
     * }</pre>
     *
     * @param name the name of the concept to be judged
     * @param occ the list of occurrences of the name in the book, each containing a section number and a concept name
     * @param itemsOf a map from a key of "section number + tab + concept name" to the list of questions that section
     * answers about that concept
     *
     * @return a string containing the full prompt for the model
     */
    public static String identifyQuestion(String name, List<BookStore.Occurrence> occ, Map<String, List<BookStore.FrameItem>> itemsOf) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < occ.size(); i++) {
            BookStore.Occurrence o = occ.get(i);
            sb.append('[').append(i + 1).append("] \"").append(o.concept()).append("\" in section ").append(o.number()).append('\n');
            List<BookStore.FrameItem> its = itemsOf.getOrDefault(o.number() + "\t" + o.concept(), List.of());
            int k = 0;
            for (BookStore.FrameItem it : its) {
                if (k++ == 12) break;
                sb.append("    ").append(it.question()).append('\n');
            }
        }
        return "A textbook speaks of \"" + name + "\" in several sections. Below, each appearance is numbered, with the questions "
                + "that section answers about it.\n\n" + sb + "\n"
                + "Say which appearances are the same concept, the same thing being explained, and which are different things "
                + "that share the name. Judge by what is said of them, not by the name. Write one line for each group of "
                + "appearances that are one concept, as \"G: \" and their numbers separated by commas; every appearance in one "
                + "group. For example\nG: 1, 2, 4\nG: 3\nWrite nothing else.";
    }

    /**
     * Parses a text reply to group the indices of appearances into lists.
     *
     * <p>The result is a list of lists, where each inner list contains the 0-based indices of appearances that belong
     * to the same group. Any appearance index from 0 to count - 1 that is not mentioned in the reply is returned as a
     * separate list containing only that index.</p>
     *
     * @param reply the text containing groups of indices, typically formatted as "G: 1, 2, 4"
     * @param count the total number of appearances available, used to identify unmentioned indices
     * @return a list of lists of integers representing the groups of indices
     */
    public static List<List<Integer>> readGroups(String reply, int count) {
        List<List<Integer>> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^\\W*G\\w*\\s*[:=]\\s*(.+)$").matcher(reply);
        while (m.find()) {
            List<Integer> g = new ArrayList<>();
            Matcher d = Pattern.compile("\\d+").matcher(m.group(1));
            while (d.find()) {
                int i = Integer.parseInt(d.group()) - 1;
                if (i >= 0 && i < count && seen.add(i)) g.add(i);
            }
            if (!g.isEmpty()) out.add(g);
        }
        for (int i = 0; i < count; i++) if (!seen.contains(i)) out.add(List.of(i));
        return out;
    }

    /**
     * Filters the book's concrete-frame concepts to find only those that are kept and have associated rules, returning
     * them as a list of occurrences.
     */
    private static List<BookStore.Occurrence> appearances(BookStore store, String book) throws Exception {
        List<BookStore.Occurrence> out = new ArrayList<>();
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            for (BookStore.FrameConcept c : e.getValue()) {
                if (Boolean.FALSE.equals(c.kept()) || c.rules().isEmpty()) continue;
                out.add(new BookStore.Occurrence(e.getKey(), c.concept()));
            }
        }
        return out;
    }

    /**
     * Identifies the concepts of the book by determining which section appearances refer to the same concept,
     * replacing the existing concept entities in the store.
     *
     * Returns a string stating the total number of concepts found in the book, how many of them appear in more than
     * one section, and how many attempts failed.
     *
     * <pre>{@code
     * identifyAll(store, "core-java-14-vol1", List.of(), writer, 4, log)
     * }</pre>
     *
     * If the model cannot judge a name, the name keeps the concepts an earlier run stored (none on a first run) and the
     * batch fails naming it, so a rerun judges it again; the names judged are stored either way.
     */
    public static String identifyAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, List<BookStore.Occurrence>> byName = new LinkedHashMap<>();
        for (BookStore.Occurrence o : appearances(store, book)) byName.computeIfAbsent(norm(o.concept()), k -> new ArrayList<>()).add(o);
        Map<String, List<BookStore.FrameItem>> itemsOf = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<BookStore.FrameItem>>> e : store.frameItems(book).entrySet()) {
            for (Map.Entry<String, List<BookStore.FrameItem>> c : e.getValue().entrySet()) itemsOf.put(e.getKey() + "\t" + c.getKey(), c.getValue());
        }
        List<String> asked = byName.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey).toList();
        log.accept("start: " + byName.size() + " name(s), " + asked.size() + " of them in several sections, " + parallel + " at a time");
        Map<String, List<List<Integer>>> groups = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        BatchFailures failures = new BatchFailures();
        Set<String> notJudged = java.util.concurrent.ConcurrentHashMap.newKeySet();
        LimitedVirtualThreads threads = new LimitedVirtualThreads(parallel);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String n : asked) {
                fs.add(threads.submit(() -> {
                    List<BookStore.Occurrence> occ = byName.get(n);
                    try {
                        List<List<Integer>> g = readGroups(writer.apply(identifyQuestion(n, occ, itemsOf)), occ.size());
                        groups.put(n, g);
                        log.accept("done " + done.incrementAndGet() + "/" + asked.size() + " " + n + ": " + occ.size() + " appearance(s), "
                                + g.size() + " concept(s)");
                    } catch (Exception ex) {
                        // the model's failure is not a decision: the name keeps the concepts an earlier run stored
                        failed.incrementAndGet();
                        notJudged.add(n);
                        log.accept("FAILED " + n + ": " + ex.getMessage());
                        failures.add(n, ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            threads.shutdown();
        }
        List<BookStore.Entity> entities = new ArrayList<>();
        int shared = 0;
        for (Map.Entry<String, List<BookStore.Occurrence>> e : byName.entrySet()) {
            if (notJudged.contains(e.getKey())) continue;
            List<BookStore.Occurrence> occ = e.getValue();
            List<List<Integer>> g = groups.getOrDefault(e.getKey(), List.of(java.util.stream.IntStream.range(0, occ.size()).boxed().toList()));
            for (int k = 0; k < g.size(); k++) {
                List<BookStore.Occurrence> members = g.get(k).stream().map(occ::get).toList();
                String id = g.size() == 1 ? e.getKey() : e.getKey() + "#" + (k + 1);
                entities.add(new BookStore.Entity(id, members.get(0).concept(), BookStore.Entity.CONCEPT, members, List.of()));
                if (members.size() > 1) shared++;
            }
        }
        Set<String> saved = new java.util.HashSet<>();
        for (BookStore.Entity e : entities) saved.add(e.id());
        Set<String> kept = new java.util.HashSet<>();
        for (BookStore.Entity earlier : store.entities(book)) {
            if (!BookStore.Entity.CONCEPT.equals(earlier.level()) || saved.contains(earlier.id())) continue;
            // ids are the name alone, or name#k (k a number) when an earlier run split it; a name may itself hold '#',
            // so "c#" is another name's id, not one of c's
            if (notJudged.stream().anyMatch(n -> earlier.id().equals(n)
                    || earlier.id().matches(java.util.regex.Pattern.quote(n) + "#\\d+"))) kept.add(earlier.id());
        }
        store.saveEntities(book, BookStore.Entity.CONCEPT, entities, kept);
        return failures.outcome(asked.size(), entities.size() + " concept(s) of " + book + ", " + shared + " of them in more than one section, "
                + failed.get() + " failed");
    }

    // ---- steps 2 and 3: the same question over material put together

    /**
     * Returns a prompt string that asks a model to name the questions answered by a set of sentences about a concept
     * and its kinds.
     *
     * <p>The string contains a numbered list of sentences, each prefixed with its concept and section, followed by
     * instructions to name the questions and then map each sentence to a question number. If {@code kinds} is empty,
     * the prompt asks about {@code name} alone; otherwise, it asks about {@code name} and its specified kinds.
     *
     * <p>Example of the generated prompt format:
     * <pre>{@code
     * [r1] (Concept, Section) Sentence text...
     * [r2] (Concept, Section) Sentence text...
     *
     * Q1: What does an identifier consist of?
     * r1 | Q1
     * r2 | none
     * }</pre>
     *
     * @param name the name of the concept to be explained
     * @param kinds a list of names of kinds of the concept; if empty, only the main concept is queried
     * @param sentences the list of sentences gathered from the book to be used as material for the questions
     * @return a formatted prompt string for a local LLM
     */
    public static String itemsQuestion(String name, List<String> kinds, List<Sentence> sentences) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            Sentence s = sentences.get(i);
            String one = s.text().strip().replaceAll("\\s+", " ");
            if (one.length() > 400) one = one.substring(0, 400) + " ...";
            text.append("[r").append(i + 1).append("] (").append(s.concept()).append(", ").append(s.number()).append(") ").append(one).append('\n');
        }
        String c = "\"" + name + "\"";
        String what = kinds.isEmpty()
                ? "these are the sentences a textbook says about " + c + ", gathered from the sections it appears in"
                : "these are the sentences a textbook says about " + c + " and about its kinds (" + String.join(", ", kinds)
                        + "), gathered from the sections they appear in";
        String about = kinds.isEmpty() ? c : "any kind of " + c;
        return "Below, " + what + "; each is numbered, with the concept and the section it was gathered for.\n\n" + text + "\n"
                + "Name the questions an explanation of " + about + " answers, as these sentences answer them: one question for "
                + "each thing they tell (what it consists of, what it may not contain, how it is written, how it behaves, how to "
                + "check it, what to avoid, ...). Sentences that tell the same thing, from whichever section"
                + (kinds.isEmpty() ? "" : " or kind") + ", share a question"
                + (kinds.isEmpty() ? "." : "; a question only some kinds answer is kept, for the kinds it fits.")
                + " Write each question on a line of its own as \"Q1: \" and the question, \"Q2: \", and so on. Then write one "
                + "line for each sentence: its number, \" | \", and the number of the question it answers (\"Q3\"), or \"none\".\n"
                + "For example\nQ1: What does an identifier consist of?\nQ2: What may an identifier not begin with?\n"
                + "r1 | Q1\nr2 | Q2\nr3 | Q1\n"
                + "Write nothing else.";
    }

    /**
     * Parses a model's reply to extract the questions it defined and the sentences that answer them.
     *
     * Returns a list of {@link BookStore.FrameItem} where each item contains a question string and a list of
     * references (section and rule) to the sentences that answer it.
     *
     * If the reply contains no valid question definitions or no sentences that map to the defined questions, an empty
     * list is returned.
     *
     * @param reply the text from the model containing question definitions (e.g., "Q1: ...") and sentence mappings
     * (e.g., "r1 | Q1").
     *
     * @param sentences the list of available sentences used to resolve the references in the reply.
     * @return a list of items, each consisting of a question and the references to the sentences that answer it.
     */
    public static List<BookStore.FrameItem> readItems(String reply, List<Sentence> sentences) {
        Map<String, String> questions = new LinkedHashMap<>();
        Matcher q = Pattern.compile("(?m)^\\W*Q(\\d+)\\s*[:.)]\\s*(.+)$").matcher(reply);
        while (q.find()) questions.putIfAbsent(q.group(1), q.group(2).replaceAll("^[\\s*_]+|[\\s*_]+$", ""));
        Map<String, List<String>> answered = new LinkedHashMap<>();
        Matcher a = Pattern.compile("(?im)^\\W*r(\\d+)\\W*\\|\\s*\\W*Q(\\d+)").matcher(reply);
        while (a.find()) {
            int i = Integer.parseInt(a.group(1)) - 1;
            if (i < 0 || i >= sentences.size() || !questions.containsKey(a.group(2))) continue;
            List<String> list = answered.computeIfAbsent(a.group(2), k -> new ArrayList<>());
            String ref = sentences.get(i).ref();
            if (!list.contains(ref)) list.add(ref);
        }
        List<BookStore.FrameItem> out = new ArrayList<>();
        for (Map.Entry<String, String> e : questions.entrySet()) {
            List<String> r = answered.get(e.getKey());
            if (r != null) out.add(new BookStore.FrameItem(e.getValue(), r));
        }
        return out;
    }

    /**
     * The maximum length in characters for the combined text of sentences used in a single question to the model.
     */
    static final int MAX_TEXT = 16000;

    /**
     * Returns a list of sentences gathered from the occurrences of a concept, in section order, up to a total text
     * length of {@link #MAX_TEXT} characters.
     */
    private static List<Sentence> material(List<BookStore.Occurrence> occ, Map<String, BookStore.FrameConcept> conceptOf) {
        List<Sentence> out = new ArrayList<>();
        int len = 0;
        for (BookStore.Occurrence o : occ) {
            BookStore.FrameConcept c = conceptOf.get(o.number() + "\t" + o.concept());
            if (c == null) continue;
            for (BookStore.FrameRule r : c.rules()) {
                if (len > MAX_TEXT) return out;
                out.add(new Sentence(o.number(), o.concept(), r.rule(), r.text()));
                len += Math.min(400, r.text().length()) + 40;
            }
        }
        return out;
    }

    private static Map<String, BookStore.FrameConcept> conceptsByPlace(BookStore store, String book) throws Exception {
        Map<String, BookStore.FrameConcept> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            for (BookStore.FrameConcept c : e.getValue()) out.put(e.getKey() + "\t" + c.concept(), c);
        }
        return out;
    }

    /**
     * Holds the data for a single request to the local LLM: a concept to be described, its kinds, and the sentences
     * that form its material.
     *
     * @param entity the concept being described
     * @param kinds the names of the kinds of the concept, if any
     * @param sentences the sentences gathered from the book to be used as material
     */
    private record Ask(BookStore.Entity entity, List<String> kinds, List<Sentence> sentences) {}

    private static String askEach(BookStore store, String book, List<Ask> asks, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        log.accept("start: " + asks.size() + " concept(s), " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger items = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        BatchFailures failures = new BatchFailures();
        LimitedVirtualThreads threads = new LimitedVirtualThreads(parallel);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Ask a : asks) {
                fs.add(threads.submit(() -> {
                    try {
                        List<BookStore.FrameItem> got = readItems(writer.apply(itemsQuestion(a.entity().name(), a.kinds(), a.sentences())), a.sentences());
                        if (got.isEmpty()) throw new IllegalStateException("the reply names no item a sentence answers");
                        store.saveEntityItems(book, a.entity().id(), got);
                        items.addAndGet(got.size());
                        log.accept("done " + done.incrementAndGet() + "/" + asks.size() + " " + a.entity().id() + ": " + a.sentences().size()
                                + " sentence(s), " + got.size() + " item(s)");
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + a.entity().id() + ": " + ex.getMessage());
                        failures.add(a.entity().id(), ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            threads.shutdown();
        }
        return failures.outcome(asks.size(), done.get() + " concept(s) of " + book + " given " + items.get() + " item(s), " + failed.get() + " failed");
    }

    /**
     * Consolidates the material of concepts appearing in multiple sections and asks for their items across the whole
     * book.
     *
     * Returns a string stating how many concepts were processed, how many items were found, and how many failed.
     *
     * If a concept appears in only one section, it is skipped and its existing items are preserved.
     *
     * <pre>{@code
     * mergeAll(store, "core-java-14-vol1", List.of("concept-id"), writer, 4, log)
     * }</pre>
     *
     * @param store the H2 database containing the book's sections, rules, and concepts
     * @param book the name of the book to process
     * @param only a list of concept IDs to process; if empty, all concepts appearing in more than one section are
     * processed
     *
     * @param writer a function that takes the question text and returns the LLM's reply
     * @param parallel the number of concurrent calls to the LLM
     * @param log a consumer for progress and error messages
     * @return a summary string containing the count of processed concepts, items found, and failures
     * @throws Exception if an error occurs during database access or LLM communication
     */
    public static String mergeAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookStore.FrameConcept> conceptOf = conceptsByPlace(store, book);
        Map<String, List<BookStore.FrameItem>> have = store.entityItems(book);
        List<Ask> asks = new ArrayList<>();
        for (BookStore.Entity e : store.entities(book)) {
            if (!e.level().equals(BookStore.Entity.CONCEPT) || e.members().size() < 2) continue;
            if (only.isEmpty() ? have.containsKey(e.id()) : !only.contains(e.id())) continue;
            asks.add(new Ask(e, List.of(), material(e.members(), conceptOf)));
        }
        return askEach(store, book, asks, writer, parallel, log);
    }

    /**
     * Builds the hierarchy of concept types in a book by combining is-a relations from reviewed rules and kinds from
     * section patterns.
     *
     * @param store the database containing the book's sections, rules, and patterns
     * @param book the name of the book to process
     * @return a mapping from a parent concept name to a set of its normalized child concept names
     */
    static Map<String, Set<String>> kindsOf(BookStore store, String book) throws Exception {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (List<BookStore.Relation> rel : store.relations(book).values()) {
            for (BookStore.Relation r0 : rel) {
                BookStore.Relation r = r0.reviewed();
                if (r == null || !r.kind().equals(BookStore.Relation.IS_A)) continue;
                if (norm(r.child()).equals(norm(r.parent()))) continue;
                out.computeIfAbsent(norm(r.parent()), k -> new LinkedHashSet<>()).add(norm(r.child()));
            }
        }
        for (SectionPattern.Layout l : store.patterns(book).values()) {
            if (l.concept() == null) continue;
            for (SectionPattern.Member m : l.members()) {
                if (!m.relation().equals(SectionPattern.Member.KIND)) continue;
                String child = norm(m.name());
                if (!child.equals(norm(l.concept()))) out.computeIfAbsent(norm(l.concept()), k -> new LinkedHashSet<>()).add(child);
            }
        }
        return out;
    }

    /**
     * Creates abstract generalized concepts from the book's concept tree by grouping concepts that share a common
     * parent and asking questions about their combined material.
     *
     * <p>The method identifies concepts that have two or more kinds and forms a generalized concept for each, whose
     * material consists of the sentences from the generalized concept itself and all its kinds. It replaces any
     * previously stored generalized concepts for the specified book.
     *
     * <p>Returns a string stating the number of generalized concepts found and the number of items (questions and
     * answers) successfully processed, or a count of failed attempts.
     *
     * @param store the database containing the book's entities and relations
     * @param book the name of the book to process
     * @param only a list of specific generalized concept IDs to process; an empty list means all found generalized
     * concepts are processed
     *
     * @param writer a function that takes a question string and returns a reply string
     * @param parallel the maximum number of concurrent calls to the writer
     * @param log a consumer for progress and error messages
     * @return a summary string of the number of generalized concepts found, the number of items processed, and the
     * number of failures
     *
     * @throws Exception if an error occurs during database access or the writing process
     */
    public static String generalizeAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, BookStore.FrameConcept> conceptOf = conceptsByPlace(store, book);
        Map<String, BookStore.Entity> byNorm = new LinkedHashMap<>();
        for (BookStore.Entity e : store.entities(book)) {
            if (e.level().equals(BookStore.Entity.CONCEPT)) byNorm.putIfAbsent(norm(e.name()), e);
        }
        List<BookStore.Entity> generals = new ArrayList<>();
        List<Ask> asks = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : kindsOf(store, book).entrySet()) {
            List<BookStore.Entity> kinds = new ArrayList<>();
            for (String k : e.getValue()) {
                BookStore.Entity ke = byNorm.get(k);
                if (ke != null && !kinds.contains(ke)) kinds.add(ke);
            }
            if (kinds.size() < 2) continue;
            BookStore.Entity own = byNorm.get(e.getKey());
            List<BookStore.Occurrence> members = own == null ? List.of() : own.members();
            String name = own == null ? e.getKey() : own.name();
            BookStore.Entity g = new BookStore.Entity("general:" + e.getKey(), name, BookStore.Entity.GENERAL, members,
                    kinds.stream().map(BookStore.Entity::id).toList());
            generals.add(g);
            List<BookStore.Occurrence> all = new ArrayList<>(members);
            for (BookStore.Entity k : kinds) all.addAll(k.members());
            if (only.isEmpty() || only.contains(g.id())) {
                asks.add(new Ask(g, kinds.stream().map(BookStore.Entity::name).toList(), material(all, conceptOf)));
            }
        }
        store.saveEntities(book, BookStore.Entity.GENERAL, generals);
        log.accept(generals.size() + " generalized concept(s) found by the concept tree");
        return askEach(store, book, asks, writer, parallel, log);
    }
}
