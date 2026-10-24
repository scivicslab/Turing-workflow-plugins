package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The table of contents of a book, read from the headings of its markdown files when the book has no contents page
 * ({@code FrameOntology_261021_oo01}, Solution: getting the concept hierarchy). No LLM is used.
 *
 * <p>A book is parts, chapters, sections and subsections; anything deeper is rounded to its subsection. OCR writes
 * every heading at the same depth, so the depth is read from the heading's number: {@code Chapter 4 ▪ Objects and
 * Classes} is chapter 4, {@code 4.1. ...} a section, {@code 4.1.1. ...} a subsection. A numbered heading counts only
 * when its number lies in the current chapter; others ({@code 1. Always keep data private.}, a numbered item the OCR
 * made a heading) are text. {@code Appendix} is a chapter of its own, numbered {@code A}. A heading seen again (a page
 * file repeating it) adds nothing.</p>
 */
public final class BookOutline {

    /** One node of the table of contents. {@code level} is {@code part}, {@code chapter}, {@code section} or {@code subsection}. */
    public record Node(String number, String level, String title, String parent, String firstFile) {
    }

    /** The table of contents, in the book's order, and what was read but not taken. */
    public record Outline(List<Node> nodes, List<String> notes) {
    }

    /**
     * What one heading is: a node of the table of contents ({@code number}, {@code level}, {@code title}, {@code parent}),
     * a heading deeper than a subsection ({@code deeper}), a numbered heading outside the current chapter
     * ({@code foreign}), or none of these ({@code null} from {@link #classify}).
     */
    record Heading(String number, String level, String title, String parent, boolean deeper, boolean foreign) {
    }

    private static final Pattern PART = Pattern.compile("^Part\\s+([0-9IVXL]+)\\b\\s*[▪:.\\-–]?\\s*(.*)$");
    /** A separator and a title must follow the number: "Chapter 6 of Volume II", a cross-reference the OCR made a heading, is no chapter. */
    private static final Pattern CHAPTER = Pattern.compile("^Chapter\\s+(\\d+)\\s*[▪:\\-–]\\s*(.+)$");
    private static final Pattern APPENDIX = Pattern.compile("^Appendix\\b\\s*[▪:.\\-–]?\\s*(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s+(.+)$");

    private BookOutline() {
    }

    /**
     * @param files the book's markdown files, path to text, in the order they are to be read (the caller sorts them)
     */
    public static Outline read(Map<String, String> files) {
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

    /** A directory named by its section's number and title: "1.1. Linux Versus Other Unix-Like Kernels _ <book>_p001-005". */
    private static final Pattern DIR_NUMBERED = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})*)\\.\\s+(.+?)\\s+_\\s.*$");
    /** A directory of one chapter: "CHAPTER 1_ Introduction _ <book>_p001-005", "APPENDIX C_ Glossary _ <book>_p011-015". */
    private static final Pattern DIR_CHAPTER = Pattern.compile("(?i)^(?:chapter\\s+(\\d{1,2})|appendix\\s+([A-Z]))_\\s*(.+?)\\s+_\\s");
    /** A heading that reads as a section's title: it starts with a capital letter and does not end as a sentence or a label does. */
    private static final Pattern TITLE_LIKE = Pattern.compile("^[A-Z][^\\[\\]{}();=#]{2,80}$");

    /** @return the name of the directory a file is in, the file's own name when it is in none */
    static String dirOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String dir = slash < 0 ? p : p.substring(0, slash);
        int up = dir.lastIndexOf('/');
        return up < 0 ? dir : dir.substring(up + 1);
    }

    /**
     * Reads a book cut into one directory per section, named by the section's number and title: the number's depth makes
     * a chapter, a section or a subsection; deeper ones are left out.
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
     * Reads a book cut into one directory per chapter whose sections are not numbered: the chapter comes from its
     * directory's name; each heading in it that reads as a title ("The Network Device", not "Helper methods:" nor a line
     * of code) is a section, numbered in the order it is met.
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

    /** A numbered heading of a book read by its numbers: "24.2.1 The FilesForSale Service", "A.2 Installing the Toolkit". */
    private static final Pattern NUMBERED_ANY = Pattern.compile("^([A-Z]|\\d{1,2})((?:\\.\\d{1,2})+)\\.?\\s+(\\S.*)$");
    /** A chapter's own heading in such a book: its number and its title, "2 COMPUTER SYSTEMS ORGANIZATION". */
    private static final Pattern BARE_CHAPTER = Pattern.compile("^([1-9]\\d?)\\.?\\s+(\\p{L}[\\p{L}\\p{N} ,:&'()-]{2,})$");

    /**
     * Reads a book whose chapters are not headed "Chapter N: ...": a numbered heading belongs to the chapter of its first
     * number or letter, which is made when it is first met; a heading of a chapter number and a title gives that chapter
     * its title. Two numbers make a section, three a subsection; deeper ones, unnumbered headings, and lines of code that
     * begin with "#" are left out.
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
     * The nodes in the book's order: the files are read in path order, in which a directory such as {@code Appendix}
     * comes before {@code Chap01}. Chapters go by their number, lettered ones (an appendix) after the numbered ones;
     * each chapter's sections and subsections stay in the order they were read. A book with parts keeps the order read.
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

    /** @return what a heading is, given the part and chapter it comes in ({@code null} for none), or {@code null} when it is none of the kinds */
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

    /** A heading without the emphasis and link marks the OCR leaves in it. */
    static String clean(String heading) {
        return heading.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replaceAll("[*_]", "").replace("\\$", "$").strip();
    }
}
