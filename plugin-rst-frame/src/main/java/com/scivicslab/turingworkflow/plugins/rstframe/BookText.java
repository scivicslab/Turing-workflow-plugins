package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a book's OCR'd markdown file into a sequence of structured blocks, such as headings, paragraphs, code, and
 * lists.
 *
 * <ul>
 * <li>{@code read(file)} returns the file's blocks in order, stripping front matter, images, page numbers, and running
 * heads.</li>
 * <li>{@code subsection(file, heading)} returns the blocks belonging to the sub-section starting with the specified
 * {@code heading} and ending before the next heading.</li>
 * <li>{@code sentences(text)} returns a list of sentences extracted from the given text.</li>
 * </ul>
 *
 * <p>A paragraph split by a page break is returned as two separate blocks. A sub-section that extends beyond the end
 * of a file is not read. OCR misreadings and errors are not corrected.</p>
 */
public final class BookText {

    private BookText() {}

    /** Categorizes a block of the book into its structural type. */
    public enum Kind { HEADING, PARAGRAPH, CODE, FORMULA, LIST }

    /**
     * Represents a single unit of content from a book, such as a heading, paragraph, code block, formula, or list.
     *
     * @param kind the type of the block
     * @param text the content of the block: a heading's title, a paragraph's text, a code block's or formula's lines,
     * or a list's items joined by newlines
     *
     * @param sentences a paragraph's sentences, a list's items, or an empty list
     */
    public record Block(Kind kind, String text, List<String> sentences) {}

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R.*?\\R---\\R", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("^!\\[[^\\]]*\\]\\([^)]*\\)\\s*$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\d{1,4}$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*]\\s+)?(?:\\d+[.)]\\s+|[-*]\\s+)(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ANGLE_URL = Pattern.compile("<(https?://[^>]*)>");
    /**
     * Matches a standalone caption for a figure or table, such as "Fig. 1" or "Table 2", followed by up to 40
     * characters of text.
     */
    private static final Pattern CAPTION = Pattern.compile("^(?:図|表|Fig\\.?|Figure|Table)\\s*\\d[\\d.]*\\s+.{1,40}$");
    /**
     * Matches short, standalone lines that the OCR left as sub-headings without a {@code #} prefix, such as "(c)
     * Complexity" or "2.4 Binary Search Tree".
     */
    private static final Pattern SUBHEADING = Pattern.compile("^(?:\\([a-z0-9]\\)|\\d+(?:\\.\\d+)*\\.?)\\s*\\S.{0,24}$");
    /** Matches a line that starts with a label such as (a). */
    private static final Pattern LABEL = Pattern.compile("^\\([a-z0-9]\\)\\s+.*$");
    /** The maximum number of characters used to identify a line as a running head. */
    private static final int RUNNING_HEAD_MAX = 30;

    /**
     * Returns the blocks of a single sub-section from a book's markdown file.
     *
     * The sub-section begins after the block whose title matches {@code heading} and continues until the next heading
     * is encountered.
     *
     * The result is a list of {@link Block} objects, in order, excluding the heading itself.
     *
     * Throws an {@link IllegalArgumentException} if the file does not contain a heading with the specified title.
     */
    public static List<Block> subsection(String file, String heading) {
        List<Block> all = read(file);
        String wanted = heading.strip();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).kind() == Kind.HEADING && all.get(i).text().equals(wanted)) {
                int end = i + 1;
                while (end < all.size() && all.get(end).kind() != Kind.HEADING) end++;
                return List.copyOf(all.subList(i + 1, end));
            }
        }
        throw new IllegalArgumentException("no heading \"" + wanted + "\" in the file");
    }

    /**
     * Returns a list of the blocks that make up a book's markdown file, in order.
     *
     * <p>The list contains {@link Block} objects representing headings, paragraphs, code, formulas, and lists. The
     * process removes front matter, images, page numbers, and running heads, and strips marks of emphasis and links
     * from the text. Paragraphs are reconstructed from lines, and adjacent code or list blocks are merged.
     *
     * @param file the path to the book's markdown file
     * @return a list of the file's blocks in order
     */
    public static List<Block> read(String file) {
        List<String> lines = new ArrayList<>();
        String body = FRONT_MATTER.matcher(file).replaceFirst("");
        for (String line : body.split("\\R", -1)) lines.add(line);
        lines = withoutPageFurniture(lines);

        List<Block> blocks = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.strip();
            if (t.startsWith("```")) {
                flush(pending, blocks);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.size() && !lines.get(i).strip().startsWith("```"); i++) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines.get(i));
                }
                blocks.add(new Block(Kind.CODE, code.toString(), List.of()));
                continue;
            }
            if (t.startsWith("$$")) {
                flush(pending, blocks);
                StringBuilder formula = new StringBuilder(t);
                if (!(t.length() > 2 && t.endsWith("$$"))) {
                    for (i++; i < lines.size(); i++) {
                        formula.append('\n').append(lines.get(i));
                        if (lines.get(i).strip().endsWith("$$")) break;
                    }
                }
                blocks.add(new Block(Kind.FORMULA, formula.toString(), List.of()));
                continue;
            }
            Matcher heading = HEADING.matcher(t);
            if (heading.matches()) {
                flush(pending, blocks);
                blocks.add(new Block(Kind.HEADING, inline(heading.group(1)), List.of()));
                continue;
            }
            if (t.isEmpty()) {
                flush(pending, blocks);
                continue;
            }
            pending.add(t);
        }
        flush(pending, blocks);
        return mergeAdjacent(blocks);
    }

    /** Removes images, page numbers, and repeating running heads from a list of lines. */
    private static List<String> withoutPageFurniture(List<String> lines) {
        Map<String, Integer> seen = new HashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (isHeadCandidate(t)) seen.merge(t, 1, Integer::sum);
        }
        List<String> kept = new ArrayList<>();
        boolean inCode = false;
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("```")) inCode = !inCode;
            if (!inCode && (IMAGE.matcher(t).matches() || PAGE_NUMBER.matcher(t).matches()
                    || (isHeadCandidate(t) && seen.get(t) > 1))) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    private static boolean isHeadCandidate(String t) {
        return !t.isEmpty() && t.length() <= RUNNING_HEAD_MAX && !t.startsWith("#") && !t.startsWith("```")
                && !endsASentence(t) && !PAGE_NUMBER.matcher(t).matches();
    }

    private static void flush(List<String> pending, List<Block> blocks) {
        if (pending.isEmpty()) return;
        if (pending.size() == 1) {
            String line = pending.get(0);
            if (CAPTION.matcher(line).matches()) {
                pending.clear();
                return;
            }
            if (LABEL.matcher(line).matches() && !line.matches(".*(\\p{IsHan}|\\p{IsHiragana}|\\p{IsKatakana}|[A-Za-z]{3,}).*")) {
                blocks.add(new Block(Kind.LIST, line, List.of(line)));   // (a) 1-2-3: an item, not a heading
                pending.clear();
                return;
            }
            if (SUBHEADING.matcher(line).matches() && !endsASentence(line)) {
                blocks.add(new Block(Kind.HEADING, inline(line), List.of()));
                pending.clear();
                return;
            }
            if (looksLikeCode(line)) {
                blocks.add(new Block(Kind.CODE, line, List.of()));
                pending.clear();
                return;
            }
        }
        boolean list = pending.stream().allMatch(l -> LIST_ITEM.matcher(l).matches() && l.matches("^(?:[-*]|\\d+[.)]).*"));
        if (list) {
            List<String> items = new ArrayList<>();
            for (String l : pending) {
                Matcher m = LIST_ITEM.matcher(l);
                m.matches();
                items.add(inline(m.group(1)));
            }
            blocks.add(new Block(Kind.LIST, String.join("\n", items), items));
        } else {
            String text = inline(joinLines(pending));
            blocks.add(new Block(Kind.PARAGRAPH, text, sentences(text)));
        }
        pending.clear();
    }

    /**
     * Combines adjacent blocks of the same kind into a single block.
     *
     * <p>Consecutive {@link Block} objects that are both {@link Kind#CODE} or both {@link Kind#LIST} are merged into
     * one, with their text and sentences joined together.</p>
     */
    private static List<Block> mergeAdjacent(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (!out.isEmpty() && (b.kind() == Kind.CODE || b.kind() == Kind.LIST)
                    && out.get(out.size() - 1).kind() == b.kind()) {
                Block prev = out.get(out.size() - 1);
                List<String> items = new ArrayList<>(prev.sentences());
                items.addAll(b.sentences());
                out.set(out.size() - 1, new Block(b.kind(), prev.text() + "\n" + b.text(), List.copyOf(items)));
                continue;
            }
            out.add(b);
        }
        return out;
    }

    private static boolean endsASentence(String t) {
        String u = t.replaceAll("[\"'”’」』)）]+$", "");
        if (u.isEmpty()) return false;
        char last = u.charAt(u.length() - 1);
        return ".!?。．！？:：".indexOf(last) >= 0;
    }

    /**
     * Concatenates a list of lines into a single string, inserting a space between lines only if neither the end of
     * the first line nor the start of the second line contains a Japanese character.
     */
    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            if (sb.length() > 0 && !isCjk(sb.charAt(sb.length() - 1)) && !isCjk(l.charAt(0))) sb.append(' ');
            sb.append(l);
        }
        return sb.toString();
    }

    /**
     * Cleans a string by removing markdown emphasis, links, and angle-bracketed URLs, and strips the space that OCR
     * often leaves before closing punctuation.
     *
     * @param s the text to be cleaned
     */
    private static String inline(String s) {
        String t = LINK.matcher(s).replaceAll("$1");
        t = ANGLE_URL.matcher(t).replaceAll("$1");
        t = t.replace("**", "").replaceAll("(?<![\\w*])\\*(?=\\S)([^*]+?)(?<=\\S)\\*(?![\\w*])", "$1");
        t = t.replaceAll(" +([.,;:)])", "$1");
        return t.strip();
    }

    /**
     * Returns a list of strings that are the sentences of the given text.
     *
     * The sentences are delimited by {@code 。．！？}, or by {@code .!?} followed by a space when the following character
     * is an uppercase letter, a quote, or an opening parenthesis. A {@code .} is also treated as a delimiter if it is
     * preceded by a Japanese character, or if it is followed by a Japanese character and not preceded by a digit.
     *
     * @param text the text to be split into sentences
     * @return a list of the sentences found in the text, in order
     */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end;
            char before = i > 0 ? text.charAt(i - 1) : ' ';
            char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
            if ("。．！？".indexOf(c) >= 0) {
                end = true;
            } else if (c == '.' && isCjk(before)) {
                end = true;                                     // ある.このような
            } else if (c == '.' && isCjk(next) && !Character.isDigit(before)) {
                end = true;
            } else if ((".!?".indexOf(c) >= 0 || (c == '"' && i > 0 && ".!?".indexOf(before) >= 0))
                    && next == ' ' && i + 2 < text.length()) {
                char after = text.charAt(i + 2);
                end = Character.isUpperCase(after) || after == '"' || after == '(';   // English: the next sentence starts
            } else {
                end = false;
            }
            if (end) {
                String s = text.substring(start, i + 1).strip();
                if (!s.isEmpty()) out.add(s);
                start = i + 1;
            }
        }
        String rest = text.substring(start).strip();
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /**
     * Determines if a single line of text is a code fragment by checking for the absence of Japanese characters and
     * the presence of specific syntactic markers.
     *
     * Returns {@code true} if the line contains no CJK characters and ends with a semicolon, an opening or closing
     * brace, or matches a method signature pattern; otherwise returns {@code false}.
     *
     * @param line the line of text to test
     */
    private static boolean looksLikeCode(String line) {
        String t = line.replace("$", "").strip();
        if (t.chars().anyMatch(ch -> isCjk((char) ch))) return false;
        return t.endsWith(";") || t.endsWith("{") || t.endsWith("}")
                || t.matches("^[\\w.<>\\[\\], ]+\\([^()]*\\)$");
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA;
    }
}
