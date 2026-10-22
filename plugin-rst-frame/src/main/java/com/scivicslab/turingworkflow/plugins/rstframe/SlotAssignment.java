package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assigns each paragraph of a concept to what can be said of the concept's class, by asking a writer one question at
 * a time ({@code FrameMakingWorkflow_261022_oo01}, step 3; {@code ConceptClasses_261022_oo01}).
 *
 * <p>The concepts and their paragraphs come from step 2's concept tree. Every lookup in the class tree is done here,
 * through {@link ConceptClasses}; the writer is only asked to judge what it is shown. For one concept, in order:</p>
 * <ol>
 *   <li>Where it is: the class of the same name if there is one; else the root classes are shown and the writer says
 *       which one the concept is a kind of, then that class's subclasses, until it stops. A concept that is a kind of
 *       none of them gets a general kind named by the writer, which becomes a new class.</li>
 *   <li>Each paragraph: what can be said of the class (its slots, inherited ones included) is shown and the writer
 *       says which question the paragraph answers, or names a new one, or says it is aside.</li>
 *   <li>A new question: the class and its superclasses are shown and the writer says the broadest one the question
 *       can be asked of. The slot is added there, and every class under it inherits it from then on.</li>
 * </ol>
 * <p>Classes and slots added are proposals; the result lists them with the assignments.</p>
 */
public final class SlotAssignment implements Questioner {

    /**
     * One concept and its paragraphs (or a frame's blanks), each kept with the reference the result writes for it; for a
     * frame, the sub-frame each blank names ({@code :frame}) and the frame file the concept came from.
     */
    record Work(String name, List<String> refs, List<ConceptTree.Unit> units, Map<String, String> frames, String source) {
    }

    /** What the units are: a book's paragraphs, or an explanation frame's blanks. The questions are worded for it. */
    private enum Material { BOOK, FRAME }

    /** Where a concept's paragraphs went: its class, and for each slot (or {@code :aside}) the paragraphs. */
    record Placed(String concept, String cls, Map<String, List<String>> slots) {
    }

    private static final Pattern SUBJECT = Pattern.compile("^\\(subject \"[^\"]*\" \"((?:[^\"\\\\]|\\\\.)*)\"(.*)\\)\\s*$");
    private static final Pattern PARAGRAPHS = Pattern.compile(":paragraphs \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern REF = Pattern.compile("\\S+ / \\S+|\\S+");
    /** {@code NEW: name: text}, or {@code name: text} as writers often answer: a name and what it stands for. */
    private static final Pattern NAMED = Pattern.compile("(?i)^\\s*(?:NEW\\s*:\\s*)?([^:]+?)\\s*:\\s*(.+?)\\s*$");
    private static final Pattern SAME = Pattern.compile("(?i)^\\s*SAME\\s*:?\\s*(.+?)\\s*$");
    private static final String ASIDE = ":aside";

    private enum Phase { DEFINE, LOCATE, CONFIRM, SLOTS, WHERE, DONE }

    private final String book;
    private final ConceptClasses classes;
    private final List<Work> works = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> grown = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    /** The book's rule files, as they were given, so that a reader of the result can find each paragraph. */
    private final List<String> files;
    private final Material material;
    /** For each frame file whose concept has been placed, the class it became: the type of a slot whose blank names it. */
    private final Map<String, String> frameClasses = new LinkedHashMap<>();

    private int concept = -1;
    private Phase phase = Phase.LOCATE;
    /** While locating: the class the concept is being taken down from, {@code null} at the roots. */
    private String at;
    private String cls;
    private int paragraph;
    private String newSlot;
    private String newQuestion;
    private boolean retried;
    /** The last answer named a new question after the concept itself. */
    private boolean namedItself;
    /** For a frame: the one-line definition of the concept the writer gave, the class's documentation if it becomes one. */
    private String definition;
    /** The class the writer said the concept is the same thing as, until that is confirmed. */
    private String sameAs;
    /** Classes the writer said, when asked to confirm, the concept is not the same thing as. */
    private final Set<String> notSame = new LinkedHashSet<>();

    /**
     * @param book      the book's name
     * @param files     the book's rule files, path to text
     * @param subjects  step 2's concept tree: {@code (subject "n" "name" ... :paragraphs "...")} lines
     * @param classes   the concept classes to place the concepts in; this work adds to them
     */
    public SlotAssignment(String book, Map<String, String> files, String subjects, ConceptClasses classes) {
        this.book = book;
        this.classes = classes;
        this.files = List.copyOf(files.keySet());
        this.material = Material.BOOK;
        Map<String, ConceptTree.Unit> byRef = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            for (ConceptTree.Unit u : ConceptTree.units(e.getKey(), RuleFile.parse(e.getValue()))) {
                byRef.putIfAbsent(shortFile(u.file()) + " / " + u.rule(), u);
                byRef.putIfAbsent(u.rule(), u);
            }
        }
        for (String line : subjects.split("\\R")) {
            Matcher m = SUBJECT.matcher(line.strip());
            if (!m.matches()) continue;
            Matcher p = PARAGRAPHS.matcher(m.group(2));
            if (!p.find()) continue;
            List<String> refs = new ArrayList<>();
            List<ConceptTree.Unit> units = new ArrayList<>();
            // paragraphs separated by ';' when file names hold spaces (TextbookConceptTree), else by spaces
            List<String> found = new ArrayList<>();
            if (p.group(1).contains(";")) {
                for (String x : p.group(1).split(";")) if (!x.isBlank()) found.add(x.strip());
            } else {
                Matcher r = REF.matcher(p.group(1));
                while (r.find()) found.add(r.group());
            }
            for (String ref : found) {
                ConceptTree.Unit u = byRef.get(ref);
                if (u == null) {
                    notes.add(m.group(1) + ": no paragraph " + ref + " in the rule files");
                    continue;
                }
                refs.add(ref);
                units.add(u);
            }
            if (!units.isEmpty()) works.add(new Work(m.group(1).replace("\\\"", "\""), refs, units, Map.of(), null));
        }
        nextConcept();
    }

    private SlotAssignment(String book, List<String> files, List<Work> works, ConceptClasses classes) {
        this.book = book;
        this.classes = classes;
        this.files = List.copyOf(files);
        this.material = Material.FRAME;
        this.works.addAll(works);
        nextConcept();
    }

    /**
     * The same questions asked of an integrated explanation frame, so that its blanks become the slots of concept classes
     * ({@code ConceptClasses_261022_oo01}). Each sub-frame file ({@code top.message-kind.lisp}) is one concept; in the top
     * frame, the blanks under one {@code *-part} rule are one concept, and the blanks under no part are the concept the
     * frame explains. A blank that names a sub-frame ({@code :frame}) becomes a slot typed with the sub-frame's class.
     *
     * @param top        the top frame's name, {@code explain-agent-harness}
     * @param frameFiles the top frame and its sub-frames, path to text
     */
    public static SlotAssignment fromFrame(String top, Map<String, String> frameFiles, ConceptClasses classes) {
        List<Work> subs = new ArrayList<>();
        String topText = null;
        String topPath = null;
        for (Map.Entry<String, String> e : frameFiles.entrySet()) {
            String file = shortFile(e.getKey());
            if (file.equals(top + ".lisp")) {
                topText = e.getValue();
                topPath = e.getKey();
            } else if (file.startsWith(top + ".") && file.endsWith(".lisp")) {
                String part = file.substring(top.length() + 1, file.length() - 5);
                RuleFile f = RuleFile.parse(e.getValue());
                Work w = new Work(part.replace('-', ' '), new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), file);
                for (RuleFile.Rule r : f.rules()) addBlank(w, e.getKey(), r);
                if (!w.units().isEmpty()) subs.add(w);
            }
        }
        List<Work> works = new ArrayList<>(subs);
        if (topText != null) {
            RuleFile f = RuleFile.parse(topText);
            Map<String, Work> parts = new LinkedHashMap<>();
            String whole = top.replaceFirst("^explain-", "").replace('-', ' ');
            if (!f.rules().isEmpty()) walkFrame(f, topPath, f.rules().get(0).name(), whole, parts, new java.util.HashSet<>());
            Work w = parts.remove(whole);
            works.addAll(parts.values());
            if (w != null) works.add(w);
        }
        return new SlotAssignment(top, List.copyOf(frameFiles.keySet()), works, classes);
    }

    private static void walkFrame(RuleFile f, String path, String name, String part, Map<String, Work> parts, Set<String> seen) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || !seen.add(name)) return;
        String here = name.endsWith("-part") ? name.substring(0, name.length() - 5).replace('-', ' ') : part;
        addBlank(parts.computeIfAbsent(here, k -> new Work(k, new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), null)), path, r);
        for (RuleFile.Token t : r.items()) if (t.kind() == RuleFile.Token.Kind.SYMBOL) walkFrame(f, path, t.raw(), here, parts, seen);
    }

    private static void addBlank(Work w, String path, RuleFile.Rule r) {
        String instruction = r.attr(":instruction");
        if (instruction == null) return;
        String sample = r.attr(":sample");
        w.refs().add(r.name());
        w.units().add(new ConceptTree.Unit(path, r.name(), "", instruction,
                sample == null ? List.of() : List.of("an example of what was written: " + cut(sample, 300))));
        String frame = r.attr(":frame");
        if (frame != null) w.frames().put(r.name(), frame);
    }

    @Override
    public String nextQuestion() {
        while (phase == Phase.LOCATE && cls == null) {
            if (at == null || !classes.subclasses(at).isEmpty()) break;
            place(at); // nothing narrower to choose from
        }
        while (phase == Phase.SLOTS && paragraph >= work().units().size()) nextConcept();
        return switch (phase) {
            case DEFINE -> defineQuestion();
            case CONFIRM -> confirmQuestion();
            case LOCATE -> locateQuestion();
            case SLOTS -> slotQuestion();
            case WHERE -> whereQuestion();
            case DONE -> null;
        };
    }

    @Override
    public void answer(String reply) {
        String line = firstLine(reply);
        switch (phase) {
            case DEFINE -> answerDefine(line);
            case CONFIRM -> answerConfirm(line);
            case LOCATE -> answerLocate(line);
            case SLOTS -> answerSlot(line);
            case WHERE -> answerWhere(line);
            case DONE -> { }
        }
    }

    // ---- 0. what the concept is, for a frame, whose blanks are instructions and not sentences about it ----

    String defineQuestion() {
        Work w = work();
        StringBuilder sb = new StringBuilder();
        sb.append("An explanation frame has blanks for writing about ").append(w.name()).append(". What its blanks ask the writer to write:\n")
          .append(saying(w)).append("\nWhat is ").append(w.name()).append("? Answer with exactly one line: one English sentence that ")
          .append("says what kind of thing it is and what it is for, without naming any particular product.");
        return retry(sb);
    }

    private void answerDefine(String line) {
        if (line.isBlank() && !retried) {
            retried = true;
            return;
        }
        definition = line.isBlank() ? null : line;
        phase = Phase.LOCATE;
        retried = false;
    }

    // ---- 1. where the concept is ----

    String locateQuestion() {
        Work w = work();
        StringBuilder sb = new StringBuilder();
        if (material == Material.FRAME) {
            sb.append("An explanation frame has blanks for writing about ").append(w.name()).append('.');
            if (definition != null) sb.append(' ').append(w.name()).append(": ").append(definition);
            sb.append("\nWhat its blanks ask the writer to write:\n").append(saying(w));
        } else {
            sb.append("A document explains ").append(w.name()).append(". What the document says of it:\n").append(saying(w));
        }
        if (!notSame.isEmpty()) sb.append("\nIt is not the same thing as ").append(String.join(", ", notSame)).append(".\n");
        if (at == null) {
            sb.append("\nKinds of thing already known, each with what it is:\n");
            for (ConceptClasses.Kind k : classes.roots()) sb.append("- ").append(k.name()).append(": ").append(k.documentation()).append('\n');
            sb.append("\nIs ").append(w.name()).append(" a kind of one of these? Answer with exactly one line:\n")
              .append("the kind's name, written exactly as it is in the list, if ").append(w.name()).append(" is a kind of it\n")
              .append("NEW: <kind>: <what that kind is>  if it is a kind of none of them. Name the general kind of thing ")
              .append(w.name()).append(" is, not ").append(w.name())
              .append(" itself: a kind broad enough that other things of the same sort would be kinds of it too.\n")
              .append("SAME <name>  if ").append(w.name()).append(" is not a kind of one of them but that very thing, under another name");
        } else {
            sb.append('\n').append(w.name()).append(" is a kind of ").append(at).append(". The narrower kinds of ").append(at)
              .append(" already known, each with what it is:\n");
            for (ConceptClasses.Kind k : classes.subclasses(at)) sb.append("- ").append(k.name()).append(": ").append(k.documentation()).append('\n');
            sb.append("\nIs ").append(w.name()).append(" a kind of one of these narrower kinds? Answer with exactly one line:\n")
              .append("the kind's name, written exactly as it is in the list, if ").append(w.name()).append(" is a kind of it\n")
              .append("HERE  if it is a kind of none of them, but still a kind of ").append(at).append('\n')
              .append("NEW: <kind>: <what that kind is>  if it belongs to a narrower kind of ").append(at)
              .append(" not in the list, broad enough that other things of the same sort would be kinds of it too\n")
              .append("SAME <name>  if ").append(w.name()).append(" is not a kind of one of them but that very thing, under another name");
        }
        return retry(sb);
    }

    private void answerLocate(String line) {
        if (at != null && line.equalsIgnoreCase("HERE")) {
            place(at);
            return;
        }
        Matcher same = SAME.matcher(line);
        if (same.matches() && classes.find(same.group(1)) != null && !notSame.contains(classes.find(same.group(1)))) {
            sameAs = classes.find(same.group(1));
            phase = Phase.CONFIRM;
            retried = false;
            return;
        }
        Matcher m = NAMED.matcher(line);
        String chosen = ConceptClasses.lispName(m.matches() ? m.group(1) : line);
        List<ConceptClasses.Kind> candidates = at == null ? classes.roots() : classes.subclasses(at);
        if (candidates.stream().anyMatch(k -> k.name().equals(chosen))) {
            descend(chosen);
            return;
        }
        if (m.matches() && !chosen.isEmpty() && !chosen.equals(ConceptClasses.lispName(work().name()))) {
            if (classes.find(chosen) != null) {
                descend(chosen);
                return;
            }
            classes.define(chosen, at, m.group(2));
            added.add(chosen);
            place(chosen);
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(work().name() + ": no kind could be read from \"" + line + "\"; " + (at == null ? "made a class of its own" : "placed under " + at));
        place(at);
    }

    String confirmQuestion() {
        Work w = work();
        StringBuilder sb = new StringBuilder();
        sb.append("Two names, and what each stands for:\n\n").append(w.name()).append(": ");
        if (definition != null) sb.append(definition).append('\n');
        sb.append(material == Material.FRAME ? "What an explanation frame asks the writer to write about it:\n" : "What a document says of it:\n")
          .append(saying(w)).append('\n').append(sameAs).append(": ").append(classes.chain(sameAs).get(0).documentation())
          .append("\nWhat can be said of it:\n");
        for (ConceptClasses.Slot s : classes.whatCanBeSaid(sameAs)) sb.append("- ").append(s.question()).append('\n');
        sb.append("\nAre ").append(w.name()).append(" and ").append(sameAs).append(" one and the same thing, under two names? ")
          .append("Something that is only a kind of the other, a part of it, or a thing of the same sort is not the same thing. ")
          .append("Answer with exactly one word: YES or NO.");
        return retry(sb);
    }

    private void answerConfirm(String line) {
        String a = line.strip().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
        if (a.equals("YES")) {
            String c = sameAs;
            sameAs = null;
            startSlots(c);
            return;
        }
        if (!a.equals("NO") && !retried) {
            retried = true;
            return;
        }
        notSame.add(sameAs);
        sameAs = null;
        phase = Phase.LOCATE;
        retried = false;
    }

    private void descend(String kind) {
        at = kind;
        retried = false;
        if (classes.subclasses(kind).isEmpty()) place(kind);
    }

    /** Makes the concept a class under {@code superclass} ({@code null} for none) and turns to its paragraphs. */
    private void place(String superclass) {
        Work w = work();
        String name = ConceptClasses.lispName(w.name());
        if (classes.find(name) == null) {
            classes.define(name, superclass, definition != null ? definition : cut(w.units().get(0).nucleus(), 160));
            added.add(name);
        }
        startSlots(name);
    }

    // ---- 2. what each paragraph says of it ----

    String slotQuestion() {
        Work w = work();
        ConceptTree.Unit u = w.units().get(paragraph);
        List<ConceptClasses.Kind> chain = classes.chain(cls);
        List<ConceptClasses.Slot> slots = classes.whatCanBeSaid(cls);
        StringBuilder sb = new StringBuilder();
        sb.append(material == Material.FRAME ? "An explanation frame is about " : "A document explains ").append(w.name()).append('.');
        for (int i = 1; i < chain.size(); i++) {
            sb.append(' ').append(i == 1 ? w.name() : chain.get(i - 1).name()).append(" is a kind of ").append(chain.get(i).name());
            if (!chain.get(i).documentation().isBlank()) sb.append(": ").append(chain.get(i).documentation());
            sb.append('.');
        }
        if (slots.isEmpty()) {
            sb.append("\n\nNothing has been asked of ").append(w.name()).append(" yet.\n");
        } else {
            sb.append("\n\nWhat can be said of ").append(w.name()).append(", one question each:\n");
            for (ConceptClasses.Slot s : slots) sb.append("- ").append(s.name()).append(": ").append(s.question()).append('\n');
        }
        sb.append(material == Material.FRAME
                ? "\nA blank of the frame: what the writer is asked to write in it, and an example of what was written:\n"
                : "\nA paragraph of the document:\n").append(text(u)).append("\n\n");
        String unit = material == Material.FRAME ? "blank" : "paragraph";
        String does = material == Material.FRAME ? " ask for" : " answer";
        if (!slots.isEmpty()) sb.append("Which one of these questions does the ").append(unit).append(does).append("? Answer with exactly one line: the name before the colon, or ");
        else sb.append("What question about ").append(w.name()).append(" does the ").append(unit).append(does).append("? Answer with exactly one line: ");
        sb.append("NEW: <name>: <question>").append(slots.isEmpty() ? "" : " if it answers none of them")
          .append(". The name is short, and the question says \"it\" for ").append(w.name())
          .append(", so that it could be asked word for word of anything of the same kind (\"Which values does it take, and what does each do\", not \"")
          .append("What is ").append(w.name()).append("\"). Or answer ASIDE if it ")
          .append(material == Material.FRAME ? "asks for nothing about " : "says nothing about ").append(w.name())
          .append(material == Material.FRAME ? " itself, such as the title or the scope of the explanation." : " itself.");
        return retry(sb);
    }

    private void answerSlot(String line) {
        if (line.equalsIgnoreCase("ASIDE")) {
            assign(ASIDE);
            return;
        }
        List<ConceptClasses.Slot> slots = classes.whatCanBeSaid(cls);
        Matcher m = NAMED.matcher(line);
        String name = ConceptClasses.lispName(m.matches() ? m.group(1) : line);
        if (slots.stream().anyMatch(s -> s.name().equals(name))) {
            assign(name);
            return;
        }
        if (m.matches() && !name.isEmpty()) {
            if (namesTheConcept(name, m.group(2)) && !retried) {
                retried = true;
                namedItself = true;
                return;
            }
            newSlot = name;
            newQuestion = m.group(2);
            // a question that still names the concept is a question about it alone
            if (classes.chain(cls).size() == 1 || namesTheConcept(name, newQuestion)) {
                addSlot(cls);
                return;
            }
            phase = Phase.WHERE;
            retried = false;
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(work().name() + " / " + work().refs().get(paragraph) + ": no question could be read from \"" + line + "\"");
        paragraph++;
        retried = false;
    }

    // ---- 3. how far up a new question reaches ----

    String whereQuestion() {
        Work w = work();
        StringBuilder sb = new StringBuilder();
        sb.append(material == Material.FRAME ? "A blank of an explanation frame about " : "A paragraph of a document about ").append(w.name())
          .append(material == Material.FRAME ? " asks a question not asked of it before:\n" : " answers a question not asked of it before:\n")
          .append(newSlot).append(": ").append(newQuestion).append("\n\n")
          .append(w.name()).append(" is a kind of each of these, from the narrowest to the broadest:\n");
        for (ConceptClasses.Kind k : classes.chain(cls)) {
            sb.append("- ").append(k.name());
            if (!k.documentation().isBlank()) sb.append(": ").append(k.documentation());
            if (!k.name().equals(cls)) {
                List<String> others = classes.subclasses(k.name()).stream().map(ConceptClasses.Kind::name).toList();
                sb.append("\n  its kinds known so far: ").append(String.join(", ", others));
            }
            sb.append('\n');
        }
        sb.append("\nWhich is the broadest of these that the question can be asked of, and makes sense for each of its kinds, not only for ")
          .append(w.name()).append("? Answer with exactly one line: its name, written exactly as it is in the list.");
        return retry(sb);
    }

    private void answerWhere(String line) {
        String chosen = ConceptClasses.lispName(line);
        if (classes.chain(cls).stream().anyMatch(k -> k.name().equals(chosen))) {
            addSlot(chosen);
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(work().name() + ": no class for " + newSlot + " could be read from \"" + line + "\"; added to " + cls);
        addSlot(cls);
    }

    private void addSlot(String to) {
        String frame = work().frames().get(work().refs().get(paragraph));
        classes.addSlot(to, newSlot, newQuestion, frame == null ? null : frameClasses.get(frame));
        if (!added.contains(to)) grown.add(to);
        String slot = newSlot;
        newSlot = null;
        newQuestion = null;
        phase = Phase.SLOTS;
        assign(slot);
    }

    // ---- moving on ----

    private void assign(String slot) {
        placed.get(placed.size() - 1).slots().computeIfAbsent(slot, k -> new ArrayList<>()).add(work().refs().get(paragraph));
        paragraph++;
        retried = false;
    }

    private void startSlots(String c) {
        cls = c;
        if (work().source() != null) frameClasses.put(work().source(), c);
        placed.add(new Placed(work().name(), c, new LinkedHashMap<>()));
        phase = Phase.SLOTS;
        paragraph = 0;
        retried = false;
    }

    private void nextConcept() {
        concept++;
        at = null;
        cls = null;
        retried = false;
        definition = null;
        sameAs = null;
        notSame.clear();
        if (concept >= works.size()) {
            phase = Phase.DONE;
            return;
        }
        String found = classes.find(work().name());
        if (found != null) startSlots(found);
        else phase = material == Material.FRAME ? Phase.DEFINE : Phase.LOCATE;
    }

    private Work work() {
        return works.get(concept);
    }

    // ---- the result ----

    /** @return each concept's class and where its paragraphs went, in the order of the tree */
    public List<Placed> placed() {
        return List.copyOf(placed);
    }

    /** @return the classes this work defined, in order */
    public List<String> added() {
        return List.copyOf(added);
    }

    /** @return the classes that were there before and got slots added */
    public List<String> grown() {
        return List.copyOf(grown);
    }

    /** @return what could not be read from the writer's answers */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** The classes added or grown, as {@code defclass} forms, then one {@code (assign ...)} per concept. */
    public String resultFile(String from) {
        StringBuilder sb = new StringBuilder();
        sb.append(";;; Paragraphs of ").append(book).append(" assigned to what can be said of each concept's class\n")
          .append(";;; (ConceptClasses_261022_oo01, FrameMakingWorkflow_261022_oo01 step 3), from ").append(from).append(".\n")
          .append(";;; The classes below were added, or had slots added, while assigning. They are proposals: whether they join\n")
          .append(";;; rst-on-lisp/concepts/ is decided by a person.\n");
        for (String f : files) sb.append(material == Material.FRAME ? ";;; frame-file: " : ";;; book-file: ").append(f).append('\n');
        for (String n : notes) sb.append(";;; note: ").append(n).append('\n');
        sb.append("(in-package :concepts)\n\n");
        for (String c : grown) sb.append(";; slots added to a class that was there before\n").append(classes.source(c)).append("\n\n");
        for (String c : added) sb.append(classes.source(c)).append("\n\n");
        for (Placed p : placed) {
            sb.append("(assign \"").append(RuleFile.escape(p.concept())).append("\" ").append(p.cls());
            for (Map.Entry<String, List<String>> e : p.slots().entrySet()) {
                sb.append("\n  (").append(e.getKey());
                for (String r : e.getValue()) sb.append(" \"").append(RuleFile.escape(r)).append('"');
                sb.append(')');
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    // ---- helpers ----

    /** @return whether a new question is the concept's own name, or names the concept */
    private boolean namesTheConcept(String slot, String question) {
        String c = ConceptClasses.lispName(work().name());
        return slot.equals(c) || (" " + ConceptClasses.lispName(question).replace('-', ' ') + " ").contains(" " + c.replace('-', ' ') + " ");
    }

    private String retry(StringBuilder sb) {
        if (retried && namedItself) {
            namedItself = false;
            sb.append("\nYour previous answer named ").append(work().name()).append(" in the question. Write the question with \"it\" in its place, ")
              .append("so that it can be asked word for word of anything of the same kind, and answer with one line in the form asked.");
            return sb.toString();
        }
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    private static String saying(Work w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.units().size() && i < 3; i++) sb.append("- ").append(cut(w.units().get(i).nucleus(), 300)).append('\n');
        return sb.toString();
    }

    private static String text(ConceptTree.Unit u) {
        StringBuilder sb = new StringBuilder(cut(u.nucleus(), 700));
        for (String s : u.satellites()) sb.append('\n').append(s);
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + " ..." : s;
    }

    private static String firstLine(String reply) {
        for (String l : reply.strip().split("\\R")) {
            String s = l.strip().replaceFirst("^[-*]\\s*", "").replaceAll("^[`\"']|[`\"'.]$", "").strip();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static String shortFile(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }
}
