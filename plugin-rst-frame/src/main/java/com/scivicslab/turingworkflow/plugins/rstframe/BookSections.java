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

    /** The outline and the sections after the sections over the limit were cut into pieces. */
    public record Pieces(List<BookOutline.Node> outline, List<Section> sections, List<String> notes) {
    }

    /** The number of a piece cut from a section that was too large: the section's number and ".p2", ".p3", ... */
    public static final Pattern PIECE = Pattern.compile("\\.p\\d+$");
    /** No section is cut below this many characters, whatever its book's median. */
    public static final int LIMIT_FLOOR = 50_000;

    /**
     * How large a section of this book may be before it is cut: four times the median of its sections that have text, and
     * never under {@link #LIMIT_FLOOR}. Four medians leave a book's long chapters whole (texbook's longest chapters are a
     * little over two medians); the floor keeps a book of very short sections (a reference, a book of headed paragraphs)
     * from having its ordinary sections cut.
     */
    public static int limit(List<Section> sections) {
        int[] sizes = sections.stream().mapToInt(x -> x.text().length()).filter(n -> n > 0).sorted().toArray();
        if (sizes.length == 0) return LIMIT_FLOOR;
        long median = sizes[sizes.length / 2];
        return (int) Math.max(LIMIT_FLOOR, Math.min(Integer.MAX_VALUE, 4 * median));
    }

    /**
     * Cuts each section longer than {@code limit} characters into pieces, so that no one section holds a large part of the
     * book (an appendix the outline ran on into the chapter before it, a book whose chapters the outline missed). A piece
     * begins at a heading of the section's own text once the piece before it has half the limit; with no heading in reach,
     * at a blank line outside code once it has the whole limit. The first piece keeps the section's number; the others are
     * its children, numbered with {@link #PIECE} and titled by their heading (or the section's title, continued), placed
     * right after the section, before any child it had. A piece's heading line becomes its title and leaves its text, as a
     * section's own heading does. Each piece is given the section's files, as the cut does not follow the page files.
     */
    public static Pieces splitLarge(List<BookOutline.Node> outline, List<Section> sections, int limit) {
        Map<String, Section> byNumber = new LinkedHashMap<>();
        for (Section x : sections) byNumber.put(x.number(), x);
        List<BookOutline.Node> nodes = new ArrayList<>();
        List<Section> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (BookOutline.Node n : outline) {
            Section x = byNumber.get(n.number());
            nodes.add(n);
            if (x == null) continue;
            if (x.text().length() <= limit) {
                out.add(x);
                continue;
            }
            List<String[]> pieces = cut(x.text(), limit);
            out.add(new Section(n.number(), pieces.get(0)[1], x.files()));
            String level = n.level().equals("chapter") ? "section" : "subsection";
            String firstFile = x.files().isEmpty() ? n.firstFile() : x.files().get(0);
            for (int k = 1; k < pieces.size(); k++) {
                String number = n.number() + ".p" + (k + 1);
                String title = pieces.get(k)[0] != null ? pieces.get(k)[0] : n.title() + " (continued)";
                nodes.add(new BookOutline.Node(number, level, title, n.number(), firstFile));
                out.add(new Section(number, pieces.get(k)[1], x.files()));
            }
            notes.add(n.number() + " (" + x.text().length() + " characters) cut into " + pieces.size() + " pieces of at most about " + limit);
        }
        return new Pieces(nodes, out, notes);
    }

    /** @return the pieces of a text, each its title (null for the first, and for one begun at a blank line) and its text */
    static List<String[]> cut(String text, int limit) {
        List<String[]> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String title = null;
        boolean code = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```")) code = !code;
            Matcher h = code ? null : HEADING.matcher(line);
            if (h != null && h.matches() && cur.length() >= limit / 2) {
                pieces.add(new String[] {title, cur.toString().strip()});
                cur.setLength(0);
                title = BookOutline.clean(h.group(1));
                continue;
            }
            if (!code && line.isBlank() && cur.length() >= limit) {
                pieces.add(new String[] {title, cur.toString().strip()});
                cur.setLength(0);
                title = null;
                continue;
            }
            cur.append(line).append('\n');
        }
        pieces.add(new String[] {title, cur.toString().strip()});
        return pieces;
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
