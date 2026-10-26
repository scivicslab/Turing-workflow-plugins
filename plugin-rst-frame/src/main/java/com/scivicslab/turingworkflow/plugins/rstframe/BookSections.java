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
 * Splits a book's markdown files into a sequence of sections based on a table of contents. It returns a list of
 * sections containing the text from each heading to the next, along with any text that belongs to no section.
 *
 * <ul>
 * <li>{@link #split(Map, List)} Splits the files using the provided outline, either by matching headings or by the
 * outline's own structure.</li>
 * <li>{@link #splitLarge(List, List, int)} Cuts sections that exceed a character limit into smaller pieces.</li>
 * <li>{@link #limit(List)} Calculates a threshold for cutting sections based on the median size of the existing
 * sections.</li>
 * </ul>
 *
 * <p>The class is stateless and may serve several workflows at once.</p>
 */
public final class BookSections {

    /**
     * The text of a single node in the book's table of contents, including the files it was read from.
     *
     * @param number the section number
     * @param text the markdown text of the section
     * @param files the list of file paths that contain the section's text, in the order they were read
     */
    public record Section(String number, String text, List<String> files) {
    }

    /** The sections in the book's order, and what was read but placed in no section. */
    public record Result(List<Section> sections, List<String> notes) {
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R.*?\\R---\\R", Pattern.DOTALL);

    private BookSections() {
    }

    /**
     * Returns true if the line is ordinary text, rather than a heading, code block, list, table, image, quote, or math
     * block.
     *
     * @param line the line to check
     */
    static boolean isText(String line) {
        String t = line.strip();
        return !(t.startsWith("#") || t.startsWith("```") || t.startsWith("|") || t.startsWith("!") || t.startsWith(">")
                || t.startsWith("- ") || t.startsWith("* ") || t.startsWith("$$") || t.matches("^\\d+\\.\\s.*"));
    }

    /**
     * Appends {@code line}, the first text of a page file, to the paragraph the previous page file ended in, if that
     * paragraph ends mid-sentence.
     *
     * Returns true if the lines were joined, and false otherwise.
     *
     * @param text the text of the current section being built
     * @param line the first text of the next page file
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
     * Splits a book's markdown files into sections based on a table of contents.
     *
     * @param files the book's markdown files, path under the book's directory to text, in the order they are read
     * @param outline the book's table of contents, as {@link BookOutline} read it
     * @return a {@link Result} containing the list of {@link Section}s and any notes about unplaced text
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

    /** Matches the suffix used to number pieces cut from a section that was too large, such as ".p2" or ".p3". */
    public static final Pattern PIECE = Pattern.compile("\\.p\\d+$");
    /**
     * The minimum character count for a section before it is eligible to be cut into pieces, regardless of the book's
     * median section size.
     */
    public static final int LIMIT_FLOOR = 50_000;

    /**
     * Returns the character limit for cutting a section into pieces.
     *
     * The limit is four times the median length of the sections in the list that contain text, but is never less than
     * {@link #LIMIT_FLOOR} characters.
     *
     * @param sections the list of sections to analyze
     */
    public static int limit(List<Section> sections) {
        int[] sizes = sections.stream().mapToInt(x -> x.text().length()).filter(n -> n > 0).sorted().toArray();
        if (sizes.length == 0) return LIMIT_FLOOR;
        long median = sizes[sizes.length / 2];
        return (int) Math.max(LIMIT_FLOOR, Math.min(Integer.MAX_VALUE, 4 * median));
    }

    /**
     * Breaks sections that exceed a character limit into smaller pieces to prevent any single section from holding too
     * much of the book.
     *
     * <p>Returns a {@link Pieces} object containing a revised list of nodes from the {@code outline}, the new list of
     * {@link Section} pieces, and notes about the cuts made.</p>
     *
     * <p>Each piece is created by finding a suitable break point: a heading within the section's text once the piece
     * has reached half the {@code limit}, or a blank line outside of code blocks once the piece has reached the full
     * {@code limit}. The first piece of a section retains the original section number, while subsequent pieces are
     * numbered using the {@link #PIECE} pattern (e.g., ".p2", ".p3"). Pieces are titled by their own heading line, or
     * by the section's title followed by " (continued)" if no heading is found. Each piece is assigned the same list
     * of source files as the original section.</p>
     *
     * @param outline the book's table of contents
     * @param sections the current list of sections to be processed
     * @param limit the maximum number of characters allowed in a single piece before a cut is attempted
     * @return a {@link Pieces} object containing the updated outline, the new sections, and a list of notes describing
     * the cuts
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

    /**
     * Returns a list of text pieces, where each piece is a two-element array containing its title (which is {@code
     * null} for the first piece, or for a piece that begins at a blank line) and its text.
     *
     * @param text the text to be cut
     * @param limit the character limit used to determine when to cut a piece
     */
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

    /**
     * Reduces a string to its alphanumeric characters and converts it to lowercase to facilitate matching against
     * headings.
     */
    private static String bare(String s) {
        return BookOutline.clean(s).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Splits the book's text into sections using the provided outline, where each section starts at the first
     * occurrence of its title in the markdown files.
     *
     * If the outline's headings cannot be found in the files, each node starts at the top of its first assigned file.
     *
     * A heading that repeats a title already used to start a section is treated as a continuation and does not start a
     * new section.
     *
     * @param files the book's markdown files, path under the book's directory to text, in the order they are read
     * @param outline the book's table of contents, as {@link BookOutline} read it
     * @return a result containing the list of sections and any notes about text that could not be assigned to a
     * section
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

    /**
     * Splits a book's markdown files into sections based on headings that match the provided outline.
     *
     * @param files the book's markdown files, where the key is the path under the book's directory and the value is
     * the file content
     *
     * @param outline the book's table of contents, as {@link BookOutline} read it
     * @return a {@link Result} containing the list of sections (each with its text and the files it was read from) and
     * any notes about unplaced text
     */
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
