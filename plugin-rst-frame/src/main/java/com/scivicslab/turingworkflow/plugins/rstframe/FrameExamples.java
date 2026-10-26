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
 * Finds and returns text from books or other frames to serve as examples for a specific part of a frame.
 *
 * <ul>
 * <li>{@code forBlank(frame, blank, max)} returns a list of up to {@code max} {@link Example}s that illustrate the
 * part of the frame named by {@code blank}. The examples are chosen by matching the segments of {@code blank} against
 * the frame's roles table or its edge list, prioritizing the most specific part.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * // Returns examples for the 'what' part of a 'flow-what' segment in a frame
 * List<Example> examples = frameExamples.forBlank("explanation/explain-program.lisp", "flows/2/flow-what", 5);
 * }</pre>
 *
 * <p>If no examples are found for the specified blank, an empty list is returned. If the frame path does not exist, an
 * empty list is returned.</p>
 */
public final class FrameExamples {

    /**
     * A single example consisting of its source and its text.
     *
     * @param source the source of the example, such as a book and rule name
     * @param text the text of the example, truncated to 700 characters if necessary
     */
    public record Example(String source, String text) {
    }

    private static final Pattern KEYED = Pattern.compile("([A-Za-z][A-Za-z0-9-]*)( \\([^)]*\\))?:(.*)");
    /**
     * Matches the header line of a roles table, such as {@code ;;; roles (role: a / b)}, where the colon may be
     * omitted if a parenthesis follows.
     */
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
     * Initializes a search engine that finds paragraphs from books to serve as examples for specific parts of a frame.
     *
     * @param frames a map of every frame file, keyed by its path under {@code frames/} (e.g., {@code
     * explanation/explain-program.lisp})
     *
     * @param readWorks a function that takes a path under the works directory and returns the file text, or null if
     * the file does not exist
     */
    public FrameExamples(Map<String, String> frames, Function<String, String> readWorks) {
        this(frames, List.of(), readWorks);
    }

    /**
     * Represents one end of an edge in the frame link graph.
     *
     * @param file the path to the file, either under {@code frames/} or under the works directory
     * @param rule the name of the rule within that file
     */
    private record End(String file, String rule) {
    }

    /** Maps each end of an edge to its connected ends, grouped by the kind of relationship. */
    private final Map<End, Map<String, List<End>>> edges = new LinkedHashMap<>();

    /**
     * Initializes an actor that finds paragraphs of source books to serve as examples for a blank, using edge lists to
     * connect frames and rules.
     *
     * @param frames every frame file, by its path under {@code frames/} ({@code explanation/explain-program.lisp})
     * @param links the edge lists ({@code links/**}{@code /*.tsv}), as texts: five tab-separated columns {@code a_file
     * a_rule b_file b_rule kind}, lines starting with {@code #} left out
     *
     * @param readWorks reads a path under the works directory; null when there is no such file
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
     * Returns a list of examples that can be used to illustrate a blank in a frame.
     *
     * <ul>
     * <li>If the frame is an integrated frame, it returns the paragraphs of the concrete rules it integrates that
     * match the blank's name or its segments, with the nearest parts appearing first.</li>
     * <li>If the frame is a concrete frame, it returns the other paragraphs from the integrated frames that use this
     * frame, matching the blank's segments.</li>
     * <li>If no edges exist, it searches the roles tables of the frame's group to find the row matching the blank's
     * segments.</li>
     * <li>If the frame is not part of a group, it returns the {@code :sample} sentences of the rule named by the
     * blank.</li>
     * </ul>
     *
     * <p>The list contains at most {@code max} examples. If no examples are found, an empty list is returned.</p>
     *
     * <pre>{@code
     * List<Example> examples = frameExamples.forBlank("explanation/explain-program.lisp", "p-what/n", 5);
     * }</pre>
     *
     * @param frame the path of the frame being filled, under {@code frames/}
     * @param blank the name of the blank as it appears in the rule file
     * @param max the maximum number of examples to return
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

    /** Returns true if the frame has any edges defined in the edge lists. */
    private boolean hasEdges(String frame) {
        for (End e : edges.keySet()) if (e.file().equals(frame)) return true;
        return false;
    }

    private List<End> joined(End at, String kind) {
        return edges.getOrDefault(at, Map.of()).getOrDefault(kind, List.of());
    }

    /**
     * Returns an {@link Example} containing the text of the paragraphs a concrete rule was made from, or the rule's
     * own {@code :sample} sentences if no source paragraphs are found.
     *
     * @param concrete the rule of a concrete frame being queried
     * @param seen a set of previously visited rule identifiers to prevent infinite loops or redundant processing
     * @return an {@link Example} whose {@code source} lists the book files and rule names, and whose {@code text}
     * contains the joined paragraph text (truncated to 700 characters), or {@code null} if the rule has already been
     * processed or contains no valid text.
     */
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

    /**
     * Returns a list of names from the table of roles that correspond to the part named {@code seg}, or to the longest
     * part whose name is a prefix of {@code seg} followed by a hyphen.
     */
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

    /**
     * Returns an {@link Example} consisting of the {@code :sample} sentences from a rule and its sub-rules (those
     * named {@code name/n}) within the specified concrete frames.
     *
     * The result is the first such example found in file order, where the source is the frame path and the rule name,
     * and the text is the joined {@code :sample} attributes.
     *
     * If no rules match the name, or if the rules have no non-blank {@code :sample} attributes, or if all matching
     * rules belong to books in {@code skipBooks}, or if the frame has already been visited in this call, returns
     * {@code null}.
     *
     * @param name the name of the rule to find, or a prefix of a rule name (e.g., {@code p-what} matches {@code
     * p-what/n})
     *
     * @param concrete the set of frame paths to search within
     * @param skipBooks the set of book paths to ignore
     * @param seen the set of strings used to track visited frames and rules to avoid duplicates
     * @return an {@link Example} containing the source and the joined sample text, or {@code null} if no valid example
     * is found
     */
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

    /**
     * Returns a list of the book files that a concrete frame was made from, including those from its own {@code
     * made-from} lines and those from its top frame's.
     */
    private List<String> booksOf(String frame) {
        List<String> lines = new ArrayList<>();
        if (frames.containsKey(frame)) lines.addAll(header(frames.get(frame), "made-from"));
        String top = topOf(frame);
        if (!top.equals(frame) && frames.containsKey(top)) lines.addAll(header(frames.get(top), "made-from"));
        List<String> out = new ArrayList<>();
        for (String v : lines) for (String f : sourceFiles(v)) if (!out.contains(f)) out.add(f);
        return out;
    }

    /**
     * Collects all text content from a rule and any sub-rules it references, excluding attribute values.
     *
     * @param f the rule file containing the rule
     * @param name the name of the rule to process
     * @param seen a set of names already processed to prevent infinite recursion in circular rule references
     * @return a list of strings containing the text of the rule and its descendants
     */
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

    /**
     * Returns a list of the names of the parts contained in a blank's name.
     *
     * The list contains the segments of the {@code blank} string separated by slashes, excluding empty strings,
     * numeric strings, and the letter {@code n}, ordered from the most specific (rightmost) to the most general
     * (leftmost).
     *
     * <pre>{@code
     * // If blank is "flows/2/flow-what/n"
     * segments("flows/2/flow-what/n"); // returns ["flow-what", "flows"]
     *
     * // If blank is "p-what/n"
     * segments("p-what/n"); // returns ["p-what"]
     * }</pre>
     */
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

    /**
     * Parses a {@code ;;; roles} table from text into a mapping of part names to the words that occupy their cells.
     *
     * @param text the text containing the roles table, where the table begins with a header line like {@code ;;; roles
     * (role: a / b)} and rows are indented with three spaces.
     *
     * @return a map where each key is a part name and the value is a list of words found in that part's cells.
     */
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

    /**
     * Extracts all values associated with a specific header key from a frame file text, joining values that continue
     * on subsequent {@code ;;;} lines with spaces.
     *
     * @param text the frame file text to parse
     * @param key the name of the header key to extract
     */
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

    /**
     * Returns a list of paths under {@code frames/} for the frames that the given text's {@code ;;; integrates:}
     * header specifies.
     */
    static List<String> integrates(String text) {
        List<String> out = new ArrayList<>();
        for (String v : header(text, "integrates")) {
            String first = v.split("\\s+")[0];
            if (first.endsWith(".lisp")) out.add(first);
        }
        return out;
    }

    /**
     * Returns a list of file paths for the books that a {@code made-from} value refers to.
     *
     * @param madeFrom the text of the {@code made-from} line, where the part before {@code " / "} contains names of
     * entries starting with {@code doc_}
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

    /**
     * Returns true if the frame at the given path is a top-level frame.
     *
     * @param path the path of the frame under {@code frames/}
     */
    static boolean isTop(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.indexOf('.') == name.lastIndexOf('.');
    }

    /**
     * Returns the path of the top-level frame that the given frame belongs to.
     *
     * If the path is already a top-level frame, the result is the same as the input. For a sub-frame like {@code
     * explanation/design.use-case.lisp}, it returns the path to the top-level frame {@code explanation/design.lisp}.
     */
    static String topOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.indexOf('.');
        return dot == name.lastIndexOf('.') ? path : path.substring(0, slash + 1) + name.substring(0, dot) + ".lisp";
    }
}
