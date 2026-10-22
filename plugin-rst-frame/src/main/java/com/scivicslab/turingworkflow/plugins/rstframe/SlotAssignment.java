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

    /** One concept of the tree and its paragraphs, each kept with the reference the tree wrote for it. */
    record Work(String name, List<String> refs, List<ConceptTree.Unit> units) {
    }

    /** Where a concept's paragraphs went: its class, and for each slot (or {@code :aside}) the paragraphs. */
    record Placed(String concept, String cls, Map<String, List<String>> slots) {
    }

    private static final Pattern SUBJECT = Pattern.compile("^\\(subject \"[^\"]*\" \"((?:[^\"\\\\]|\\\\.)*)\"(.*)\\)\\s*$");
    private static final Pattern PARAGRAPHS = Pattern.compile(":paragraphs \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern REF = Pattern.compile("\\S+ / \\S+|\\S+");
    /** {@code NEW: name: text}, or {@code name: text} as writers often answer: a name and what it stands for. */
    private static final Pattern NAMED = Pattern.compile("(?i)^\\s*(?:NEW\\s*:\\s*)?([^:]+?)\\s*:\\s*(.+?)\\s*$");
    private static final String ASIDE = ":aside";

    private enum Phase { LOCATE, SLOTS, WHERE, DONE }

    private final String book;
    private final ConceptClasses classes;
    private final List<Work> works = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> grown = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    /** The book's rule files, as they were given, so that a reader of the result can find each paragraph. */
    private final List<String> files;

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
            Matcher r = REF.matcher(p.group(1));
            while (r.find()) {
                ConceptTree.Unit u = byRef.get(r.group());
                if (u == null) {
                    notes.add(m.group(1) + ": no paragraph " + r.group() + " in the rule files");
                    continue;
                }
                refs.add(r.group());
                units.add(u);
            }
            if (!units.isEmpty()) works.add(new Work(m.group(1).replace("\\\"", "\""), refs, units));
        }
        nextConcept();
    }

    @Override
    public String nextQuestion() {
        while (phase == Phase.LOCATE && cls == null) {
            if (at == null || !classes.subclasses(at).isEmpty()) break;
            place(at); // nothing narrower to choose from
        }
        while (phase == Phase.SLOTS && paragraph >= work().units().size()) nextConcept();
        return switch (phase) {
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
            case LOCATE -> answerLocate(line);
            case SLOTS -> answerSlot(line);
            case WHERE -> answerWhere(line);
            case DONE -> { }
        }
    }

    // ---- 1. where the concept is ----

    String locateQuestion() {
        Work w = work();
        StringBuilder sb = new StringBuilder();
        sb.append("A document explains ").append(w.name()).append(". What the document says of it:\n").append(saying(w));
        if (at == null) {
            sb.append("\nKinds of thing already known, each with what it is:\n");
            for (ConceptClasses.Kind k : classes.roots()) sb.append("- ").append(k.name()).append(": ").append(k.documentation()).append('\n');
            sb.append("\nIs ").append(w.name()).append(" a kind of one of these? Answer with exactly one line:\n")
              .append("the kind's name, written exactly as it is in the list, if ").append(w.name()).append(" is a kind of it\n")
              .append("NEW: <kind>: <what that kind is>  if it is a kind of none of them. Name the general kind of thing ")
              .append(w.name()).append(" is, not ").append(w.name())
              .append(" itself: a kind broad enough that other things of the same sort would be kinds of it too.");
        } else {
            sb.append('\n').append(w.name()).append(" is a kind of ").append(at).append(". The narrower kinds of ").append(at)
              .append(" already known, each with what it is:\n");
            for (ConceptClasses.Kind k : classes.subclasses(at)) sb.append("- ").append(k.name()).append(": ").append(k.documentation()).append('\n');
            sb.append("\nIs ").append(w.name()).append(" a kind of one of these narrower kinds? Answer with exactly one line:\n")
              .append("the kind's name, written exactly as it is in the list, if ").append(w.name()).append(" is a kind of it\n")
              .append("HERE  if it is a kind of none of them, but still a kind of ").append(at).append('\n')
              .append("NEW: <kind>: <what that kind is>  if it belongs to a narrower kind of ").append(at)
              .append(" not in the list, broad enough that other things of the same sort would be kinds of it too");
        }
        return retry(sb);
    }

    private void answerLocate(String line) {
        if (at != null && line.equalsIgnoreCase("HERE")) {
            place(at);
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
            classes.define(name, superclass, cut(w.units().get(0).nucleus(), 160));
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
        sb.append("A document explains ").append(w.name()).append('.');
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
        sb.append("\nA paragraph of the document:\n").append(text(u)).append("\n\n");
        if (!slots.isEmpty()) sb.append("Which one of these questions does the paragraph answer? Answer with exactly one line: the name before the colon, or ");
        else sb.append("What question about ").append(w.name()).append(" does the paragraph answer? Answer with exactly one line: ");
        sb.append("NEW: <name>: <question>").append(slots.isEmpty() ? "" : " if it answers none of them")
          .append(". The name is short, and the question says \"it\" for ").append(w.name())
          .append(", so that it could be asked word for word of anything of the same kind (\"Which values does it take, and what does each do\", not \"")
          .append("What is ").append(w.name()).append("\"). Or answer ASIDE if it says nothing about ").append(w.name()).append(" itself.");
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
        sb.append("A paragraph of a document about ").append(w.name()).append(" answers a question not asked of it before:\n")
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
        classes.addSlot(to, newSlot, newQuestion);
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
        if (concept >= works.size()) {
            phase = Phase.DONE;
            return;
        }
        String found = classes.find(work().name());
        if (found != null) startSlots(found);
        else phase = Phase.LOCATE;
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
        for (String f : files) sb.append(";;; book-file: ").append(f).append('\n');
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
