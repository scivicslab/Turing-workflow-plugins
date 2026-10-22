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
                String h = clean(b.text());
                Matcher m;
                if ((m = PART.matcher(h)).matches()) {
                    part = "P" + m.group(1);
                    if (nodes.containsKey(part)) { repeated++; continue; }
                    nodes.put(part, new Node(part, "part", m.group(2).strip(), null, f.getKey()));
                } else if ((m = CHAPTER.matcher(h)).matches()) {
                    chapter = m.group(1);
                    if (nodes.containsKey(chapter)) { repeated++; continue; }
                    nodes.put(chapter, new Node(chapter, "chapter", m.group(2).strip(), part, f.getKey()));
                } else if ((m = APPENDIX.matcher(h)).matches()) {
                    chapter = "A";
                    if (nodes.containsKey(chapter)) { repeated++; continue; }
                    nodes.put(chapter, new Node(chapter, "chapter", m.group(1).isBlank() ? "Appendix" : m.group(1).strip(), part, f.getKey()));
                } else if ((m = NUMBERED.matcher(h)).matches()) {
                    String number = m.group(1);
                    String[] parts = number.split("\\.");
                    if (chapter == null || !parts[0].equals(chapter)) {
                        notes.add("not a heading of the current chapter " + chapter + ": \"" + h + "\" in " + f.getKey());
                        continue;
                    }
                    if (parts.length > 3) continue;                       // deeper than a subsection: part of its subsection
                    if (nodes.containsKey(number)) { repeated++; continue; }
                    String parent = parts.length == 2 ? chapter : parts[0] + "." + parts[1];
                    if (!nodes.containsKey(parent)) notes.add(number + " comes before its parent " + parent + " in " + f.getKey());
                    nodes.put(number, new Node(number, parts.length == 2 ? "section" : "subsection", m.group(2).strip(), parent, f.getKey()));
                }
            }
        }
        if (repeated > 0) notes.add(repeated + " heading(s) seen again were not added");
        return new Outline(List.copyOf(nodes.values()), notes);
    }

    /** A heading without the emphasis and link marks the OCR leaves in it. */
    static String clean(String heading) {
        return heading.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replaceAll("[*_]", "").replace("\\$", "$").strip();
    }
}
