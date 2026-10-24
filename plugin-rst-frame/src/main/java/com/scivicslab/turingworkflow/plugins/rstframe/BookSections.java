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
 * A book's text cut into the nodes of its table of contents ({@code FrameOntology_261021_oo01}, Solution: shaping the
 * book into a list of subsections). A book's markdown is split into files of a few pages each, so a subsection often
 * runs on into the next file; here the files are read as one text and cut at the headings of the table of contents
 * instead. No LLM is used.
 *
 * <p>Each node gets the text from its heading to the next node's heading, as markdown, with each file's front matter
 * removed; the heading line itself is not part of the text. A heading a page file repeats continues its node. A
 * heading deeper than a subsection stays in its subsection's text. Text that comes before any node of its top-level
 * directory (a preface, the acknowledgments, the cover) belongs to no node and is counted.</p>
 *
 * <p>A page file often ends in the middle of a sentence, even of a word ("You then supply new meth-" and, in the next
 * file, "ods and instance fields ..."). When the text so far ends mid-sentence (in a lower-case letter, a comma, a
 * semicolon or a hyphen) and the next file begins with ordinary text (not a heading, code, a list, a table, an image),
 * the two are one paragraph: the lines are joined, and a hyphen at the break is dropped. A compound split exactly at
 * its own hyphen loses that hyphen; that is taken to be rarer than a word hyphenated by the page.</p>
 */
public final class BookSections {

    /** The text of one node, and the files it was read from, in order. */
    public record Section(String number, String text, List<String> files) {
    }

    /** The sections in the book's order, and what was read but placed in no section. */
    public record Result(List<Section> sections, List<String> notes) {
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R.*?\\R---\\R", Pattern.DOTALL);

    private BookSections() {
    }

    /** @return whether a line is ordinary text, not a heading, code, a list, a table, an image or a quote */
    static boolean isText(String line) {
        String t = line.strip();
        return !(t.startsWith("#") || t.startsWith("```") || t.startsWith("|") || t.startsWith("!") || t.startsWith(">")
                || t.startsWith("- ") || t.startsWith("* ") || t.startsWith("$$") || t.matches("^\\d+\\.\\s.*"));
    }

    /**
     * Joins {@code line}, the first text of a page file, to the paragraph the previous page file ended in, when that
     * paragraph ends mid-sentence.
     *
     * @return whether it was joined
     */
    static boolean joinAcrossPages(StringBuilder text, String line) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end == 0) return false;
        char last = text.charAt(end - 1);
        if (!(Character.isLowerCase(last) || last == ',' || last == ';' || last == '-')) return false;
        int lineStart = text.lastIndexOf("\n", end - 1) + 1;
        if (!isText(text.substring(lineStart, end))) return false;
        text.setLength(end);
        if (last == '-') text.setLength(end - 1);
        else text.append(' ');
        text.append(line.strip()).append('\n');
        return true;
    }

    /**
     * @param files   the book's markdown files, path under the book's directory to text, in the order they are read
     * @param outline the book's table of contents, as {@link BookOutline} read it
     */
    public static Result split(Map<String, String> files, List<BookOutline.Node> outline) {
        Result byHeadings = splitByHeadings(files, outline);
        long placed = byHeadings.sections().stream().filter(x -> !x.text().isEmpty()).count();
        // a book whose outline was not read from "Chapter N: ..." headings: its nodes start where the outline found them
        if (placed * 2 < outline.size()) return splitByOutline(files, outline);
        return byHeadings;
    }

    /** A title reduced to its letters and digits, to find it in a heading whatever the marks and spacing around it. */
    private static String bare(String s) {
        return BookOutline.clean(s).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Cuts the text by the outline itself: each node starts at the first heading of its first file that holds its title;
     * a node whose first file has no such heading (a book cut into one directory per section, a chapter known only by its
     * number) starts at the top of that file. A heading met again later ("... (continued)") starts nothing; the text after
     * it stays with the node it continues.
     */
    static Result splitByOutline(Map<String, String> files, List<BookOutline.Node> outline) {
        Map<String, List<BookOutline.Node>> byFile = new LinkedHashMap<>();
        for (BookOutline.Node n : outline) byFile.computeIfAbsent(n.firstFile(), k -> new ArrayList<>()).add(n);
        Map<String, StringBuilder> texts = new LinkedHashMap<>();
        Map<String, Set<String>> from = new LinkedHashMap<>();
        Map<String, Integer> unplaced = new LinkedHashMap<>();
        // the headings that began a node, bare: met again, they begin nothing and are not text
        Set<String> begun = new java.util.HashSet<>();
        String current = null;
        for (Map.Entry<String, String> f : files.entrySet()) {
            String top = f.getKey().contains("/") ? f.getKey().substring(0, f.getKey().indexOf('/')) : "";
            String body = FRONT_MATTER.matcher(f.getValue()).replaceFirst("");
            String[] lines = body.split("\\R", -1);
            List<BookOutline.Node> pending = new ArrayList<>(byFile.getOrDefault(f.getKey(), List.of()));
            // the nodes whose heading is in this file start there; the others start at its top
            Map<Integer, BookOutline.Node> startAt = new LinkedHashMap<>();
            List<BookOutline.Node> atTop = new ArrayList<>();
            for (BookOutline.Node n : pending) {
                String title = bare(n.title()), numbered = bare(n.number() + " " + n.title());
                int found = -1;
                for (int i = 0; i < lines.length && found < 0; i++) {
                    if (startAt.containsKey(i)) continue;
                    Matcher m = HEADING.matcher(lines[i]);
                    // a title the OCR left as a plain line starts its node only when the line is the title and nothing else
                    if (!m.matches()) {
                        if (!title.isEmpty() && bare(lines[i]).equals(title)) found = i;
                        continue;
                    }
                    String h = bare(m.group(1));
                    if (!title.isEmpty() && (h.equals(title) || h.equals(numbered) || h.endsWith(title) && h.length() <= title.length() + 12)) found = i;
                }
                if (found >= 0) startAt.put(found, n);
                else atTop.add(n);
            }
            for (BookOutline.Node n : atTop) {
                current = n.number();
                texts.computeIfAbsent(current, k -> new StringBuilder());
                from.computeIfAbsent(current, k -> new LinkedHashSet<>()).add(f.getKey());
            }
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                BookOutline.Node starts = startAt.get(i);
                if (starts != null) {
                    begun.add(bare(starts.number() + " " + starts.title()));
                    begun.add(bare(starts.title()));
                    current = starts.number();
                    texts.computeIfAbsent(current, k -> new StringBuilder());
                    from.computeIfAbsent(current, k -> new LinkedHashSet<>()).add(f.getKey());
                    continue;
                }
                if (current == null) {
                    if (!line.isBlank()) unplaced.merge(top, 1, Integer::sum);
                    continue;
                }
                // a heading that repeats one already begun ("2.1 Processors (continued)") is dropped as the first one was
                Matcher again = HEADING.matcher(line);
                if (again.matches()) {
                    String h = bare(again.group(1));
                    if (begun.stream().anyMatch(b -> !b.isEmpty() && h.startsWith(b))) continue;
                }
                StringBuilder t = texts.get(current);
                int n = t.length();
                if (line.isBlank() && (n == 0 || (n >= 2 && t.charAt(n - 1) == '\n' && t.charAt(n - 2) == '\n'))) continue;
                t.append(line).append('\n');
                from.get(current).add(f.getKey());
            }
        }
        List<Section> out = new ArrayList<>();
        for (BookOutline.Node n : outline) {
            StringBuilder t = texts.get(n.number());
            out.add(new Section(n.number(), t == null ? "" : t.toString().strip(),
                    from.containsKey(n.number()) ? List.copyOf(from.get(n.number())) : List.of()));
        }
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : unplaced.entrySet()) {
            notes.add(e.getValue() + " non-blank line(s) in " + (e.getKey().isEmpty() ? "the top directory" : e.getKey()) + " come before any node and belong to none");
        }
        return new Result(out, notes);
    }

    /** Cuts the text by headings classified as {@link BookOutline} classifies a book headed "Chapter N: ...". */
    static Result splitByHeadings(Map<String, String> files, List<BookOutline.Node> outline) {
        Map<String, BookOutline.Node> nodes = new LinkedHashMap<>();
        for (BookOutline.Node n : outline) nodes.put(n.number(), n);
        Map<String, StringBuilder> texts = new LinkedHashMap<>();
        Map<String, Set<String>> from = new LinkedHashMap<>();
        Map<String, Integer> unplaced = new LinkedHashMap<>();
        String dir = null;
        String part = null;
        String chapter = null;
        String current = null;
        for (Map.Entry<String, String> f : files.entrySet()) {
            String top = f.getKey().contains("/") ? f.getKey().substring(0, f.getKey().indexOf('/')) : "";
            if (!top.equals(dir)) {
                // a new top-level directory: a chapter does not run on into the next directory
                dir = top;
                current = null;
                chapter = null;
                part = null;
            }
            String body = FRONT_MATTER.matcher(f.getValue()).replaceFirst("");
            boolean fileStart = true;
            for (String line : body.split("\\R", -1)) {
                if (fileStart && !line.isBlank()) {
                    fileStart = false;
                    if (current != null && isText(line) && joinAcrossPages(texts.get(current), line)) {
                        from.get(current).add(f.getKey());
                        continue;
                    }
                }
                Matcher m = HEADING.matcher(line);
                if (m.matches()) {
                    BookOutline.Heading h = BookOutline.classify(m.group(1), part, chapter);
                    if (h != null && !h.deeper() && !h.foreign() && nodes.containsKey(h.number())) {
                        if (h.level().equals("part")) part = h.number();
                        if (h.level().equals("chapter")) chapter = h.number();
                        current = h.number();
                        texts.computeIfAbsent(current, k -> new StringBuilder());
                        from.computeIfAbsent(current, k -> new LinkedHashSet<>()).add(f.getKey());
                        continue;
                    }
                }
                if (current == null) {
                    if (!line.isBlank()) unplaced.merge(top, 1, Integer::sum);
                    continue;
                }
                StringBuilder t = texts.get(current);
                // a blank line after a blank line adds nothing (a dropped heading leaves the blank lines around it)
                int n = t.length();
                if (line.isBlank() && (n == 0 || (n >= 2 && t.charAt(n - 1) == '\n' && t.charAt(n - 2) == '\n'))) continue;
                t.append(line).append('\n');
                from.get(current).add(f.getKey());
            }
        }
        List<Section> out = new ArrayList<>();
        for (BookOutline.Node n : outline) {
            StringBuilder t = texts.get(n.number());
            out.add(new Section(n.number(), t == null ? "" : t.toString().strip(),
                    from.containsKey(n.number()) ? List.copyOf(from.get(n.number())) : List.of()));
        }
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : unplaced.entrySet()) {
            notes.add(e.getValue() + " non-blank line(s) in " + (e.getKey().isEmpty() ? "the top directory" : e.getKey()) + " come before any node and belong to none");
        }
        return new Result(out, notes);
    }
}
