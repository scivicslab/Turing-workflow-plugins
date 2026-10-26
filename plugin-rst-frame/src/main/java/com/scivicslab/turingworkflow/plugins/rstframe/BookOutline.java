package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the table of contents from a book's markdown files by reading headings when no contents page is present.
 *
 * <p>The class works by identifying the hierarchy of parts, chapters, sections, and subsections from heading numbers
 * and text. It treats any depth beyond a subsection as a subsection. It recognizes numbered headings only if they
 * belong to the current chapter; otherwise, they are treated as text. Appendices are treated as chapters numbered with
 * letters.</p>
 *
 * <ul>
 * <li>{@link #read(Map)} returns an {@link Outline} containing the hierarchical nodes and any notes about the reading
 * process.</li>
 * <li>{@link #withDirectoryChapters(Map, Outline)} returns a new {@link Outline} that incorporates chapters and
 * appendices identified from directory names.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows.</p>
 */
public final class BookOutline {

    /**
     * A single entry in the book's table of contents.
     *
     * @param number the heading's number, such as {@code 1.2} or {@code A.1}
     * @param level the hierarchical level: {@code part}, {@code chapter}, {@code section}, or {@code subsection}
     * @param title the text of the heading
     * @param parent the number of the containing node, or {@code null} if it is a top-level node
     * @param firstFile the path to the first markdown file containing this heading
     */
    public record Node(String number, String level, String title, String parent, String firstFile) {
    }

    /**
     * The book's table of contents in the order of the book, and a list of notes about headings that were read but not
     * included in the outline.
     *
     * @param nodes the list of nodes representing the parts, chapters, sections, and subsections of the book
     * @param notes the list of strings describing why certain headings were skipped or how the outline was constructed
     */
    public record Outline(List<Node> nodes, List<String> notes) {
    }

    /**
     * Represents the classification of a single heading found in the book's text.
     *
     * <p>A heading is one of the following: a node of the table of contents (containing {@code number}, {@code level},
     * {@code title}, and {@code parent}), a heading deeper than a subsection ({@code deeper}), a numbered heading that
     * belongs to a different chapter than the current one ({@code foreign}), or none of these ({@code null} from
     * {@link #classify}).
     *
     * @param number the heading's number, such as "1.2.1" or "A"
     * @param level the hierarchy level, such as "part", "chapter", "section", or "subsection"
     * @param title the text of the heading
     * @param parent the number of the preceding node in the hierarchy
     * @param deeper true if the heading is deeper than a subsection
     * @param foreign true if the heading is a numbered heading that does not belong to the current chapter
     */
    record Heading(String number, String level, String title, String parent, boolean deeper, boolean foreign) {
    }

    private static final Pattern PART = Pattern.compile("^Part\\s+([0-9IVXL]+)\\b\\s*[▪:.\\-–]?\\s*(.*)$");
    /** Matches a heading that identifies a chapter by its number and title, such as "Chapter 6 of Volume II". */
    private static final Pattern CHAPTER = Pattern.compile("^Chapter\\s+(\\d+)\\s*[▪:\\-–]\\s*(.+)$");
    private static final Pattern APPENDIX = Pattern.compile("^Appendix\\b\\s*[▪:.\\-–]?\\s*(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s+(.+)$");

    private BookOutline() {
    }

    /**
     * Returns an {@link Outline} of the book's structure by reading the headings in the provided markdown files.
     *
     * <p>The result is an {@link Outline} containing the hierarchy of parts, chapters, sections, and subsections,
     * along with any notes about headings that could not be placed in the hierarchy. If a chapter has no numbered
     * sections in its files, the method attempts to identify sections by looking for "## " headings in the text.
     *
     * <p>The caller must provide the files in the order they are to be read.
     *
     * @param files a map where the keys are the paths to the book's markdown files and the values are the text content
     * of those files
     */
    public static Outline read(Map<String, String> files) {
        Outline o = readOutline(files);
        return new Outline(withHeadingSections(o.nodes(), files), o.notes());
    }

    /**
     * Adds section nodes to chapters that lack numbered sections by using "## " headings found in the chapter's files.
     *
     * <p>The method scans the files belonging to a chapter to find headings that look like titles. If a chapter has no
     * numbered sections, it creates new {@link Node} objects for these headings, numbering them sequentially (e.g.,
     * "1.1", "1.2") based on the order they appear in the files. If a chapter contains any bold headings (marked with
     * "**"), only the bold headings are treated as sections; plain headings are treated as sidebars and ignored.
     * Headings that are part of code blocks or do not match the title-like pattern are skipped.
     *
     * <p>Returns a list of nodes containing the original outline nodes plus the newly created section nodes.
     *
     * @param nodes the current list of outline nodes
     * @param files a map of file paths to their text content
     */
    static List<Node> withHeadingSections(List<Node> nodes, Map<String, String> files) {
        // a chapter with any node numbered under it has its sections
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (Node n : nodes) if (n.number().contains(".")) parents.add(n.number().substring(0, n.number().indexOf('.')));
        List<String> paths = new ArrayList<>(files.keySet());
        List<Node> chapters = nodes.stream().filter(n -> n.level().equals("chapter")).toList();
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            out.add(n);
            if (!n.level().equals("chapter") || parents.contains(n.number())) continue;
            // the chapter's files: from its first file to the next chapter's first file
            int from = paths.indexOf(n.firstFile());
            int to = paths.size();
            for (Node c : chapters) {
                int i = paths.indexOf(c.firstFile());
                if (i > from && i < to) to = i;
            }
            if (from < 0) continue;
            List<String[]> found = new ArrayList<>();
            boolean anyBold = false;
            for (int i = from; i < to; i++) {
                boolean code = false;
                for (String line : files.get(paths.get(i)).split("\n")) {
                    if (line.startsWith("```")) code = !code;
                    if (code || !line.startsWith("## ")) continue;
                    boolean bold = line.substring(3).strip().startsWith("**");
                    String h = clean(line.substring(3));
                    if (!TITLE_LIKE.matcher(h).matches() || NUMBERED_ANY.matcher(h).matches() || h.endsWith(":") || h.endsWith(".") || h.matches("(?i)(chapter|appendix)\\b.*")
                            || h.equalsIgnoreCase(n.title())) continue;
                    anyBold |= bold;
                    found.add(new String[] {h, bold ? "b" : "", paths.get(i)});
                }
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            int k = 0;
            for (String[] h : found) {
                if (anyBold && h[1].isEmpty() || !seen.add(h[0])) continue;
                String number = n.number() + "." + (++k);
                out.add(new Node(number, "section", h[0], n.number(), h[2]));
            }
        }
        return out;
    }

    private static Outline readOutline(Map<String, String> files) {
        // a book whose chapters are not headed "Chapter N: ..." (Globus Toolkit, Aho, Tanenbaum) is read by its numbers
        boolean headedChapters = false;
        for (String text : files.values()) {
            for (BookText.Block b : BookText.read(text)) {
                if (b.kind() == BookText.Kind.HEADING && CHAPTER.matcher(clean(b.text())).matches()) {
                    headedChapters = true;
                    break;
                }
            }
            if (headedChapters) break;
        }
        if (!headedChapters) {
            // a book cut into one directory per section ("1.1. Linux Versus Other Unix-Like Kernels _ <book>_p001-005")
            long numberedDirs = files.keySet().stream().filter(k -> DIR_NUMBERED.matcher(dirOf(k)).matches()).count();
            if (numberedDirs * 2 > files.size()) return readByDirectories(files);
            // a book cut into one directory per chapter ("CHAPTER 1_ Introduction _ <book>_p001-005"), its sections unnumbered
            long chapterDirs = files.keySet().stream().filter(k -> DIR_CHAPTER.matcher(dirOf(k)).find()).count();
            Outline numbered = readNumbered(files);
            if (chapterDirs * 2 > files.size() && numbered.nodes().stream().noneMatch(n -> !n.level().equals("chapter"))) return readByChapterDirectories(files);
            return numbered;
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String part = null;
        String chapter = null;
        int repeated = 0;
        for (Map.Entry<String, String> f : files.entrySet()) {
            for (BookText.Block b : BookText.read(f.getValue())) {
                if (b.kind() != BookText.Kind.HEADING) continue;
                Heading h = classify(b.text(), part, chapter);
                if (h == null || h.deeper()) continue;
                if (h.foreign()) {
                    notes.add("not a heading of the current chapter " + chapter + ": \"" + clean(b.text()) + "\" in " + f.getKey());
                    continue;
                }
                if (h.level().equals("part")) part = h.number();
                if (h.level().equals("chapter")) chapter = h.number();
                if (nodes.containsKey(h.number())) {
                    repeated++;
                    continue;
                }
                if (h.parent() != null && !nodes.containsKey(h.parent()) && !h.level().equals("chapter")) {
                    notes.add(h.number() + " comes before its parent " + h.parent() + " in " + f.getKey());
                }
                nodes.put(h.number(), new Node(h.number(), h.level(), h.title(), h.parent(), f.getKey()));
            }
        }
        if (repeated > 0) notes.add(repeated + " heading(s) seen again were not added");
        return new Outline(inBookOrder(List.copyOf(nodes.values())), notes);
    }

    /**
     * Matches a directory name that identifies a section by its number and title, such as "1.1. Linux Versus Other
     * Unix-Like Kernels _ <book>_p001-005".
     */
    private static final Pattern DIR_NUMBERED = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})*)\\.\\s+(.+?)\\s+_\\s.*$");
    /**
     * Matches a directory name for a single chapter or appendix, such as "CHAPTER 1_ Introduction _ <book>_p001-005"
     * or "APPENDIX C_ Glossary _ <book>_p011-015".
     */
    private static final Pattern DIR_CHAPTER = Pattern.compile("(?i)^(?:chapter\\s+(\\d{1,2})|appendix\\s+([A-Z]))_\\s*(.+?)\\s+_\\s");
    /**
     * Matches a heading that reads as a section's title, starting with a capital letter and not ending as a sentence
     * or a label.
     */
    private static final Pattern TITLE_LIKE = Pattern.compile("^[A-Z][^\\[\\]{}();=#]{2,80}$");

    /** Returns the name of the directory a file is in, or the file's own name if it is not in a directory. */
    static String dirOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String dir = slash < 0 ? p : p.substring(0, slash);
        int up = dir.lastIndexOf('/');
        return up < 0 ? dir : dir.substring(up + 1);
    }

    /**
     * Returns an {@link Outline} of a book where each section is a single directory named by its number and title,
     * such as "1.1. Linux Versus Other Unix-Like Kernels _ <book>_p001-005".
     *
     * <p>The depth of the section number determines whether a node is a chapter, a section, or a subsection; headings
     * deeper than a subsection are excluded.
     */
    static Outline readByDirectories(Map<String, String> files) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (String path : files.keySet()) {
            Matcher m = DIR_NUMBERED.matcher(dirOf(path));
            if (!m.matches()) continue;
            String number = m.group(1);
            if (nodes.containsKey(number)) continue;
            String[] parts = number.split("\\.");
            if (parts.length > 3) continue;
            String level = parts.length == 1 ? "chapter" : parts.length == 2 ? "section" : "subsection";
            String parent = parts.length == 1 ? null : parts.length == 2 ? parts[0] : parts[0] + "." + parts[1];
            if (parent != null && !nodes.containsKey(parent)) notes.add(number + " comes before its parent " + parent + " in " + path);
            nodes.put(number, new Node(number, level, m.group(2).replace('_', ':').strip(), parent, path));
        }
        return new Outline(inBookOrder(List.copyOf(nodes.values())), notes);
    }

    /**
     * Returns an {@link Outline} for a book where chapters are organized into separate directories and sections are
     * unnumbered.
     *
     * <p>The chapter is identified from the directory name, and sections are created from markdown headings found
     * within those directories that qualify as titles. A heading is treated as a section title if it is not a line of
     * code and is not a heading of a different level or type. Sections are numbered sequentially based on the order
     * they are encountered within each chapter. Duplicate headings within the same chapter are ignored.
     *
     * @param files the book's markdown files, path to text, in the order they are to be read (the caller sorts them)
     * @return an {@link Outline} containing the identified chapters and their sequentially numbered sections
     */
    static Outline readByChapterDirectories(Map<String, String> files) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Integer> next = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            Matcher d = DIR_CHAPTER.matcher(dirOf(f.getKey()));
            if (!d.find()) continue;
            String chapter = d.group(1) != null ? d.group(1) : d.group(2);
            if (!nodes.containsKey(chapter)) nodes.put(chapter, new Node(chapter, "chapter", d.group(3).strip(), null, f.getKey()));
            // the raw lines: a "## " heading is a section's, a "#define" line in code is not a heading at all
            boolean code = false;
            for (String line : f.getValue().split("\n")) {
                if (line.startsWith("```")) code = !code;
                if (code || !line.startsWith("## ")) continue;
                String h = clean(line.substring(3));
                if (!TITLE_LIKE.matcher(h).matches() || h.endsWith(":") || h.endsWith(".") || h.matches("(?i)(chapter|appendix)\\b.*")) continue;
                // a title seen again in the same chapter (a heading repeated across page files) is one section
                if (!seen.add(chapter + "\t" + h)) continue;
                int n = next.merge(chapter, 1, Integer::sum);
                String number = chapter + "." + n;
                nodes.put(number, new Node(number, "section", h, chapter, f.getKey()));
            }
        }
        return new Outline(inBookOrder(List.copyOf(nodes.values())), List.of());
    }

    /** A directory of one chapter or appendix named in Japanese: "29_第27章 Recovery from Errors", "30_付録A Answers to All the Exercises". */
    private static final Pattern DIR_JA = Pattern.compile("^\\d+_(?:第\\s*(\\d{1,3})\\s*章|付録\\s*([A-Z]))\\s*(.*)$");
    private static final Pattern ANY_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");

    /**
     * The outline with the chapters and appendices its book's directories name ("29_第27章 Recovery from Errors",
     * "30_付録A Answers to All the Exercises") that it does not have. A book cut into such directories says where each of
     * them begins even when its headings do not: an appendix whose OCR lost its heading, or whose heading the model took for
     * the name of the chapter before it, otherwise runs on as the end of that chapter. A directory's node begins at its
     * first file, or at the last file of the directory before it when that file holds a heading of its title (the
     * appendix began on the page the chapter before it ended). A node of the outline that names the chapter (its title,
     * its letter and title, or its letter alone) is its heading where the outline found it, and becomes the chapter in its
     * place. Fewer than two such directories leave the outline as it is.
     *
     * @param files the book's markdown files, path under the book's directory to text, in the order they are read
     */
    public static Outline withDirectoryChapters(Map<String, String> files, Outline outline) {
        Map<String, List<String>> dirs = new LinkedHashMap<>();
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash > 0) dirs.computeIfAbsent(path.substring(0, slash), k -> new ArrayList<>()).add(path);
        }
        if (dirs.keySet().stream().filter(d -> DIR_JA.matcher(d).matches()).count() < 2) return outline;
        Map<String, Integer> order = new LinkedHashMap<>();
        for (String path : files.keySet()) order.put(path, order.size());
        List<Node> nodes = new ArrayList<>(outline.nodes());
        List<String> notes = new ArrayList<>(outline.notes());
        java.util.Set<String> chapters = new java.util.HashSet<>();
        for (Node n : nodes) if (n.level().equals("chapter")) chapters.add(n.number());
        List<String> previous = null;
        for (Map.Entry<String, List<String>> d : dirs.entrySet()) {
            Matcher m = DIR_JA.matcher(d.getKey());
            List<String> before = previous;
            previous = d.getValue();
            if (!m.matches()) continue;
            String number = m.group(1) != null ? m.group(1) : m.group(2);
            if (chapters.contains(number)) continue;
            String title = m.group(3).strip().isEmpty() ? d.getKey() : m.group(3).strip();
            String first = d.getValue().get(0);
            if (before != null) {
                String last = before.get(before.size() - 1);
                for (String line : files.get(last).split("\\R")) {
                    Matcher h = ANY_HEADING.matcher(line);
                    if (h.matches() && bareTitle(h.group(1)).equals(bareTitle(title))) {
                        first = last;
                        break;
                    }
                }
            }
            // a node of the outline that names this chapter ("D Dirty Tricks", "E") is its heading, found where it stands
            java.util.Set<String> names = java.util.Set.of(bareTitle(title), bareTitle(number + " " + title), bareTitle(number));
            int at = -1;
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                if (n.level().equals("chapter") || !names.contains(bareTitle(n.title()))) continue;
                if (at < 0) {
                    at = i;
                    first = n.firstFile();
                } else {
                    nodes.remove(i--);
                }
            }
            if (at >= 0) {
                nodes.set(at, new Node(number, "chapter", title, null, first));
            } else {
                at = nodes.size();
                int from = order.getOrDefault(first, Integer.MAX_VALUE);
                for (int i = 0; i < nodes.size(); i++) {
                    if (order.getOrDefault(nodes.get(i).firstFile(), -1) > from) {
                        at = i;
                        break;
                    }
                }
                nodes.add(at, new Node(number, "chapter", title, null, first));
            }
            chapters.add(number);
            notes.add("chapter " + number + " \"" + title + "\" added from its directory " + d.getKey());
        }
        return new Outline(nodes, notes);
    }

    /**
     * Reduces a title to a sequence of alphanumeric characters to allow comparison between headings and directory
     * names.
     */
    static String bareTitle(String s) {
        return clean(s).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Matches a heading that begins with a number or letter followed by a sequence of dot-separated numbers, such as
     * "24.2.1 The FilesForSale Service" or "A.2 Installing the Toolkit".
     */
    private static final Pattern NUMBERED_ANY = Pattern.compile("^([A-Z]|\\d{1,2})((?:\\.\\d{1,3})+)\\.?\\s+(\\S.*)$");
    /**
     * Matches a heading that identifies a chapter by its number and title, such as "2 COMPUTER SYSTEMS ORGANIZATION".
     */
    private static final Pattern BARE_CHAPTER = Pattern.compile("^([1-9]\\d?)\\.?\\s+(\\p{L}[\\p{L}\\p{N} ,:&'()-]{2,})$");

    /**
     * Constructs an {@link Outline} from a book's markdown files by parsing numbered headings that do not use the
     * "Chapter N: ..." format.
     *
     * <p>The resulting {@link Outline} contains a hierarchy of chapters, sections, and subsections where a heading's
     * level is determined by the number of digits in its prefix (e.g., "1" is a chapter, "1.1" is a section, and
     * "1.1.1" is a subsection). Headings with more than three digits, unnumbered headings, or lines of code starting
     * with "#" are ignored. A chapter's title is taken from a heading that matches the chapter number, or defaults to
     * "Chapter" or "Appendix" followed by the number if no such heading exists.
     *
     * @param files the book's markdown files, path to text, in the order they are to be read
     * @return an {@link Outline} containing the hierarchy of nodes and any notes about headings that appeared out of
     * order or were repeated
     */
    static Outline readNumbered(Map<String, String> files) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int repeated = 0;
        // the chapters' titles first, wherever their headings stand
        for (String text : files.values()) {
            for (BookText.Block b : BookText.read(text)) {
                if (b.kind() != BookText.Kind.HEADING) continue;
                Matcher c = BARE_CHAPTER.matcher(clean(b.text()));
                if (c.matches()) titles.putIfAbsent(c.group(1), c.group(2).strip());
            }
        }
        for (Map.Entry<String, String> f : files.entrySet()) {
            for (BookText.Block b : BookText.read(f.getValue())) {
                if (b.kind() != BookText.Kind.HEADING) continue;
                String h = clean(b.text());
                Matcher m = NUMBERED_ANY.matcher(h);
                if (!m.matches()) {
                    Matcher c = BARE_CHAPTER.matcher(h);
                    if (c.matches() && !nodes.containsKey(c.group(1))) {
                        nodes.put(c.group(1), new Node(c.group(1), "chapter", c.group(2).strip(), null, f.getKey()));
                    }
                    continue;
                }
                String chapter = m.group(1);
                String number = chapter + m.group(2);
                String[] parts = number.split("\\.");
                if (parts.length > 3) continue;
                if (!nodes.containsKey(chapter)) {
                    String title = titles.getOrDefault(chapter, chapter.matches("\\d+") ? "Chapter " + chapter : "Appendix " + chapter);
                    nodes.put(chapter, new Node(chapter, "chapter", title, null, f.getKey()));
                }
                if (nodes.containsKey(number)) {
                    repeated++;
                    continue;
                }
                String parent = parts.length == 2 ? chapter : parts[0] + "." + parts[1];
                if (!nodes.containsKey(parent)) notes.add(number + " comes before its parent " + parent + " in " + f.getKey());
                nodes.put(number, new Node(number, parts.length == 2 ? "section" : "subsection", m.group(3).strip(), parent, f.getKey()));
            }
        }
        if (repeated > 0) notes.add(repeated + " heading(s) seen again were not added");
        return new Outline(inBookOrder(List.copyOf(nodes.values())), notes);
    }

    /**
     * Returns a list of nodes sorted by the book's logical structure.
     *
     * <p>The list follows the order of parts, then numbered chapters in ascending order, followed by lettered
     * appendices. Within each chapter, sections and subsections follow the order in which they were encountered in the
     * files. If the nodes include a part, the input order is preserved without reordering by chapter.
     *
     * @param read the list of nodes to be sorted
     */
    static List<Node> inBookOrder(List<Node> read) {
        if (read.stream().anyMatch(n -> n.level().equals("part"))) return read;
        Map<String, List<Node>> byChapter = new LinkedHashMap<>();
        for (Node n : read) byChapter.computeIfAbsent(n.number().split("\\.")[0], k -> new ArrayList<>()).add(n);
        List<String> chapters = new ArrayList<>(byChapter.keySet());
        chapters.sort((a, b) -> {
            boolean na = a.matches("\\d+"), nb = b.matches("\\d+");
            if (na && nb) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            if (na != nb) return na ? -1 : 1;
            return a.compareTo(b);
        });
        List<Node> out = new ArrayList<>();
        for (String c : chapters) out.addAll(byChapter.get(c));
        return out;
    }

    /**
     * Classifies a raw heading string into a {@link Heading} based on the current {@code part} and {@code chapter}.
     *
     * @param raw the heading text as read from the file
     * @param part the current part number or null
     * @param chapter the current chapter number or null
     * @return a {@link Heading} containing the number, level, title, parent, and whether it is deeper than a
     * subsection or foreign to the current chapter, or {@code null} if the text is not a heading
     */
    static Heading classify(String raw, String part, String chapter) {
        String h = clean(raw);
        Matcher m;
        if ((m = PART.matcher(h)).matches()) return new Heading("P" + m.group(1), "part", m.group(2).strip(), null, false, false);
        if ((m = CHAPTER.matcher(h)).matches()) return new Heading(m.group(1), "chapter", m.group(2).strip(), part, false, false);
        if ((m = APPENDIX.matcher(h)).matches()) {
            return new Heading("A", "chapter", m.group(1).isBlank() ? "Appendix" : m.group(1).strip(), part, false, false);
        }
        if ((m = NUMBERED.matcher(h)).matches()) {
            String number = m.group(1);
            String[] parts = number.split("\\.");
            if (chapter == null || !parts[0].equals(chapter)) return new Heading(number, null, m.group(2).strip(), null, false, true);
            if (parts.length > 3) return new Heading(number, null, m.group(2).strip(), null, true, false);
            String parent = parts.length == 2 ? chapter : parts[0] + "." + parts[1];
            return new Heading(number, parts.length == 2 ? "section" : "subsection", m.group(2).strip(), parent, false, false);
        }
        return null;
    }

    /**
     * Returns a string that is the input heading with Markdown link syntax and emphasis marks removed.
     *
     * <p>The result is the text inside any {@code [text](url)} pattern, with all {@code *} and {@code _} characters
     * stripped and {@code \$} characters preserved. A heading that is already clean has no effect.</p>
     *
     * @param heading the raw heading text containing Markdown syntax
     */
    static String clean(String heading) {
        return heading.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replaceAll("[*_]", "").replace("\\$", "$").strip();
    }
}
