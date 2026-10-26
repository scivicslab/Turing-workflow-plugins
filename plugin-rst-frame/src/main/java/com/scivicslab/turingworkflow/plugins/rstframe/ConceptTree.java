package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a concept tree for a book by interviewing a writer about its rule files, one paragraph at a time. The user
 * gets a structured hierarchy of concepts where each node contains its relation to its parent and the paragraphs that
 * explain it.
 *
 * <ul>
 * <li>{@link #nextQuestion()} Returns a question for the writer to answer, progressing through placing paragraphs,
 * merging identical concepts, establishing a root, and defining relations.</li>
 * <li>{@link #answer(String)} Processes the writer's reply to the current question, updating the tree or recording a
 * failure in the notes.</li>
 * <li>{@link #units()} Returns the list of paragraphs (units) extracted from the rule files.</li>
 * <li>{@link #concepts()} Returns the list of all concepts found in the tree.</li>
 * <li>{@link #subjectsFile(String)} Returns a string representing the tree in the {@code (subject ...)} format used by
 * {@code rst-on-lisp}.</li>
 * <li>{@link #notes()} Returns a list of messages describing answers that could not be parsed.</li>
 * </ul>
 *
 * <p>One instance is used for a single book and is not thread-safe; it maintains the state of the interview through
 * several calls to {@code nextQuestion} and {@code answer}.</p>
 *
 * <p>A workflow step might use it like this:</p>
 * <pre>{@code
 * actor: concept-tree
 * method: nextQuestion
 * ...
 * actor: concept-tree
 * method: answer
 * arguments:
 *   reply: "NEW: MyNewConcept"
 * }</pre>
 *
 * <p>If the writer's answer cannot be parsed, the class records a note and may retry the question once before moving
 * on. If {@code allowNew} was false, any name not in the existing list is treated as an invalid answer.</p>
 */
public final class ConceptTree implements Questioner {

    /**
     * Represents a single paragraph to be placed in the concept tree, containing its source location, the rule it
     * follows, its heading, and its text.
     *
     * @param file the path to the rule file
     * @param rule the name of the rule
     * @param heading the heading under which the paragraph falls
     * @param nucleus the central sentence of the paragraph
     * @param satellites the list of sentences that add information to the nucleus
     */
    public record Unit(String file, String rule, String heading, String nucleus, List<String> satellites) {
    }

    /**
     * Represents a single concept extracted from a book, including its position in the hierarchy and the text that
     * explains it.
     *
     * @param name the name of the concept
     * @param parent the name of the parent concept, or null if it is the root
     * @param relation the relationship to the parent, one of {@code kind-of}, {@code part-of}, or {@code setting-of},
     * or null
     *
     * @param paragraphs the list of {@code file / rule} strings for the paragraphs that explain this concept
     */
    public record Concept(String name, String parent, String relation, List<String> paragraphs) {
    }

    private static final Pattern NEW = Pattern.compile("(?i)^\\s*NEW\\s*:\\s*(.+?)\\s*$");
    private static final Pattern REL = Pattern.compile("(?i)^\\s*(KIND-OF|PART-OF|SETTING-OF)\\s+(.+?)\\s*$");
    private static final int MAX_SATELLITE_CHARS = 220;

    private final String book;
    private final boolean allowNew;
    private final List<Unit> units;
    private final Map<String, Concept> concepts = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    private int at = 0;
    /**
     * Represents the current stage of the concept tree construction process.
     *
     * <p>The phases are:
     * <ul><li>{@code PARAGRAPHS}: placing paragraphs into concepts.</li>
     * <li>{@code SAME}: checking if a concept is the same as another.</li>
     * <li>{@code ROOT}: designating the main concept of the document.</li>
     * <li>{@code DESCEND}: placing concepts into a hierarchy.</li>
     * <li>{@code RELATION}: recording the specific relationship to a parent.</li>
     * <li>{@code DONE}: the construction is complete.</li></ul>
     */
    private enum Phase { PARAGRAPHS, SAME, ROOT, DESCEND, RELATION, DONE }
    private Phase phase = Phase.PARAGRAPHS;
    private final List<String> pending = new ArrayList<>();
    private String relating;
    /** The concept that is currently being placed under a parent, or the root of the tree. */
    private String chosenParent;
    private String root;
    /**
     * Holds the nucleus sentences of the paragraphs associated with each concept, used when a concept is being asked
     * about.
     */
    private final Map<String, List<String>> nuclei = new LinkedHashMap<>();
    private boolean retried;
    private String lastQuestion;

    /**
     * Initializes a concept tree for a book by organizing its rule files into a hierarchy of concepts through a series
     * of questions to a writer.
     *
     * <p>The tree is built by processing the provided files to identify paragraphs, determining which concepts they
     * explain, and establishing the relationships between those concepts. If {@code allowNew} is true, the writer may
     * introduce concepts not present in the {@code given} list; otherwise, the writer must choose from the provided
     * list or the concepts discovered during the process.</p>
     *
     * @param book the name of the book, which serves as the root of the tree
     * @param files a map of rule file names to their text content, used to identify the paragraphs to be processed
     * @param given a list of initial concepts, such as a table of contents, to serve as the starting nodes of the tree
     * @param allowNew whether the writer is permitted to name concepts that are not in the initial list or previously
     * discovered
     */
    public ConceptTree(String book, Map<String, String> files, List<String> given, boolean allowNew) {
        this.book = book;
        this.allowNew = allowNew;
        for (String g : given) concepts.put(key(g), new Concept(g, null, null, new ArrayList<>()));
        this.units = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) units.addAll(units(e.getKey(), RuleFile.parse(e.getValue())));
    }

    /** Returns the paragraphs of the book in reading order, as a list of {@link Unit}s. */
    public List<Unit> units() {
        return units;
    }

    @Override
    public String nextQuestion() {
        if (phase == Phase.PARAGRAPHS && at >= units.size()) startPhase(Phase.SAME);
        while (true) {
            if (phase == Phase.SAME && relating == null) {
                if (pending.isEmpty()) { startPhase(Phase.ROOT); continue; }
                String next = pending.remove(0);
                if (concepts.containsKey(key(next)) && concepts.size() > 1) relating = next;
                continue;
            }
            if (phase == Phase.ROOT && concepts.size() < 2) {
                if (concepts.size() == 1) root = concepts.values().iterator().next().name();
                startPhase(Phase.DONE);
                continue;
            }
            if (phase == Phase.DESCEND && relating == null) {
                if (pending.isEmpty()) { startPhase(Phase.DONE); continue; }
                String next = pending.remove(0);
                if (!concepts.containsKey(key(next)) || next.equals(root)) continue;
                relating = next;
                chosenParent = root;
            }
            if (phase == Phase.DESCEND && childrenOf(chosenParent).isEmpty()) {
                // nothing narrower to choose from: the concept goes right under where it stands
                phase = Phase.RELATION;
                continue;
            }
            break;
        }
        return switch (phase) {
            case PARAGRAPHS -> lastQuestion = paragraphQuestion(units.get(at));
            case SAME -> lastQuestion = sameQuestion(relating);
            case ROOT -> lastQuestion = rootQuestion();
            case DESCEND -> lastQuestion = descendQuestion(relating, chosenParent);
            case RELATION -> lastQuestion = relationQuestion(relating, chosenParent);
            case DONE -> null;
        };
    }

    private void startPhase(Phase p) {
        phase = p;
        pending.clear();
        relating = null;
        retried = false;
        if (p == Phase.SAME || p == Phase.DESCEND) for (Concept c : concepts.values()) pending.add(c.name());
    }

    @Override
    public void answer(String reply) {
        String line = firstLine(reply);
        if (phase == Phase.SAME) {
            answerSame(line);
            return;
        }
        if (phase == Phase.ROOT) {
            answerRoot(line);
            return;
        }
        if (phase == Phase.DESCEND) {
            answerDescend(line);
            return;
        }
        if (phase == Phase.RELATION) {
            answerRelation(line);
            return;
        }
        Unit u = units.get(at);
        Concept known = concepts.get(key(line));
        if (known != null) {
            place(known, u);
            next();
            return;
        }
        Matcher m = NEW.matcher(line);
        // a name not in the list is a new concept, with or without the NEW: the question asks for
        String asNew = m.matches() ? m.group(1) : line.isBlank() || line.length() > 80 || line.contains(". ") ? null : line;
        if (asNew != null && allowNew) {
            String name = clean(asNew);
            Concept existing = concepts.get(key(name));
            if (existing != null) {
                place(existing, u);
                next();
                return;
            }
            Concept c = new Concept(name, null, null, new ArrayList<>());
            concepts.put(key(name), c);
            place(c, u);
            next();
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(u.file() + " / " + u.rule() + ": no concept could be read from \"" + line + "\"");
        next();
    }

    private static final Pattern SAME = Pattern.compile("(?i)^\\s*SAME\\s+(.+?)\\s*$");

    /**
     * Folds the concept being asked about into another concept if the reply is {@code SAME <concept>}, or leaves it as
     * a distinct concept if the reply is {@code DIFFERENT}.
     *
     * <p>A reply of {@code SAME <concept>} merges the current concept into the target concept, combining their
     * paragraphs and nucleus sentences. A reply of {@code DIFFERENT} leaves the current concept as a separate entity
     * in the tree.
     *
     * <p>If the reply does not match either format, the method retries once; if the second attempt also fails, it
     * records a note about the unreadable answer and leaves the concept as is.
     */
    private void answerSame(String line) {
        Matcher m = SAME.matcher(line);
        Concept c = concepts.get(key(relating));
        if (m.matches()) {
            Concept into = concepts.get(key(clean(m.group(1))));
            if (into != null && !into.name().equals(c.name())) {
                into.paragraphs().addAll(c.paragraphs());
                nuclei.computeIfAbsent(key(into.name()), k -> new ArrayList<>()).addAll(nuclei.getOrDefault(key(c.name()), List.of()));
                nuclei.remove(key(c.name()));
                concepts.remove(key(c.name()));
                pending.remove(into.name());
                relating = null;
                retried = false;
                return;
            }
        } else if (line.strip().equalsIgnoreCase("DIFFERENT")) {
            relating = null;
            retried = false;
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(c.name() + ": no answer to whether it is the same as another could be read from \"" + line + "\"");
        relating = null;
        retried = false;
    }

    String sameQuestion(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("The concepts found in a document:\n");
        for (Concept c : concepts.values()) if (!c.name().equals(name)) sb.append("- ").append(c.name()).append('\n');
        sb.append("\nIs \"").append(name).append("\" the same thing as one of these, under another name? Answer with exactly one line:\n")
          .append("SAME <concept>  with the name written exactly as it is in the list, if it is the same thing\n")
          .append("DIFFERENT       if it is a thing of its own (a kind or a part of another is a thing of its own)");
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    /**
     * Sets the concept identified in the reply as the root of the tree, or defaults to the first concept found if the
     * reply is unreadable.
     *
     * @param line the raw text reply from the writer
     */
    private void answerRoot(String line) {
        Concept c = concepts.get(key(clean(line)));
        if (c == null && !retried) {
            retried = true;
            return;
        }
        if (c == null) {
            c = concepts.values().iterator().next();
            notes.add("no root could be read from \"" + line + "\"; the first concept found, " + c.name() + ", is the root");
        }
        root = c.name();
        startPhase(Phase.DESCEND);
    }

    /**
     * Places a concept within the hierarchy by either nesting it under a narrower concept or keeping it directly under
     * its current parent.
     *
     * <p>If the reply is {@code HERE}, the concept is placed directly under the current parent and the next question
     * will ask for its relation. If the reply is the name of a narrower concept already in the tree, the current
     * concept's parent is updated to that concept, and the next question will ask about that new parent. If the reply
     * is not a known concept or a valid step down, the concept is placed directly under the current parent and a note
     * is recorded.</p>
     *
     * @param line the reply from the writer, which is either the name of a narrower concept or the string {@code HERE}
     */
    private void answerDescend(String line) {
        if (line.strip().equalsIgnoreCase("HERE")) {
            phase = Phase.RELATION;
            retried = false;
            return;
        }
        Concept child = concepts.get(key(clean(line)));
        if (child != null && chosenParent.equals(child.parent())) {
            chosenParent = child.name();
            retried = false;
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(relating + ": no step down from " + chosenParent + " could be read from \"" + line + "\"; placed under it");
        phase = Phase.RELATION;
        retried = false;
    }

    private List<String> childrenOf(String parent) {
        List<String> out = new ArrayList<>();
        for (Concept c : concepts.values()) if (parent.equals(c.parent())) out.add(c.name());
        return out;
    }

    /**
     * Records the relation of a concept to its chosen parent in the tree as {@code kind-of}, {@code part-of}, or
     * {@code setting-of}.
     *
     * <p>If the reply contains one of the three specified relation names, the concept is updated in the tree with that
     * relation; otherwise, if a first attempt at answering failed, the concept is recorded as a {@code part-of}
     * relation to the parent.
     *
     * @param line the text of the writer's reply, which is searched for the relation name or treated as a failed
     * attempt if no valid relation is found
     */
    private void answerRelation(String line) {
        Concept c = concepts.get(key(relating));
        String rel = line.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z-]", "");
        if (rel.equals("KIND-OF") || rel.equals("PART-OF") || rel.equals("SETTING-OF")) {
            concepts.put(key(c.name()), new Concept(c.name(), chosenParent, rel.toLowerCase(Locale.ROOT), c.paragraphs()));
        } else if (!retried) {
            retried = true;
            return;
        } else {
            notes.add(c.name() + ": no relation to " + chosenParent + " could be read from \"" + line + "\"; placed as part-of");
            concepts.put(key(c.name()), new Concept(c.name(), chosenParent, "part-of", c.paragraphs()));
        }
        relating = null;
        chosenParent = null;
        retried = false;
        phase = Phase.DESCEND;
    }

    /**
     * Returns true if {@code ancestor} is a parent, grandparent, or higher ancestor of {@code name} in the concept
     * tree.
     */
    private boolean isUnder(String name, String ancestor) {
        String p = concepts.get(key(name)).parent();
        Set<String> seen = new HashSet<>();
        while (p != null && seen.add(p)) {
            if (p.equals(ancestor)) return true;
            Concept c = concepts.get(key(p));
            p = c == null ? null : c.parent();
        }
        return false;
    }

    private void place(Concept c, Unit u) {
        c.paragraphs().add(shortFile(u.file()) + " / " + u.rule());
        nuclei.computeIfAbsent(key(c.name()), k -> new ArrayList<>()).add(u.nucleus());
    }

    private void next() {
        at++;
        retried = false;
    }

    /** Returns the concepts found in the book, in the order they were discovered. */
    public List<Concept> concepts() {
        return List.copyOf(concepts.values());
    }

    /** Returns a list of strings describing what could not be read from the writer's answers. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    String paragraphQuestion(Unit u) {
        StringBuilder sb = new StringBuilder();
        sb.append("A document is being read paragraph by paragraph to find the concepts it explains.\n\n");
        sb.append("Concepts found so far:\n");
        if (concepts.isEmpty()) sb.append("(none yet)\n");
        for (Concept c : concepts.values()) sb.append("- ").append(c.name()).append('\n');
        sb.append("\nThe paragraph is under the heading: ").append(u.heading().isBlank() ? "(none)" : u.heading()).append('\n');
        sb.append("Its central sentence (nucleus): ").append(u.nucleus()).append('\n');
        if (!u.satellites().isEmpty()) {
            sb.append("What the rest of the paragraph adds to it:\n");
            for (String s : u.satellites()) sb.append("- ").append(s).append('\n');
        }
        sb.append("\nWhich concept is this paragraph about? A concept is a thing or idea the document explains, named by a noun "
                + "or noun phrase, not a task the reader does and not a heading.\n");
        if (allowNew) {
            sb.append("Answer with exactly one line: the name of a concept from the list, written exactly as it is there, "
                    + "or NEW: followed by the name of a concept not in the list.");
        } else {
            sb.append("Answer with exactly one line: the name of a concept from the list, written exactly as it is there.");
        }
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    /**
     * Returns a string containing up to three nucleus sentences of the paragraphs belonging to the specified concept,
     * each on a new line and cut to 300 characters.
     */
    private String saying(String name) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String nu : nuclei.getOrDefault(key(name), List.of())) {
            if (n++ == 3) break;
            sb.append("- ").append(cut(nu, 300)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns a single line containing the first two nucleus sentences of the concept, each cut to 160 characters and
     * separated by a slash.
     */
    private String said(String name) {
        List<String> ns = nuclei.getOrDefault(key(name), List.of());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ns.size() && i < 2; i++) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(cut(ns.get(i), 160));
        }
        return sb.toString();
    }

    String rootQuestion() {
        StringBuilder sb = new StringBuilder();
        for (Unit u : units) {
            if (u.rule().equals("title")) {
                sb.append("A document titled: ").append(u.nucleus()).append("\n\n");
                break;
            }
        }
        sb.append("The concepts found in the document:\n");
        for (Concept c : concepts.values()) sb.append("- ").append(c.name()).append('\n');
        sb.append("\nWhich one of these is the whole document about: the concept all the others are explained as belonging to? "
                + "Answer with exactly one line: its name, written exactly as it is in the list.");
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    String descendQuestion(String name, String at) {
        StringBuilder sb = new StringBuilder();
        sb.append("In a document, ").append(name).append(" is explained as belonging to ").append(at).append(".\nWhat the document says of ")
          .append(name).append(":\n").append(saying(name));
        sb.append("\nThe narrower concepts already placed under ").append(at).append(", each with what the document says of it:\n");
        for (String c : childrenOf(at)) sb.append("- ").append(c).append(": ").append(said(c)).append('\n');
        sb.append("\nDoes ").append(name).append(" belong under one of these narrower concepts, or directly under ").append(at)
          .append("? Answer with exactly one line: the name of that narrower concept, written exactly as it is in the list, "
                + "or HERE if it belongs directly under ").append(at).append('.');
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    String relationQuestion(String name, String parent) {
        StringBuilder sb = new StringBuilder();
        sb.append("In a document, ").append(name).append(" belongs to ").append(parent).append(".\nWhat the document says of ")
          .append(name).append(":\n").append(saying(name));
        sb.append("\nHow does ").append(name).append(" belong to ").append(parent).append("? Answer with exactly one word:\n")
          .append("KIND-OF     if ").append(name).append(" is a kind of ").append(parent).append('\n')
          .append("PART-OF     if ").append(name).append(" is a part of ").append(parent).append('\n')
          .append("SETTING-OF  if ").append(name).append(" is a value that changes how ").append(parent).append(" behaves");
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one word, and nothing else.");
        return sb.toString();
    }

    /**
     * Generates a Lisp-style subjects file representing the concept tree of the book, where each concept is a numbered
     * node under its parent with its relation and paragraphs.
     *
     * @param from the name of the file to use as the starting point for the `:from` attribute in the output
     */
    public String subjectsFile(String from) {
        StringBuilder sb = new StringBuilder();
        sb.append(";;; Concept tree of ").append(book).append(", built from the body of its rule files by reading each paragraph's\n")
          .append(";;; nucleus (FrameMakingWorkflow_261022_oo01, step 2). Each node lists the paragraphs placed under it.\n");
        for (String n : notes) sb.append(";;; note: ").append(n).append('\n');
        sb.append("(in-package :rst)\n");
        sb.append("(defsubjects ").append(book).append(" :from \"").append(RuleFile.escape(from)).append("\")\n\n");
        Map<String, List<Concept>> children = new LinkedHashMap<>();
        for (Concept c : concepts.values()) children.computeIfAbsent(c.parent() == null ? "" : c.parent(), k -> new ArrayList<>()).add(c);
        write(children, "", "", sb, new HashSet<>());
        return sb.toString();
    }

    private void write(Map<String, List<Concept>> children, String parent, String prefix, StringBuilder sb, Set<String> seen) {
        int n = 0;
        for (Concept c : children.getOrDefault(parent, List.of())) {
            if (!seen.add(c.name())) continue;
            String number = prefix.isEmpty() ? String.valueOf(++n) : prefix + "." + (++n);
            sb.append("(subject \"").append(number).append("\" \"").append(RuleFile.escape(c.name())).append('"');
            if (c.relation() != null) sb.append(" :relation ").append(c.relation());
            if (!c.paragraphs().isEmpty()) {
                sb.append(" :paragraphs \"").append(RuleFile.escape(String.join(" ", paragraphRules(c)))).append('"');
            }
            sb.append(")\n");
            write(children, c.name(), number, sb, seen);
        }
    }

    /**
     * Returns a list of rule identifiers for the paragraphs belonging to a concept.
     *
     * <p>If all paragraphs for the concept come from a single file, the list contains only the rule names, omitting
     * the file path; otherwise, it contains the full {@code file / rule} strings for each paragraph.</p>
     */
    private static List<String> paragraphRules(Concept c) {
        Set<String> files = new HashSet<>();
        for (String p : c.paragraphs()) files.add(p.substring(0, p.indexOf(" / ")));
        List<String> out = new ArrayList<>();
        for (String p : c.paragraphs()) out.add(files.size() == 1 ? p.substring(p.indexOf(" / ") + 3) : p);
        return out;
    }

    // ---- reading the rule files ----

    /**
     * The paragraphs of a rule file in reading order: walking from its first rule, a rule with a {@code :heading} is a
     * section and its rules are walked; a rule with a relation keyword, a string of its own or a {@code :kind} is a
     * paragraph (or a list or table) and becomes one unit.
     */
    /**
     * Returns the text of a paragraph or list from a rule file. The result is the sequence of sentences in the
     * paragraph, or if the rule contains a {@code :file} attribute, a string in the form {@code file:path/to/file}.
     *
     * @param fileText the text of the rule file
     * @param rule the name of the rule to extract
     * @return the text of the paragraph, or null if the rule is not found
     */
    public static String paragraphText(String fileText, String rule) {
        RuleFile f = RuleFile.parse(fileText);
        RuleFile.Rule r = f.rule(rule);
        if (r == null) return null;
        if (r.attr(":file") != null) return "file:" + r.attr(":file");
        return String.join(" ", textOf(f, rule, new HashSet<>()));
    }

    /**
     * Returns a list of {@link Unit} objects representing the paragraphs found in a rule file, ordered as they appear
     * in the document.
     *
     * @param file the path to the file containing the text
     * @param text the content of the rule file to be parsed
     */
    public static List<Unit> paragraphs(String file, String text) {
        return units(file, RuleFile.parse(text));
    }

    static List<Unit> units(String file, RuleFile f) {
        List<Unit> out = new ArrayList<>();
        if (f.rules().isEmpty()) return out;
        walk(file, f, f.rules().get(0).name(), "", new HashSet<>(), out);
        return out;
    }

    private static void walk(String file, RuleFile f, String name, String heading, Set<String> seen, List<Unit> out) {
        RuleFile.Rule r = f.rule(name);
        if (r == null || !seen.add(name)) return;
        String h = r.attr(":heading");
        if (isParagraph(r)) {
            out.add(unit(file, f, r, h != null ? strip(h) : heading));
            return;
        }
        String here = h != null ? strip(h) : heading;
        for (RuleFile.Token t : r.items()) {
            if (t.kind() == RuleFile.Token.Kind.SYMBOL) walk(file, f, t.raw(), here, seen, out);
        }
    }

    /**
     * Determines if a rule represents a paragraph.
     *
     * <p>A rule is a paragraph if it contains a string that is not an attribute's value (such as a heading) or if it
     * contains the keywords {@code :nucleus} or {@code :kind}.</p>
     */
    private static boolean isParagraph(RuleFile.Rule r) {
        List<RuleFile.Token> items = r.items();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD) {
                if (t.raw().equals(":nucleus") || t.raw().equals(":kind")) return true;
                if (ATTRIBUTES.contains(t.raw())) i++;
                continue;
            }
            if (t.kind() == RuleFile.Token.Kind.STRING) return true;
        }
        return false;
    }

    private static Unit unit(String file, RuleFile f, RuleFile.Rule r, String heading) {
        String nucleus = null;
        List<String> satellites = new ArrayList<>();
        List<RuleFile.Token> items = r.items();
        for (int i = 0; i + 1 < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() != RuleFile.Token.Kind.KEYWORD || items.get(i + 1).kind() != RuleFile.Token.Kind.SYMBOL) continue;
            String rel = t.raw().substring(1);
            String text = String.join(" ", textOf(f, items.get(i + 1).raw(), new HashSet<>()));
            if (rel.equals("nucleus") && nucleus == null) nucleus = text;
            else if (!text.isBlank()) satellites.add(rel + ": " + cut(text, MAX_SATELLITE_CHARS));
        }
        if (nucleus == null) nucleus = cut(String.join(" ", textOf(f, r.name(), new HashSet<>())), 600);
        return new Unit(file, r.name(), heading, nucleus, satellites);
    }

    private static final Set<String> ATTRIBUTES = Set.of(":heading", ":kind", ":file", ":alt", ":doc", ":relation",
            ":instruction", ":sample", ":frame", ":include", ":source", ":rank");

    private static List<String> textOf(RuleFile f, String name, Set<String> seen) {
        List<String> out = new ArrayList<>();
        RuleFile.Rule r = f.rule(name);
        if (r == null || !seen.add(name)) return out;
        List<RuleFile.Token> items = r.items();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD) {
                if (ATTRIBUTES.contains(t.raw())) i++;
                continue;
            }
            if (t.kind() == RuleFile.Token.Kind.STRING) out.add(t.value());
            else out.addAll(textOf(f, t.raw(), seen));
        }
        return out;
    }

    private static String strip(String heading) {
        return heading.replaceFirst("^#+\\s*", "").strip();
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

    private static String clean(String name) {
        return name.strip().replaceAll("^[`\"']|[`\"'.]$", "").strip();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String shortFile(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }
}
