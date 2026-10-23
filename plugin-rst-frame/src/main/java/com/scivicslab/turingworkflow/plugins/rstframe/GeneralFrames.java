package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Generalized frames, built on the concrete frames of the sections ({@link FrameMaterial}, {@link FrameItems}), in three
 * steps.
 * <ol>
 * <li>{@link #identifyAll}: the concepts of all sections are recognized as concepts of the book. Concepts whose names
 * are the same once case and plural are set aside are candidates; the model is shown each one's items (the questions
 * its section answers about it) and says which are the same concept, judged by what is said of them. Each concept of the
 * book gets an id and the sections it appears in.</li>
 * <li>{@link #mergeAll}: the frame material of a concept found in several sections is put together, and the model is
 * asked the same question as for a concrete frame (which questions do these sentences answer, and which sentence answers
 * which), over the whole book.</li>
 * <li>{@link #generalizeAll}: the concepts are joined into abstract ones by the concept tree (the is-a relations steps 6
 * and 7 left, and the kinds the pattern step named). A concept with two kinds or more becomes a generalized concept; the
 * material of it and of its kinds is put together and asked the same question, the items being those an explanation of
 * any of its kinds answers.</li>
 * </ol>
 */
public final class GeneralFrames {

    private GeneralFrames() {
    }

    /** A sentence of a merged material: the section and concept it was gathered for, its rule, its text. */
    public record Sentence(String number, String concept, String rule, String text) {
        /** @return how the sentence is named in the stored items: the section and the rule */
        public String ref() {
            return number + ":" + rule;
        }
    }

    /** @return the name with case, a parenthesized remark and an English plural set aside */
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

    /** @return the question whether the appearances of one name are one concept */
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

    /** @return the groups the reply makes, by index into the appearances; an appearance the reply leaves out is a group alone */
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

    /** The concrete-frame concepts of the book that stay on their lists and got rules, as appearances. */
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
     * Recognizes the concepts of the book: one id per concept, with the sections it appears in. Replaces the concepts
     * stored before.
     *
     * @return how many concepts the book has, and how many of them appear in more than one section, in one line
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
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String n : asked) {
                fs.add(pool.submit(() -> {
                    List<BookStore.Occurrence> occ = byName.get(n);
                    try {
                        List<List<Integer>> g = readGroups(writer.apply(identifyQuestion(n, occ, itemsOf)), occ.size());
                        groups.put(n, g);
                        log.accept("done " + done.incrementAndGet() + "/" + asked.size() + " " + n + ": " + occ.size() + " appearance(s), "
                                + g.size() + " concept(s)");
                    } catch (Exception ex) {
                        // a name the model could not judge stays one concept
                        failed.incrementAndGet();
                        log.accept("FAILED " + n + ": " + ex.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        List<BookStore.Entity> entities = new ArrayList<>();
        int shared = 0;
        for (Map.Entry<String, List<BookStore.Occurrence>> e : byName.entrySet()) {
            List<BookStore.Occurrence> occ = e.getValue();
            List<List<Integer>> g = groups.getOrDefault(e.getKey(), List.of(java.util.stream.IntStream.range(0, occ.size()).boxed().toList()));
            for (int k = 0; k < g.size(); k++) {
                List<BookStore.Occurrence> members = g.get(k).stream().map(occ::get).toList();
                String id = g.size() == 1 ? e.getKey() : e.getKey() + "#" + (k + 1);
                entities.add(new BookStore.Entity(id, members.get(0).concept(), BookStore.Entity.CONCEPT, members, List.of()));
                if (members.size() > 1) shared++;
            }
        }
        store.saveEntities(book, BookStore.Entity.CONCEPT, entities);
        return entities.size() + " concept(s) of " + book + ", " + shared + " of them in more than one section, " + failed.get() + " failed";
    }

    // ---- steps 2 and 3: the same question over material put together

    /** @return the question asked of material put together from several sections, for {@code name} and, when given, its kinds */
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

    /** @return the items the reply names, each with the sentences that answer it, named by section and rule */
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

    /** The longest text of the sentences put into one question, so it stays within what the model reads well. */
    static final int MAX_TEXT = 16000;

    /** @return the sentences gathered for the appearances, in section order, up to {@link #MAX_TEXT} */
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

    /** One question to ask: an entity, the kinds it is asked with, and its sentences. */
    private record Ask(BookStore.Entity entity, List<String> kinds, List<Sentence> sentences) {}

    private static String askEach(BookStore store, String book, List<Ask> asks, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        log.accept("start: " + asks.size() + " concept(s), " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger items = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Ask a : asks) {
                fs.add(pool.submit(() -> {
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
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " concept(s) of " + book + " given " + items.get() + " item(s), " + failed.get() + " failed";
    }

    /**
     * Puts together the material of each concept that appears in more than one section and asks its items over the
     * whole book. A concept of one section keeps its section's items.
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

    /** @return the is-a edges of the book, child name to parent name, normalized: the reviewed relations and the pattern's kinds */
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
     * Joins the concepts into abstract ones, one level up the concept tree: a concept with two kinds or more that are
     * concepts of the book (their material gathered) becomes a generalized concept, whose material is its own and its
     * kinds', asked the same question. Replaces the generalized concepts stored before.
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
