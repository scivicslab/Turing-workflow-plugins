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
 * Builds the concept tree of one book from its rule files, by asking a writer about one paragraph at a time
 * ({@code FrameMakingWorkflow_261022_oo01}, step 2).
 *
 * <p>Every judgement of meaning is the writer's; this class only orders the questions and records the answers. It
 * walks the rule files from the top and, for each paragraph, shows the nucleus sentence, the heading it is under and
 * the concepts found so far, and asks which concept the paragraph is about: one of the list, or a new one. When a new
 * concept comes, it asks once how it relates to a concept already in the list: a kind of it, a part of it, a setting
 * of it, or none. No candidate is chosen by spelling or by where it stands in the text.</p>
 *
 * <p>For a textbook, whose table of contents already is the concept tree, the concepts are given at the start and no
 * new one is accepted; the questions then only place each paragraph under a node.</p>
 *
 * <p>The tree is written in the form of {@code rst-on-lisp/subjects/}: one {@code (subject "number" "name" ...)} per
 * node, the number giving the parent, each node carrying its relation to its parent and the paragraphs placed under
 * it.</p>
 */
public final class ConceptTree implements Questioner {

    /** One paragraph to place: the rule file it is in, its rule, its heading, its nucleus and its satellites. */
    public record Unit(String file, String rule, String heading, String nucleus, List<String> satellites) {
    }

    /** One concept: its name, its parent and how it relates to the parent ({@code kind-of}, {@code part-of}, {@code setting-of}), and its paragraphs. */
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
    /** The concept a relation question is pending for, or null when the next question is about a paragraph. */
    private String relating;
    private boolean retried;
    private String lastQuestion;

    /**
     * @param book      the book's name, the root of the tree
     * @param files     the book's rule files, path to text, in reading order
     * @param given     concepts given at the start (a textbook's headings), in order; may be empty
     * @param allowNew  whether the writer may name a concept not in the list
     */
    public ConceptTree(String book, Map<String, String> files, List<String> given, boolean allowNew) {
        this.book = book;
        this.allowNew = allowNew;
        for (String g : given) concepts.put(key(g), new Concept(g, null, null, new ArrayList<>()));
        this.units = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) units.addAll(units(e.getKey(), RuleFile.parse(e.getValue())));
    }

    /** @return the paragraphs this tree places, in reading order */
    public List<Unit> units() {
        return units;
    }

    @Override
    public String nextQuestion() {
        if (relating != null) {
            lastQuestion = relationQuestion(relating);
            return lastQuestion;
        }
        if (at >= units.size()) return null;
        lastQuestion = paragraphQuestion(units.get(at));
        return lastQuestion;
    }

    @Override
    public void answer(String reply) {
        String line = firstLine(reply);
        if (relating != null) {
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
        if (m.matches() && allowNew) {
            String name = clean(m.group(1));
            Concept existing = concepts.get(key(name));
            if (existing != null) {
                place(existing, u);
                next();
                return;
            }
            boolean others = !concepts.isEmpty();
            Concept c = new Concept(name, null, null, new ArrayList<>());
            concepts.put(key(name), c);
            place(c, u);
            next();
            if (others) {
                relating = name;
                retried = false;
            }
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(u.file() + " / " + u.rule() + ": no concept could be read from \"" + line + "\"");
        next();
    }

    private void answerRelation(String line) {
        Matcher m = REL.matcher(line);
        Concept c = concepts.get(key(relating));
        if (m.matches()) {
            Concept parent = concepts.get(key(clean(m.group(2))));
            if (parent != null && !parent.name().equals(c.name()) && !isUnder(parent.name(), c.name())) {
                concepts.put(key(c.name()), new Concept(c.name(), parent.name(), m.group(1).toLowerCase(Locale.ROOT), c.paragraphs()));
                relating = null;
                retried = false;
                return;
            }
        } else if (line.strip().equalsIgnoreCase("NONE")) {
            relating = null;
            retried = false;
            return;
        }
        if (!retried) {
            retried = true;
            return;
        }
        notes.add(c.name() + ": no relation could be read from \"" + line + "\"");
        relating = null;
        retried = false;
    }

    /** @return whether {@code name} has {@code ancestor} above it (so making {@code ancestor} its child would loop) */
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
    }

    private void next() {
        at++;
        retried = false;
    }

    /** @return the concepts, in the order they were found */
    public List<Concept> concepts() {
        return List.copyOf(concepts.values());
    }

    /** @return what could not be read from the writer's answers */
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

    String relationQuestion(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("A new concept was found in a document: ").append(name).append("\n\nOther concepts of the same document:\n");
        for (Concept c : concepts.values()) {
            if (!c.name().equals(name)) sb.append("- ").append(c.name()).append('\n');
        }
        sb.append("\nHow is ").append(name).append(" related to one of these? Answer with exactly one line:\n")
          .append("KIND-OF <concept>    if ").append(name).append(" is a kind of that concept\n")
          .append("PART-OF <concept>    if ").append(name).append(" is a part of that concept\n")
          .append("SETTING-OF <concept> if ").append(name).append(" is a value that changes how that concept behaves\n")
          .append("NONE                 if none of these holds for any of them\n")
          .append("Write the concept's name exactly as it is in the list.");
        if (retried) sb.append("\nYour previous answer could not be read. Answer with one line in the form asked, and nothing else.");
        return sb.toString();
    }

    /**
     * The tree as a subjects file: the root is the book; each concept is a numbered node under its parent (or the root),
     * carrying {@code :relation} and {@code :paragraphs}.
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

    /** {@code file / rule} entries grouped as {@code rule rule ...} when there is one file, else kept whole. */
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

    /** A string of its own (not an attribute's value such as a heading), a {@code :nucleus} or a {@code :kind} makes a paragraph. */
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
