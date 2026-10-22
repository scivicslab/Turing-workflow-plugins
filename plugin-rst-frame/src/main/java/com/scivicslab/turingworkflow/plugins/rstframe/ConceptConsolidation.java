package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a tree of concept classes smaller ({@code ConceptClasses_261022_oo01}): a subclass stays a class only while it
 * says something its superclass does not; one that says nothing more becomes a value (an instance) of its superclass.
 * Run between runs of step 3, which grow the tree; the two alternate.
 *
 * <p>The candidates are found by the program; the writer is asked only whether a difference is worth keeping. In order:</p>
 * <ol>
 *   <li>A slot two or more subclasses of one class each define under the same name goes up to that class (no question).</li>
 *   <li>For each slot a class with no subclasses defines itself, the writer is shown its superclass and the other
 *       kinds of it, and asked whether the question can be asked of every kind of the superclass. If so, it goes up.</li>
 *   <li>A class with no subclasses and no slot of its own becomes a value of its superclass, repeatedly, so that a
 *       class whose kinds all became values can become one in turn (no question). The paragraphs assigned to it are
 *       assigned to the superclass, and the concept's name is kept as the value's name.</li>
 * </ol>
 * <p>The result is one file that stands alone: every class, the values, and the assignments, with what was done.</p>
 */
public final class ConceptConsolidation implements Questioner {

    /** One {@code (assign ...)} form: a concept, its class, and for each slot ({@code :aside} too) the paragraphs. */
    public record Assignment(String concept, String cls, Map<String, List<String>> slots) {
    }

    /** One question to ask: whether a slot of a class can go up to the class's superclass. */
    private record Candidate(String cls, String superclass, ConceptClasses.OwnSlot slot) {
    }

    private static final Pattern ASSIGN = Pattern.compile("^\\(assign \"((?:[^\"\\\\]++|\\\\.)*+)\" (\\S+?)\\)?$");
    private static final Pattern SLOT = Pattern.compile("^\\s+\\((\\S+)((?: \"(?:[^\"\\\\]++|\\\\.)*+\")*)\\)\\)?$");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]++|\\\\.)*+)\"");
    private static final Pattern KEPT_LINE = Pattern.compile("(?m)^;;; (book-file|frame-file): .*$");

    private final ConceptClasses classes;
    private final List<Assignment> assignments;
    private final List<String> keptLines = new ArrayList<>();
    private final List<String> done = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private int at;
    private boolean retried;
    private boolean finished;

    /**
     * @param classes the classes to make smaller: the class files, with the result of step 3 read on top of them
     * @param result  that result's text, for its assignments ({@code ""} when there is none)
     */
    public ConceptConsolidation(ConceptClasses classes, String result) {
        this.classes = classes;
        this.assignments = assignments(result);
        Matcher m = KEPT_LINE.matcher(result);
        while (m.find()) keptLines.add(m.group());
        pullUpSharedSlots();
        for (String c : classes.names()) {
            List<String> supers = classes.superclasses(c);
            if (supers.size() != 1 || !classes.subclasses(c).isEmpty()) continue;
            for (ConceptClasses.OwnSlot s : classes.ownSlots(c)) candidates.add(new Candidate(c, supers.get(0), s));
        }
    }

    @Override
    public String nextQuestion() {
        if (at >= candidates.size()) {
            if (!finished) {
                finished = true;
                toValues();
            }
            return null;
        }
        Candidate k = candidates.get(at);
        StringBuilder sb = new StringBuilder();
        sb.append(k.cls()).append(" is a kind of ").append(k.superclass());
        String doc = classes.chain(k.superclass()).get(0).documentation();
        if (!doc.isBlank()) sb.append(": ").append(doc);
        sb.append(".\n");
        List<String> others = classes.subclasses(k.superclass()).stream().map(ConceptClasses.Kind::name).filter(n -> !n.equals(k.cls())).toList();
        sb.append("The other kinds of ").append(k.superclass()).append(": ").append(others.isEmpty() ? "(none yet)" : String.join(", ", others)).append(".\n");
        List<ConceptClasses.Slot> inherited = classes.whatCanBeSaid(k.superclass());
        if (!inherited.isEmpty()) {
            sb.append("What can already be said of every ").append(k.superclass()).append(":\n");
            for (ConceptClasses.Slot s : inherited) sb.append("- ").append(s.name()).append(": ").append(s.question()).append('\n');
        }
        sb.append("\n").append(k.cls()).append(" has a question of its own:\n").append(k.slot().name()).append(": ").append(k.slot().question())
          .append("\n\nCould this question be asked, as it is written, of every kind of ").append(k.superclass()).append(", not only of ")
          .append(k.cls()).append("? Answer with exactly one word: YES or NO.");
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one word, and nothing else.");
        return sb.toString();
    }

    @Override
    public void answer(String reply) {
        Candidate k = candidates.get(at);
        String a = reply == null ? "" : reply.strip().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", " ").strip();
        String first = a.isEmpty() ? "" : a.split("\\s+")[0];
        if (first.equals("YES")) {
            moveUp(k.cls(), k.superclass(), k.slot(), "it can be asked of every kind of " + k.superclass());
        } else if (!first.equals("NO")) {
            if (!retried) {
                retried = true;
                return;
            }
            notes.add(k.cls() + " / " + k.slot().name() + ": no answer could be read from \"" + reply + "\"; kept where it is");
        }
        retried = false;
        at++;
    }

    // ---- what the program does without asking ----

    /** A slot name two or more subclasses of one class define goes up to that class. */
    private void pullUpSharedSlots() {
        for (String parent : classes.names()) {
            Map<String, List<String>> bySlot = new LinkedHashMap<>();
            for (ConceptClasses.Kind k : classes.subclasses(parent)) {
                for (ConceptClasses.OwnSlot s : classes.ownSlots(k.name())) bySlot.computeIfAbsent(s.name(), x -> new ArrayList<>()).add(k.name());
            }
            for (Map.Entry<String, List<String>> e : bySlot.entrySet()) {
                if (e.getValue().size() < 2) continue;
                ConceptClasses.OwnSlot first = null;
                for (String c : e.getValue()) {
                    for (ConceptClasses.OwnSlot s : classes.ownSlots(c)) if (s.name().equals(e.getKey()) && first == null) first = s;
                }
                for (String c : e.getValue()) classes.removeSlot(c, e.getKey());
                if (classes.ownSlots(parent).stream().noneMatch(s -> s.name().equals(e.getKey()))) {
                    classes.addSlot(parent, first.name(), first.question(), first.type().isEmpty() ? null : first.type());
                }
                done.add("moved " + e.getKey() + " up from " + String.join(", ", e.getValue()) + " to " + parent + ": they all had it");
            }
        }
    }

    private void moveUp(String cls, String superclass, ConceptClasses.OwnSlot slot, String why) {
        classes.removeSlot(cls, slot.name());
        if (classes.whatCanBeSaid(superclass).stream().noneMatch(s -> s.name().equals(slot.name()))) {
            classes.addSlot(superclass, slot.name(), slot.question(), slot.type().isEmpty() ? null : slot.type());
        }
        done.add("moved " + slot.name() + " up from " + cls + " to " + superclass + ": " + why);
    }

    /** Every class with no subclasses, one superclass and no slot of its own becomes a value, until none is left. */
    private void toValues() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String c : classes.names()) {
                List<String> supers = classes.superclasses(c);
                if (supers.size() != 1 || !classes.subclasses(c).isEmpty() || !classes.ownSlots(c).isEmpty()) continue;
                String superclass = supers.get(0);
                String name = c.replace('-', ' ');
                // the concept this class was made for, not one moved onto it from a class that became a value before
                for (Assignment a : assignments) if (ConceptClasses.lispName(a.concept()).equals(c)) name = a.concept();
                classes.toInstance(c, name);
                for (int i = 0; i < assignments.size(); i++) {
                    Assignment a = assignments.get(i);
                    if (a.cls().equals(c)) assignments.set(i, new Assignment(a.concept(), superclass, a.slots()));
                }
                done.add(c + " became a value of " + superclass + ": it said nothing " + superclass + " does not");
                changed = true;
                break;
            }
        }
    }

    // ---- the result ----

    /** @return what was done, one line each */
    public List<String> done() {
        return List.copyOf(done);
    }

    /** @return what could not be read from the writer's answers */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** @return the assignments, each pointing at the class it is in now */
    public List<Assignment> placed() {
        return List.copyOf(assignments);
    }

    /** The tree made smaller, as one file that stands alone: every class, superclasses first, the values, and the assignments. */
    public String resultFile(String from) {
        StringBuilder sb = new StringBuilder();
        sb.append(";;; The concept classes of ").append(from).append(", made smaller (ConceptClasses_261022_oo01): a subclass that\n")
          .append(";;; said nothing its superclass does not became a value of it. This file stands alone; it is a proposal.\n")
          .append(";;; standalone\n");
        for (String l : keptLines) sb.append(l).append('\n');
        for (String d : done) sb.append(";;; done: ").append(d).append('\n');
        for (String n : notes) sb.append(";;; note: ").append(n).append('\n');
        sb.append("(in-package :concepts)\n\n");
        List<String> order = new ArrayList<>(classes.names());
        order.sort(java.util.Comparator.comparingInt((String c) -> classes.chain(c).size()).thenComparing(c -> c));
        for (String c : order) sb.append(classes.source(c)).append("\n\n");
        for (Map.Entry<String, String> e : classes.instances().entrySet()) {
            sb.append("(instance \"").append(RuleFile.escape(e.getKey())).append("\" ").append(e.getValue()).append(")\n");
        }
        if (!classes.instances().isEmpty()) sb.append('\n');
        for (Assignment a : assignments) {
            sb.append("(assign \"").append(RuleFile.escape(a.concept())).append("\" ").append(a.cls());
            for (Map.Entry<String, List<String>> e : a.slots().entrySet()) {
                sb.append("\n  (").append(e.getKey());
                for (String r : e.getValue()) sb.append(" \"").append(RuleFile.escape(r)).append('"');
                sb.append(')');
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    /** @return the {@code (assign ...)} forms of a result of step 3, one concept each */
    public static List<Assignment> assignments(String text) {
        List<Assignment> out = new ArrayList<>();
        Assignment cur = null;
        for (String line : text.split("\\R")) {
            Matcher a = ASSIGN.matcher(line);
            if (a.matches()) {
                cur = new Assignment(a.group(1).replace("\\\"", "\""), a.group(2), new LinkedHashMap<>());
                out.add(cur);
                continue;
            }
            Matcher s = SLOT.matcher(line);
            if (cur != null && s.matches()) {
                List<String> refs = cur.slots().computeIfAbsent(s.group(1), k -> new ArrayList<>());
                Matcher str = STRING.matcher(s.group(2));
                while (str.find()) refs.add(str.group(1).replace("\\\"", "\""));
                continue;
            }
            cur = null;
        }
        return out;
    }
}
