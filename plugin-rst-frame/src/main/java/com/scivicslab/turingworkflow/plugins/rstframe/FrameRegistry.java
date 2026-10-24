package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.HashSet;
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
 * The registry of generalized frames, grown one concrete frame at a time. It starts empty and is kept across books and
 * runs. Each concrete frame of a book (a concept of a section, with the questions its section answers about it) is
 * matched against the generalized frames already made: when one explains the same kind of subject, so that one frame
 * of questions can explain both (an integer type and a floating-point type are both data types), the concrete frame is
 * put into it; otherwise it starts a new generalized frame with a new id, which is a number, not a name. Each time a new
 * id is given, the concept tree is looked at again around it: whether the new frame is a kind of a frame already made,
 * or frames already made are kinds of it. When every concrete frame is placed, a generalized frame with several concrete
 * frames is asked its items over their material put together, the same question as for a concrete frame.
 */
public final class FrameRegistry {

    private FrameRegistry() {
    }

    /** A concrete frame to place: where it is, and the questions its section answers about it. */
    public record Concrete(BookStore.RegistryMember at, List<String> questions) {}

    /** What the registry holds of a generalized frame while it grows: its name, parent, concrete frames' names and questions. */
    public static final class Frame {
        final String id;
        String name;
        String parent;
        final List<String> concepts = new ArrayList<>();
        final List<String> questions = new ArrayList<>();
        final Set<String> words = new HashSet<>();

        Frame(String id, String name, String parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            words.addAll(words(name));
        }

        void add(String concept, List<String> qs) {
            if (!concepts.contains(concept)) concepts.add(concept);
            words.addAll(words(concept));
            for (String q : qs) {
                if (questions.size() < 40) questions.add(q);
                words.addAll(words(q));
            }
        }
    }

    private static final Set<String> STOP = Set.of("what", "which", "when", "where", "does", "how", "the", "and", "for", "are", "can",
            "with", "its", "that", "this", "from", "into", "used", "use", "using", "be", "of", "is", "an", "in", "to", "or",
            "do", "you", "it", "by", "on", "as", "there", "their", "they", "should", "must", "not", "will", "has", "have", "been");

    /** @return the words of a text that say what it is about: lower case, three letters or more, the common ones left out */
    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    /** How many generalized frames are shown to the model when a concrete frame is matched. */
    static final int CANDIDATES = 6;

    /** @return the generalized frames that share the most words with the concrete frame, those that share none left out */
    static List<Frame> candidates(Concrete c, List<Frame> frames) {
        // a frame that already holds a concept of the same name is always shown
        String name = GeneralFrames.norm(c.at().concept());
        List<Frame> same = frames.stream().filter(f -> f.concepts.stream().anyMatch(x -> GeneralFrames.norm(x).equals(name))).toList();
        Set<String> w = words(c.at().concept());
        for (String q : c.questions()) w.addAll(words(q));
        Set<String> own = words(c.at().concept());
        List<Map.Entry<Frame, Integer>> scored = new ArrayList<>();
        for (Frame f : frames) {
            int n = 0;
            for (String x : w) if (f.words.contains(x)) n += own.contains(x) ? 3 : 1;
            if (n > 0) scored.add(Map.entry(f, n));
        }
        scored.sort((a, b) -> b.getValue() - a.getValue());
        List<Frame> out = new ArrayList<>(same);
        for (Map.Entry<Frame, Integer> e : scored) {
            if (out.size() >= CANDIDATES + same.size()) break;
            if (!out.contains(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    private static String describe(Frame f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.id).append(" \"").append(f.name).append("\": frames of ").append(String.join(", ", f.concepts.stream().limit(8).toList()))
          .append(f.concepts.size() > 8 ? ", ..." : "").append('\n');
        for (String q : f.questions.stream().limit(8).toList()) sb.append("    ").append(q).append('\n');
        return sb.toString();
    }

    /** @return the question whether a concrete frame fits one of the generalized frames */
    public static String matchQuestion(Concrete c, List<Frame> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : candidates) sb.append(describe(f));
        StringBuilder qs = new StringBuilder();
        for (String q : c.questions().stream().limit(12).toList()) qs.append("    ").append(q).append('\n');
        return "A frame is the list of questions an explanation of a subject answers. Here is the frame of \"" + c.at().concept()
                + "\", from section " + c.at().number() + " of a textbook:\n" + qs + "\n"
                + "And here are generalized frames already made, each with the subjects it explains and some of its questions:\n\n"
                + sb + "\n"
                + "Does one of these generalized frames explain the same kind of subject as \"" + c.at().concept() + "\", so that one "
                + "frame of questions can explain both? Subjects of one kind share their questions: an integer type and a "
                + "floating-point type are both data types (what values it holds, how much storage, how it is written); a "
                + "variable and a thread are not. Being mentioned together is not enough; the questions must fit. The same subject "
                + "explained in another section (Java, a virtual machine, an interface) always matches the frame that holds it, even "
                + "when this section asks other questions about it: they are more questions of the same frame.\n"
                + "Write \"match: \" and the frame's id, or \"new\" when none fits. Write nothing else.";
    }

    /**
     * @return the question whether a concept is the same thing as the concept of the same name a generalized frame holds:
     *         asked before the match question, since two sections about one subject ask different questions about it
     */
    public static String sameQuestion(Concrete c, Frame f) {
        StringBuilder qs = new StringBuilder();
        for (String q : c.questions().stream().limit(8).toList()) qs.append("    ").append(q).append('\n');
        return "A textbook speaks of \"" + c.at().concept() + "\" in section " + c.at().number() + ", which answers:\n" + qs + "\n"
                + "A frame made earlier holds a subject of the same name:\n" + describe(f) + "\n"
                + "Is \"" + c.at().concept() + "\" in section " + c.at().number() + " the same thing as the subject of that name in the "
                + "frame? Two sections about one thing ask different questions about it; that does not make it two things. Answer "
                + "\"different\" only when the name means another thing here. Write \"same\" or \"different\". Write nothing else.";
    }

    /** The reply to a match question: the id matched ({@code null} for a new frame), and the new name when one is given. */
    public record Match(String id, String name) {}

    /** @return what the reply says; an id not among the candidates counts as no match */
    public static Match readMatch(String reply, List<Frame> candidates) {
        Matcher m = Pattern.compile("(?im)^\\W*match\\W*[:=]\\s*\\W*(F\\d+)").matcher(reply);
        String id = null;
        if (m.find()) {
            String f = m.group(1);
            if (candidates.stream().anyMatch(c -> c.id.equals(f))) id = f;
        }
        Matcher n = Pattern.compile("(?im)^\\W*name\\W*[:=]\\s*(.+)$").matcher(reply);
        String name = n.find() ? n.group(1).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "") : null;
        return new Match(id, name == null || name.isBlank() ? null : name);
    }

    /** @return the question how a new generalized frame stands in the concept tree among frames already made */
    public static String treeQuestion(Frame f, List<Frame> near) {
        StringBuilder sb = new StringBuilder();
        for (Frame n : near) sb.append(describe(n));
        return "A new generalized frame was made for \"" + f.name + "\":\n" + describe(f) + "\n"
                + "Here are generalized frames already made, near it:\n\n" + sb + "\n"
                + "Is \"" + f.name + "\" a kind of one of them, so that every " + f.name + " is one (an integer type is a data "
                + "type; a checked exception is an exception)? Are some of them kinds of \"" + f.name + "\"? Being related is not "
                + "enough: what a thing runs, holds, uses or is made of is not a kind of it (bytecode is not a kind of virtual "
                + "machine; a security model of Java is not a kind of Java), nor is a part, a feature, an operation or a tool of it. "
                + "When in doubt, write none.\n"
                + "Write \"parent: \" and the id of the one it is a kind of, if any; \"children: \" and the ids of those that are "
                + "kinds of it, separated by commas, if any; or \"none\". Write nothing else.";
    }

    /** Where a new frame stands: the frame above it, and the frames that go under it. */
    public record Place(String parent, List<String> children) {}

    /** @return what the reply says, keeping only ids among the near frames */
    public static Place readPlace(String reply, List<Frame> near) {
        Set<String> ids = new HashSet<>();
        for (Frame n : near) ids.add(n.id);
        String parent = null;
        Matcher p = Pattern.compile("(?im)^\\W*parent\\W*[:=]\\s*\\W*(F\\d+)").matcher(reply);
        if (p.find() && ids.contains(p.group(1))) parent = p.group(1);
        List<String> children = new ArrayList<>();
        Matcher c = Pattern.compile("(?im)^\\W*children\\W*[:=]\\s*(.+)$").matcher(reply);
        if (c.find()) {
            Matcher d = Pattern.compile("F\\d+").matcher(c.group(1));
            while (d.find()) if (ids.contains(d.group()) && !d.group().equals(parent) && !children.contains(d.group())) children.add(d.group());
        }
        return new Place(parent, children);
    }

    /** @return whether {@code above} is {@code id} or stands above it in the tree, so that making it a child would close a cycle */
    static boolean above(String above, String id, Map<String, Frame> byId) {
        Set<String> seen = new HashSet<>();
        for (String x = id; x != null && seen.add(x); x = byId.containsKey(x) ? byId.get(x).parent : null) {
            if (x.equals(above)) return true;
        }
        return false;
    }

    /** @return the concrete frames of a book in its order: section by section, concept by concept, those with items */
    static List<Concrete> concretes(BookStore store, String book) throws Exception {
        Map<String, Map<String, List<BookStore.FrameItem>>> items = store.frameItems(book);
        Map<String, List<BookStore.FrameConcept>> frames = store.frameConcepts(book);
        List<Concrete> out = new ArrayList<>();
        for (BookOutline.Node n : store.outline(book)) {
            for (BookStore.FrameConcept c : frames.getOrDefault(n.number(), List.of())) {
                if (Boolean.FALSE.equals(c.kept()) || c.rules().isEmpty()) continue;
                List<BookStore.FrameItem> its = items.getOrDefault(n.number(), Map.of()).getOrDefault(c.concept(), List.of());
                out.add(new Concrete(new BookStore.RegistryMember(book, n.number(), c.concept()), its.stream().map(BookStore.FrameItem::question).toList()));
            }
        }
        return out;
    }

    /**
     * Places every concrete frame of the book not placed yet, one at a time, in the book's order. {@code only} holding
     * {@code reset} empties the registry first. Each model question waits for the one before: the registry it matches
     * against is what the questions before it made.
     *
     * @return how many concrete frames were placed, how many generalized frames they made, in one line
     */
    public static String placeAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        if (only.contains("reset")) store.clearRegistry();
        Map<String, Frame> byId = new LinkedHashMap<>();
        Set<String> placed = new HashSet<>();
        Map<String, Map<String, List<BookStore.FrameItem>>> itemsOf = new LinkedHashMap<>();
        int memberSeq = 0;
        for (BookStore.RegistryFrame r : store.registry()) {
            Frame f = new Frame(r.id(), r.name(), r.parent());
            for (BookStore.RegistryMember m : r.members()) {
                Map<String, List<BookStore.FrameItem>> its = itemsOf.computeIfAbsent(m.book(), b -> {
                    Map<String, List<BookStore.FrameItem>> flat = new LinkedHashMap<>();
                    try {
                        for (var e : store.frameItems(b).entrySet()) for (var c : e.getValue().entrySet()) flat.put(e.getKey() + "\t" + c.getKey(), c.getValue());
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    return flat;
                });
                f.add(m.concept(), its.getOrDefault(m.number() + "\t" + m.concept(), List.of()).stream().map(BookStore.FrameItem::question).toList());
                placed.add(m.book() + "\t" + m.number() + "\t" + m.concept());
                memberSeq++;
            }
            byId.put(f.id, f);
        }
        List<Concrete> todo = concretes(store, book).stream().filter(c -> !placed.contains(book + "\t" + c.at().number() + "\t" + c.at().concept())).toList();
        log.accept("start: " + todo.size() + " concrete frame(s) of " + book + " to place, " + byId.size() + " generalized frame(s) in the registry");
        int matched = 0, made = 0, failed = 0, done = 0;
        for (Concrete c : todo) {
            try {
                List<Frame> cand = candidates(c, new ArrayList<>(byId.values()));
                Match m = null;
                // a frame holding a concept of the same name is asked first whether it is the same thing
                String nm = GeneralFrames.norm(c.at().concept());
                for (Frame f0 : cand) {
                    if (f0.concepts.stream().noneMatch(x -> GeneralFrames.norm(x).equals(nm))) continue;
                    if (writer.apply(sameQuestion(c, f0)).strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").startsWith("same")) {
                        m = new Match(f0.id, null);
                        break;
                    }
                }
                if (m == null) m = cand.isEmpty() ? new Match(null, null) : readMatch(writer.apply(matchQuestion(c, cand)), cand);
                Frame f;
                if (m.id() != null) {
                    // the name stays while frames are placed; nameAll names each frame from all its concrete frames at the end
                    f = byId.get(m.id());
                    matched++;
                } else {
                    String id = String.format("F%04d", byId.size() + 1);
                    while (byId.containsKey(id)) id = "F" + (Integer.parseInt(id.substring(1)) + 1);
                    f = new Frame(id, c.at().concept(), null);
                    store.addRegistryFrame(id, f.name, byId.size() + 1);
                    byId.put(id, f);
                    made++;
                }
                f.add(c.at().concept(), c.questions());
                store.addRegistryMember(f.id, c.at(), ++memberSeq);
                if (m.id() == null) {
                    // a new id: look at the concept tree again around the new frame
                    List<Frame> near = candidates(c, byId.values().stream().filter(x -> x != f).toList());
                    if (!near.isEmpty()) {
                        Place p = readPlace(writer.apply(treeQuestion(f, near)), near);
                        if (p.parent() != null && !above(f.id, p.parent(), byId)) {
                            f.parent = p.parent();
                            store.setRegistryParent(f.id, f.parent);
                        }
                        for (String ch : p.children()) {
                            Frame k = byId.get(ch);
                            if (k != null && !above(ch, f.id, byId)) {
                                k.parent = f.id;
                                store.setRegistryParent(ch, f.id);
                            }
                        }
                        if (p.parent() != null || !p.children().isEmpty()) {
                            log.accept("  tree: " + f.id + " " + f.name + (p.parent() == null ? "" : " under " + p.parent())
                                    + (p.children().isEmpty() ? "" : " over " + String.join(",", p.children())));
                        }
                    }
                }
                done++;
                log.accept("done " + done + "/" + todo.size() + " " + c.at().number() + " " + c.at().concept() + " -> " + f.id + " " + f.name
                        + (m.id() == null ? " (new)" : ""));
            } catch (Exception ex) {
                failed++;
                log.accept("FAILED " + c.at().number() + " " + c.at().concept() + ": " + ex.getMessage());
            }
        }
        return done + " concrete frame(s) of " + book + " placed: " + matched + " into frames already made, " + made + " new; "
                + byId.size() + " generalized frame(s) in the registry, " + failed + " failed";
    }

    /**
     * Gives the relations the book's sections found between their concepts (steps 6 and 7, as the review left them) to
     * the generalized frames those concepts were put into: the network beside the is-a tree. A concept folded into
     * another (a feature, a part, an operation) stands for the frame of the one it was folded into; a relation whose two
     * ends fall into one frame, or whose ends are in no frame, is left out. Asks the model nothing.
     *
     * @return how many relations the network got, by kind, in one line
     */
    public static String relateAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, String> frameOf = new LinkedHashMap<>();
        for (BookStore.RegistryFrame r : store.registry()) {
            for (BookStore.RegistryMember m : r.members()) if (m.book().equals(book)) frameOf.put(m.number() + "\t" + GeneralFrames.norm(m.concept()), r.id());
        }
        // a concept folded into a concrete frame stands for that frame's generalized frame
        for (Map.Entry<String, List<BookStore.FrameConcept>> e : store.frameConcepts(book).entrySet()) {
            for (BookStore.FrameConcept c : e.getValue()) {
                String id = frameOf.get(e.getKey() + "\t" + GeneralFrames.norm(c.concept()));
                if (id == null || c.folded() == null) continue;
                for (String f : c.folded()) frameOf.putIfAbsent(e.getKey() + "\t" + GeneralFrames.norm(f), id);
            }
        }
        List<BookStore.FrameRelation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        for (Map.Entry<String, List<BookStore.Relation>> e : store.relations(book).entrySet()) {
            for (BookStore.Relation r0 : e.getValue()) {
                BookStore.Relation r = r0.reviewed();
                if (r == null) continue;
                String from = frameOf.get(e.getKey() + "\t" + GeneralFrames.norm(r.child()));
                String to = frameOf.get(e.getKey() + "\t" + GeneralFrames.norm(r.parent()));
                if (from == null || to == null || from.equals(to)) continue;
                if (!seen.add(from + "\t" + r.kind() + "\t" + to + "\t" + e.getKey())) continue;
                out.add(new BookStore.FrameRelation(from, r.kind(), to, book, e.getKey()));
                byKind.merge(r.kind(), 1, Integer::sum);
            }
        }
        store.saveFrameRelations(book, out);
        return out.size() + " relation(s) between generalized frames from " + book + ": " + byKind;
    }

    /** @return the question for the name of a generalized frame, from all its concrete frames */
    public static String nameQuestion(BookStore.RegistryFrame f) {
        List<String> concepts = f.members().stream().map(BookStore.RegistryMember::concept).distinct().toList();
        return "A generalized frame explains these subjects, from sections of a textbook: " + String.join("; ", concepts) + ".\n"
                + "Name the kind of subject they all are, in a few words, as a textbook would name it (\"data type\", \"loop\", "
                + "\"exception\"). When one of them names them all, use it. Write \"name: \" and the name. Write nothing else.";
    }

    /** Names every generalized frame with several concrete frames from all of them; a frame of one keeps its concept's name. */
    public static String nameAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        List<BookStore.RegistryFrame> several = store.registry().stream().filter(r -> r.members().size() > 1).toList();
        log.accept("start: naming " + several.size() + " generalized frame(s), " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (BookStore.RegistryFrame r : several) {
                fs.add(pool.submit(() -> {
                    try {
                        Matcher n = Pattern.compile("(?im)^\\W*name\\W*[:=]\\s*(.+)$").matcher(writer.apply(nameQuestion(r)));
                        if (!n.find()) throw new IllegalStateException("the reply names nothing");
                        String name = n.group(1).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "");
                        store.renameRegistryFrame(r.id(), name);
                        log.accept("done " + done.incrementAndGet() + "/" + several.size() + " " + r.id() + ": " + r.name() + " -> " + name);
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + r.id() + ": " + ex.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " generalized frame(s) named, " + failed.get() + " failed";
    }

    /**
     * Asks the items of every generalized frame of the registry with several concrete frames, over their material put
     * together; a frame of one concrete frame keeps that frame's items.
     */
    public static String itemsAll(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel,
            Consumer<String> log) throws Exception {
        Map<String, Map<String, BookStore.FrameConcept>> conceptOf = new LinkedHashMap<>();
        Map<String, Map<String, List<BookStore.FrameItem>>> sectionItems = new LinkedHashMap<>();
        List<BookStore.RegistryFrame> frames = store.registry();
        for (BookStore.RegistryFrame r : frames) {
            for (BookStore.RegistryMember m : r.members()) {
                if (conceptOf.containsKey(m.book())) continue;
                Map<String, BookStore.FrameConcept> c = new LinkedHashMap<>();
                for (var e : store.frameConcepts(m.book()).entrySet()) for (var fc : e.getValue()) c.put(e.getKey() + "\t" + fc.concept(), fc);
                conceptOf.put(m.book(), c);
                Map<String, List<BookStore.FrameItem>> it = new LinkedHashMap<>();
                for (var e : store.frameItems(m.book()).entrySet()) for (var x : e.getValue().entrySet()) it.put(e.getKey() + "\t" + x.getKey(), x.getValue());
                sectionItems.put(m.book(), it);
            }
        }
        List<BookStore.RegistryFrame> several = new ArrayList<>();
        for (BookStore.RegistryFrame r : frames) {
            if (r.members().size() == 1) {
                BookStore.RegistryMember m = r.members().get(0);
                List<BookStore.FrameItem> its = sectionItems.getOrDefault(m.book(), Map.of()).getOrDefault(m.number() + "\t" + m.concept(), List.of());
                store.saveRegistryItems(r.id(), its.stream().map(i -> new BookStore.FrameItem(i.question(),
                        i.rules().stream().map(x -> m.book() + "|" + m.number() + ":" + x).toList())).toList());
            } else if (r.members().size() > 1) {
                several.add(r);
            }
        }
        log.accept("start: " + several.size() + " generalized frame(s) with several concrete frames, " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger();
        AtomicInteger items = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (BookStore.RegistryFrame r : several) {
                fs.add(pool.submit(() -> {
                    try {
                        List<GeneralFrames.Sentence> sentences = new ArrayList<>();
                        List<String> refs = new ArrayList<>();
                        int len = 0;
                        for (BookStore.RegistryMember m : r.members()) {
                            BookStore.FrameConcept c = conceptOf.getOrDefault(m.book(), Map.of()).get(m.number() + "\t" + m.concept());
                            if (c == null) continue;
                            for (BookStore.FrameRule rule : c.rules()) {
                                if (len > GeneralFrames.MAX_TEXT) break;
                                sentences.add(new GeneralFrames.Sentence(m.number(), m.concept(), rule.rule(), rule.text()));
                                refs.add(m.book() + "|" + m.number() + ":" + rule.rule());
                                len += Math.min(400, rule.text().length()) + 40;
                            }
                        }
                        List<String> kinds = r.members().stream().map(BookStore.RegistryMember::concept).distinct().toList();
                        List<BookStore.FrameItem> got = GeneralFrames.readItems(writer.apply(GeneralFrames.itemsQuestion(r.name(), kinds, sentences)), sentences);
                        if (got.isEmpty()) throw new IllegalStateException("the reply names no item a sentence answers");
                        // the sentences are named "number:rule"; the registry names them with their book
                        Map<String, String> withBook = new LinkedHashMap<>();
                        for (int i = 0; i < sentences.size(); i++) withBook.put(sentences.get(i).ref(), refs.get(i));
                        store.saveRegistryItems(r.id(), got.stream().map(i -> new BookStore.FrameItem(i.question(),
                                i.rules().stream().map(x -> withBook.getOrDefault(x, book + "|" + x)).toList())).toList());
                        items.addAndGet(got.size());
                        log.accept("done " + done.incrementAndGet() + "/" + several.size() + " " + r.id() + " " + r.name() + ": " + r.members().size()
                                + " concrete frame(s), " + got.size() + " item(s)");
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + r.id() + " " + r.name() + ": " + ex.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " generalized frame(s) given " + items.get() + " item(s), " + failed.get() + " failed";
    }
}
