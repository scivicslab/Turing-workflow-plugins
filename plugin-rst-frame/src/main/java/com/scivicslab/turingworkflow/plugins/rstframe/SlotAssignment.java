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
 * Assigns paragraphs of a concept or blanks of an explanation frame to the slots of concept classes by asking a writer
 * a sequence of questions.
 *
 * <p>The workflow uses this to build a hierarchy of concept classes and their properties. For each concept, the writer
 * is asked to:
 * <ul>
 * <li>Locate the concept within the existing class hierarchy or define a new general kind.</li>
 * <li>Assign each paragraph or blank to a specific slot (a question that can be asked of any instance of that class)
 * or mark it as an aside.</li>
 * <li>If a paragraph answers a question not yet asked of the class, the writer determines the broadest class to which
 * that question applies.</li>
 * </ul>
 *
 * <p>The class keeps track of the concepts processed, the classes added or modified, and any notes regarding answers
 * that could not be parsed. One instance serves one workflow.
 *
 * <p>An action result is a success if the writer provides an answer in the expected format; if the answer is
 * unreadable, the next call to {@code nextQuestion} returns a message asking for a correction.
 *
 * <p>The result of the assignment is available through:
 * <ul>
 * <li>{@code placed()}: a list of each concept's assigned class and the references to its paragraphs.</li>
 * <li>{@code added()}: a list of the names of the new classes defined during the process.</li>
 * <li>{@code grown()}: a list of the names of existing classes that had new slots added.</li>
 * <li>{@code notes()}: a list of messages describing what could not be read from the writer's answers.</li>
 * <li>{@code resultFile(String from)}: a string of Lisp code representing the proposed class and slot assignments,
 * including a header with the source and any notes.</li>
 * </ul>
 */
public final class SlotAssignment implements Questioner {

    /**
     * Holds the data for a single concept being processed, including its name, the references to its paragraphs or
     * blanks, the units of text, any associated sub-frames, and the source file.
     *
     * @param name the name of the concept
     * @param refs the list of references (e.g., rule names) for each paragraph or blank
     * @param units the list of {@link ConceptTree.Unit} objects representing the text segments
     * @param frames a mapping from rule names to the names of sub-frames they represent
     * @param source the name of the file from which the concept or its units were taken
     */
    record Work(String name, List<String> refs, List<ConceptTree.Unit> units, Map<String, String> frames, String source) {
    }

    /**
     * Distinguishes whether the input consists of a book's paragraphs or an explanation frame's blanks to determine
     * how questions are worded.
     */
    private enum Material { BOOK, FRAME }

    /**
     * Records the assignment of a concept's paragraphs to a specific class and its slots.
     *
     * <p>The record contains:
     * <ul>
     * <li>{@code concept}: the name of the concept being assigned</li>
     * <li>{@code cls}: the name of the class it was assigned to</li>
     * <li>{@code slots}: a mapping from each slot name (or {@code :aside}) to the list of paragraph references that
     * answer it</li>
     * </ul>
     *
     * @param concept the name of the concept
     * @param cls the name of the class
     * @param slots the mapping of slot names to paragraph references
     */
    record Placed(String concept, String cls, Map<String, List<String>> slots) {
    }

    private static final Pattern SUBJECT = Pattern.compile("^\\(subject \"[^\"]*\" \"((?:[^\"\\\\]++|\\\\.)*+)\"(.*)\\)\\s*$");
    private static final Pattern PARAGRAPHS = Pattern.compile(":paragraphs \"((?:[^\"\\\\]++|\\\\.)*+)\"");
    private static final Pattern REF = Pattern.compile("\\S+ / \\S+|\\S+");
    /**
     * Matches a line that specifies a name and a description, optionally prefixed with {@code NEW:}, in the form
     * {@code name: text} or {@code NEW: name: text}.
     */
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
    /** Maps each frame file to the class it became, used to link a slot's type to the sub-frame it names. */
    private final Map<String, String> frameClasses = new LinkedHashMap<>();

    private int concept = -1;
    private Phase phase = Phase.LOCATE;
    /**
     * The class the concept is being taken down from during the locating phase; {@code null} if the concept is being
     * compared against the root classes.
     */
    private String at;
    private String cls;
    private int paragraph;
    private String newSlot;
    private String newQuestion;
    private boolean retried;
    /** Indicates whether the last answer provided by the writer named the concept itself within a new question. */
    private boolean namedItself;
    /**
     * Holds the one-line English sentence that describes what a concept is, used when the writer is asked to define a
     * concept that is not already known.
     */
    private String definition;
    /** The class the writer said the concept is the same thing as, until that is confirmed. */
    private String sameAs;
    /**
     * Holds the names of classes that the writer has explicitly rejected as being the same thing as the current
     * concept.
     */
    private final Set<String> notSame = new LinkedHashSet<>();

    /**
     * Sets up a state for assigning each paragraph of a concept to a specific class by asking a writer a sequence of
     * questions.
     *
     * @param book the book's name
     * @param files a map of the book's rule files, where the key is the file path and the value is the file content
     * @param subjects a string containing the concept tree from step 2, with lines in the format {@code (subject "n"
     * "name" ... :paragraphs "...")}
     *
     * @param classes the concept classes to be populated or extended by this work
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
     * Creates a {@code SlotAssignment} from an integrated explanation frame.
     *
     * <p>The resulting object uses the blanks in the frame as instructions for a writer to determine the classes and
     * slots for the concepts described in the frame. Each sub-frame file is treated as a single concept; in the top
     * frame, the blanks under a {@code *-part} rule form one concept, and the blanks under no part form the concept
     * the frame itself explains. A blank that names a sub-frame becomes a slot typed with that sub-frame's class.</p>
     *
     * @param top the name of the top frame, such as {@code explain-agent-harness}
     * @param frameFiles a map of frame file names to their text content, including the top frame and all sub-frames
     * @param classes the concept classes to be populated by the assignment process
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

    /**
     * Defines the concept as a class under the specified {@code superclass} and moves to processing its paragraphs. If
     * {@code superclass} is {@code null}, the concept is defined as a new root class.
     */
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

    /**
     * Returns a list of assignments, where each entry contains a concept's name, the class it was assigned to, and a
     * mapping from each slot name to the list of paragraphs that answer it.
     */
    public List<Placed> placed() {
        return List.copyOf(placed);
    }

    /** Returns the names of the new concept classes defined during the assignment process. */
    public List<String> added() {
        return List.copyOf(added);
    }

    /**
     * Returns a list of the names of classes that existed before this work began but had new slots added to them.
     */
    public List<String> grown() {
        return List.copyOf(grown);
    }

    /**
     * Returns a list of strings that record what could not be read from the writer's answers.
     *
     * <p>Each string contains the name of the concept and the specific text that failed to match the expected format
     * or the reason a class could not be found. These notes are included in the output of {@link #resultFile(String)}
     * to help a person review the assignments.</p>
     */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * Generates a Lisp-style text file containing the proposed concept classes and the assignment of paragraphs to
     * their respective slots.
     *
     * @param from the name of the source or process that produced the assignments, used in the file header.
     * @return a string containing the full content of the definition file, including headers, class definitions, and
     * assignment blocks.
     */
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

    /** Returns true if the provided slot or question names the current concept. */
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
