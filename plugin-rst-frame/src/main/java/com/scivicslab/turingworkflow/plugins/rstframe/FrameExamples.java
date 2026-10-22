package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The paragraphs of the source books a blank can be shown as examples, besides its one {@code :sample}
 * ({@code FramesAndSamplesFromBooks_261021_oo01}, "関係で指したお手本を引く").
 *
 * <p>An integrated frame says, in its {@code ;;; roles:} table, which paragraph of each frame it integrates plays each of
 * its parts: the row {@code p-what: the-system p-system-what / - / p-what p-functions} names, for the part
 * {@code p-what}, rules of the books those frames were made from. A concrete frame names its books in
 * {@code ;;; made-from:} (a sub-frame adds the lines of its top frame). This class reads both and, for a blank, finds
 * the row whose part the blank belongs to and returns the text of every rule the row names that is a paragraph of one
 * of those books. A row may instead name a rule of an integrated concrete frame (the table of {@code explain-data-type}
 * does); such a rule carries the book's own sentences as {@code :sample}, and those are returned.</p>
 *
 * <ul>
 *   <li>A blank of an integrated frame (or of one of its sub-frames) belongs to the part whose name is one of the
 *       segments of the blank's name: {@code p-what/n} to {@code p-what}, {@code flows/2/flow-what/n} to
 *       {@code flow-what}. The nearest segment that is a part wins. A segment that is a part's name followed by
 *       {@code -} and more ({@code what-definition}) belongs to that part ({@code what}), the longest such name
 *       winning.</li>
 *   <li>A blank of a concrete frame belongs to every row, in the integrated frames that take that concrete frame in,
 *       that names one of the blank's segments; the examples are the row's other paragraphs, from the other frames'
 *       books.</li>
 * </ul>
 *
 * <p>Where the library has an edge list ({@code rst-on-lisp/links/*.tsv}, {@code FrameLinks_261022_oo01}) for a frame,
 * the edges are used instead of the roles table: {@code integrates} edges join a rule of an integrated frame and a rule
 * of a concrete frame (an integrated frame is one under {@code explanation/}), {@code made-from} edges join a rule of a concrete frame and a paragraph of a book, and both are
 * followed either way. A rule with no {@code made-from} edge gives its {@code :sample} sentences.</p>
 *
 * <p>Pure: frames are handed in as texts by their path under {@code frames/}, the edge lists as texts, and the books
 * are read through {@code readWorks}, which takes a path under the works directory and returns the text or null.</p>
 */
public final class FrameExamples {

    /** One example: where it is from, and its text. */
    public record Example(String source, String text) {
    }

    private static final Pattern KEYED = Pattern.compile("([A-Za-z][A-Za-z0-9-]*)( \\([^)]*\\))?:(.*)");
    /** The head line of a roles table; its colon may be left out after the parenthesis ({@code ;;; roles (role: a / b)}). */
    private static final Pattern ROLES_HEAD = Pattern.compile("roles( \\(.*\\))?:?\\s*");
    private static final Pattern ROW = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_/-]*):\\s+(.*)$");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_/.-]*");
    private static final int MAX_CHARS = 700;

    /** An integrated frame with its sub-frames: the rows of their roles tables, and the books of what it integrates. */
    private record Group(Set<String> frames, Map<String, List<String>> rows, Set<String> concrete, List<String> books) {
    }

    private final Map<String, String> frames;
    private final Function<String, String> readWorks;
    private final Map<String, RuleFile> bookCache = new LinkedHashMap<>();
    private final List<Group> groups = new ArrayList<>();

    /**
     * @param frames    every frame file, by its path under {@code frames/} ({@code explanation/explain-program.lisp})
     * @param readWorks reads a path under the works directory; null when there is no such file
     */
    public FrameExamples(Map<String, String> frames, Function<String, String> readWorks) {
        this(frames, List.of(), readWorks);
    }

    /** One end of an edge: a rule of a file, the file by its path under {@code frames/} or under the works directory. */
    private record End(String file, String rule) {
    }

    /** Every edge, from both of its ends: an end, to the ends joined to it, by kind. */
    private final Map<End, Map<String, List<End>>> edges = new LinkedHashMap<>();

    /**
     * @param links the edge lists ({@code links/**}{@code /*.tsv}), as texts: five tab-separated columns
     *              {@code a_file a_rule b_file b_rule kind}, lines starting with {@code #} left out
     */
    public FrameExamples(Map<String, String> frames, List<String> links, Function<String, String> readWorks) {
        this.frames = frames;
        this.readWorks = readWorks;
        for (String text : links) {
            for (String line : text.split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t");
                if (c.length < 5) continue;
                End a = new End(c[0].strip(), c[1].strip());
                End b = new End(c[2].strip(), c[3].strip());
                String kind = c[4].strip();
                edges.computeIfAbsent(a, k -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>()).add(b);
                edges.computeIfAbsent(b, k -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>()).add(a);
            }
        }
        for (String path : frames.keySet()) {
            if (!isTop(path)) continue;
            List<String> integrates = integrates(frames.get(path));
            if (integrates.isEmpty()) continue;
            Set<String> members = new LinkedHashSet<>();
            Map<String, List<String>> rows = new LinkedHashMap<>();
            for (String p : frames.keySet()) {
                if (!p.equals(path) && !topOf(p).equals(path)) continue;
                members.add(p);
                rows(frames.get(p)).forEach((k, v) -> rows.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
            }
            Set<String> concrete = new LinkedHashSet<>(integrates);
            List<String> books = new ArrayList<>();
            for (String c : integrates) for (String b : booksOf(c)) if (!books.contains(b)) books.add(b);
            groups.add(new Group(members, rows, concrete, books));
        }
    }

    /**
     * @param frame the frame being filled, by its path under {@code frames/}
     * @param blank the blank's name as it stands in the rule file being filled
     * @param max   how many examples at most
     * @return the examples, nearest part first; empty when none is known
     */
    public List<Example> forBlank(String frame, String blank, int max) {
        List<String> segments = segments(blank);
        List<Example> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (hasEdges(frame)) {
            // edges name rules, and a rule's name may hold "/" (flow/what): the blank's own prefixes first, longest first
            List<String> candidates = new ArrayList<>();
            String[] parts = blank.split("/");
            for (int i = parts.length; i >= 1; i--) candidates.add(String.join("/", java.util.Arrays.copyOf(parts, i)));
            for (String seg : segments) if (!candidates.contains(seg)) candidates.add(seg);
            for (String seg : candidates) {
                End at = new End(frame, seg);
                if (!edges.containsKey(at)) continue;
                List<End> concrete = new ArrayList<>();
                if (frame.startsWith("explanation/")) {
                    // a rule of an integrated frame: the concrete rules it was made from
                    concrete.addAll(joined(at, "integrates"));
                } else {
                    // a rule of a concrete frame: the other concrete rules of the integrated rules it went into
                    for (End integrated : joined(at, "integrates")) {
                        for (End other : joined(integrated, "integrates")) {
                            if (!other.file().equals(frame) && !concrete.contains(other)) concrete.add(other);
                        }
                    }
                }
                for (End c : concrete) {
                    if (out.size() >= max) break;
                    Example e = paragraphsOf(c, seen);
                    if (e != null) out.add(e);
                }
                if (!out.isEmpty()) return out;
            }
            return out;
        }
        for (Group g : groups) {
            if (!g.frames().contains(frame)) continue;
            for (String seg : segments) {
                List<String> row = rowOf(g.rows(), seg);
                if (row == null) continue;
                collect(row, g, Set.of(), seen, out, max);
                return out;
            }
        }
        if (!frames.containsKey(frame)) return out;
        List<String> own = booksOf(frame);
        for (Group g : groups) {
            if (!g.concrete().contains(frame) && !g.concrete().contains(topOf(frame))) continue;
            for (String seg : segments) {
                for (List<String> row : g.rows().values()) {
                    if (!row.contains(seg)) continue;
                    collect(row, g, new HashSet<>(own), seen, out, max);
                }
                if (!out.isEmpty()) return out;
            }
        }
        return out;
    }

    /**
     * The examples a row names: for each name, the paragraph of that name in the first book that has it; failing that,
     * the {@code :sample} sentences of the rule of that name in the first integrated concrete frame that has it.
     */
    private boolean hasEdges(String frame) {
        for (End e : edges.keySet()) if (e.file().equals(frame)) return true;
        return false;
    }

    private List<End> joined(End at, String kind) {
        return edges.getOrDefault(at, Map.of()).getOrDefault(kind, List.of());
    }

    /** The book paragraphs a concrete rule was made from, joined; failing those, the rule's {@code :sample} sentences. */
    private Example paragraphsOf(End concrete, Set<String> seen) {
        if (!seen.add(concrete.file() + "#" + concrete.rule())) return null;
        List<String> texts = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (End book : joined(concrete, "made-from")) {
            RuleFile f = book(book.file());
            if (f == null || f.rule(book.rule()) == null) continue;
            String text = String.join(" ", textOf(f, book.rule(), new HashSet<>()));
            if (text.isBlank()) continue;
            texts.add(text);
            sources.add(book.file().substring(book.file().lastIndexOf('/') + 1) + " / " + book.rule());
        }
        if (!texts.isEmpty()) return new Example(String.join(", ", sources), cut(String.join(" ", texts)));
        Example e = fromFrames(concrete.rule(), Set.of(concrete.file()), Set.of(), new HashSet<>());
        return e;
    }

    /** The row of a part named {@code seg}, or of the longest part {@code seg} starts with followed by {@code -}. */
    private static List<String> rowOf(Map<String, List<String>> rows, String seg) {
        if (rows.containsKey(seg)) return rows.get(seg);
        String best = null;
        for (String key : rows.keySet()) {
            if (seg.startsWith(key + "-") && (best == null || key.length() > best.length())) best = key;
        }
        return best == null ? null : rows.get(best);
    }

    private void collect(List<String> names, Group g, Set<String> skip, Set<String> seen, List<Example> out, int max) {
        for (String name : names) {
            if (out.size() >= max) return;
            // a name that is a paragraph of the frame's own books is that frame's own part of the row, not an example
            if (skip.stream().map(this::book).anyMatch(f -> f != null && f.rule(name) != null)) continue;
            Example e = fromBooks(name, g.books(), skip, seen);
            if (e == null) e = fromFrames(name, g.concrete(), skip, seen);
            if (e != null) out.add(e);
        }
    }

    private Example fromBooks(String name, List<String> books, Set<String> skip, Set<String> seen) {
        for (String book : books) {
            if (skip.contains(book)) continue;
            RuleFile f = book(book);
            if (f == null || f.rule(name) == null || !seen.add(book + "#" + name)) continue;
            String text = String.join(" ", textOf(f, name, new HashSet<>()));
            if (text.isBlank()) continue;
            return new Example(book.substring(book.lastIndexOf('/') + 1) + " / " + name, cut(text));
        }
        return null;
    }

    /** The {@code :sample} sentences of a concrete frame's rule and of the rules under it ({@code p-what/n}), in file order. */
    private Example fromFrames(String name, Set<String> concrete, Set<String> skipBooks, Set<String> seen) {
        for (String frame : concrete) {
            String text = frames.get(frame);
            if (text == null || !java.util.Collections.disjoint(booksOf(frame), skipBooks)) continue;
            List<String> samples = new ArrayList<>();
            boolean found = false;
            for (RuleFile.Rule r : RuleFile.parse(text).rules()) {
                if (!r.name().equals(name) && !r.name().startsWith(name + "/")) continue;
                found = true;
                String sample = r.attr(":sample");
                if (sample != null && !sample.isBlank()) samples.add(sample);
            }
            if (!found || samples.isEmpty() || !seen.add(frame + "#" + name)) continue;
            return new Example(frame + " / " + name, cut(String.join(" ", samples)));
        }
        return null;
    }

    private static String cut(String text) {
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + " ..." : text;
    }

    private RuleFile book(String path) {
        return bookCache.computeIfAbsent(path, p -> {
            String text = readWorks.apply(p);
            return text == null ? null : RuleFile.parse(text);
        });
    }

    /** The books a concrete frame was made from: its own {@code made-from} lines, then its top frame's. */
    private List<String> booksOf(String frame) {
        List<String> lines = new ArrayList<>();
        if (frames.containsKey(frame)) lines.addAll(header(frames.get(frame), "made-from"));
        String top = topOf(frame);
        if (!top.equals(frame) && frames.containsKey(top)) lines.addAll(header(frames.get(top), "made-from"));
        List<String> out = new ArrayList<>();
        for (String v : lines) for (String f : sourceFiles(v)) if (!out.contains(f)) out.add(f);
        return out;
    }

    /** Every string a rule holds and the strings of the rules it names, attributes such as {@code :heading} left out. */
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

    private static final Set<String> ATTRIBUTES = Set.of(":heading", ":kind", ":file", ":alt", ":doc", ":relation",
            ":instruction", ":sample", ":frame", ":include", ":source", ":rank");

    /** {@code p-what/n} → [p-what]; {@code flows/2/flow-what/n} → [flow-what, flows]: the parts, nearest first. */
    static List<String> segments(String blank) {
        String[] parts = blank.split("/");
        List<String> out = new ArrayList<>();
        for (int i = parts.length - 1; i >= 0; i--) {
            String s = parts[i];
            if (s.isEmpty() || s.matches("\\d+") || s.equals("n")) continue;
            out.add(s);
        }
        return out;
    }

    /** The rows of a {@code ;;; roles} table: each part, with every word its cells name. */
    static Map<String, List<String>> rows(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        boolean in = false;
        for (String line : text.split("\n")) {
            if (!line.startsWith(";;;")) {
                in = false;
                continue;
            }
            String body = line.substring(3);
            if (!body.startsWith("   ")) {
                in = ROLES_HEAD.matcher(body.strip()).matches();
                continue;
            }
            if (!in) continue;
            Matcher r = ROW.matcher(body);
            if (!r.matches()) continue;
            List<String> words = new ArrayList<>();
            Matcher w = WORD.matcher(r.group(2));
            while (w.find()) words.add(w.group());
            out.put(r.group(1), words);
        }
        return out;
    }

    /** Every value of a header key, a value continued on the next {@code ;;;} lines joined with spaces. */
    static List<String> header(String text, String key) {
        List<String> out = new ArrayList<>();
        String current = null;
        for (String line : text.split("\n")) {
            if (!line.startsWith(";;;")) {
                current = null;
                continue;
            }
            String body = line.substring(3);
            Matcher k = KEYED.matcher(body.strip());
            if (!body.startsWith("   ") && k.matches()) {
                current = k.group(1);
                if (current.equals(key)) out.add(k.group(3).strip());
            } else if (key.equals(current) && !body.isBlank()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + body.strip());
            }
        }
        return out;
    }

    /** The frame paths of {@code ;;; integrates:}, under {@code frames/}. */
    static List<String> integrates(String text) {
        List<String> out = new ArrayList<>();
        for (String v : header(text, "integrates")) {
            String first = v.split("\\s+")[0];
            if (first.endsWith(".lisp")) out.add(first);
        }
        return out;
    }

    /**
     * The book files a {@code made-from} value names: the part before {@code " / "}, entries starting with
     * {@code doc_}; an entry ending in {@code .lisp} is the file, any other is a directory holding a file of its own name.
     */
    static List<String> sourceFiles(String madeFrom) {
        int slash = madeFrom.indexOf(" / ");
        String left = slash < 0 ? madeFrom : madeFrom.substring(0, slash);
        List<String> out = new ArrayList<>();
        for (String part : left.split(" (?=doc_)")) {
            String p = part.strip();
            if (p.endsWith("...")) p = p.substring(0, p.length() - 3).strip();
            if (!p.startsWith("doc_")) continue;
            out.add(p.endsWith(".lisp") ? p : p + "/" + p.substring(p.lastIndexOf('/') + 1) + ".lisp");
        }
        return out;
    }

    /** @return true for {@code design.lisp}, false for {@code design.use-case.lisp} */
    static boolean isTop(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.indexOf('.') == name.lastIndexOf('.');
    }

    /** @return the top frame a frame belongs to: {@code design.lisp} for {@code design.use-case.lisp} */
    static String topOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.indexOf('.');
        return dot == name.lastIndexOf('.') ? path : path.substring(0, slash + 1) + name.substring(0, dot) + ".lisp";
    }
}
