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
