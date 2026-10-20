package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one file of a book's OCR'd markdown into headings, paragraphs of sentences, code and lists,
 * and takes one sub-section out of it ({@code FramesAndSamplesFromBooks_261021_oo01}).
 *
 * <p>What it takes away is what the page put there and the book did not say: the front matter,
 * images, page numbers, the running heads repeated at the top of the pages, and the markdown marks
 * of emphasis and links. What it puts back together is the lines folded inside a paragraph. A
 * paragraph cut by a page is left as two; a frame is made from one sub-section, and a sub-section
 * that runs over a file's end is not read. It does not correct a word; an OCR misreading is left
 * for the writer to correct.</p>
 */
public final class BookText {

    private BookText() {}

    /** What a block of the book is. */
    public enum Kind { HEADING, PARAGRAPH, CODE, FORMULA, LIST }

    /**
     * One block of the book.
     *
     * @param kind      what it is
     * @param text      a heading's title, a paragraph's text, a code block's or formula's lines, a list's items joined by newlines
     * @param sentences a paragraph's sentences, a list's items, or empty
     */
    public record Block(Kind kind, String text, List<String> sentences) {}

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R.*?\\R---\\R", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("^!\\[[^\\]]*\\]\\([^)]*\\)\\s*$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\d{1,4}$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*]\\s+)?(?:\\d+[.)]\\s+|[-*]\\s+)(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ANGLE_URL = Pattern.compile("<(https?://[^>]*)>");
    /** A figure's or table's caption, standing alone: the figure is not in the text, so neither is its label. */
    private static final Pattern CAPTION = Pattern.compile("^(?:図|表|Fig\\.?|Figure|Table)\\s*\\d[\\d.]*\\s+.{1,40}$");
    /** A sub-heading the OCR left without #: "(c) 計算量", "2.4 2分探索木", standing alone and short. */
    private static final Pattern SUBHEADING = Pattern.compile("^(?:\\([a-z0-9]\\)|\\d+(?:\\.\\d+)*\\.?)\\s*\\S.{0,24}$");
    /** A line that starts with a label such as (a). */
    private static final Pattern LABEL = Pattern.compile("^\\([a-z0-9]\\)\\s+.*$");
    /** The longest line taken for a running head when it appears more than once. */
    private static final int RUNNING_HEAD_MAX = 30;

    /**
     * The blocks of one sub-section: those after the heading whose title is {@code heading}, up to
     * the next heading. A frame is made from one sub-section, so that is all that is read.
     *
     * @param file    one file of a book's markdown
     * @param heading the sub-section's title as the file writes it, without {@code #} or emphasis
     * @return the sub-section's blocks, in order, without its heading
     * @throws IllegalArgumentException when the file has no heading with that title
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
     * @param file one file of a book's markdown
     * @return the file's blocks, in order
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

    /** Drops images, page numbers, and short lines that come back on several pages (running heads). */
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

    /** Code lines that OCR left as separate blocks are one piece of code; labelled lines in a row are one list. */
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

    /** Joins folded lines: nothing where a Japanese character meets the break, one space elsewhere. */
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

    /** Removes the marks of emphasis and links, and the space OCR leaves before closing punctuation. */
    private static String inline(String s) {
        String t = LINK.matcher(s).replaceAll("$1");
        t = ANGLE_URL.matcher(t).replaceAll("$1");
        t = t.replace("**", "").replaceAll("(?<![\\w*])\\*(?=\\S)([^*]+?)(?<=\\S)\\*(?![\\w*])", "$1");
        t = t.replaceAll(" +([.,;:)])", "$1");
        return t.strip();
    }

    /**
     * Splits a paragraph into sentences: after {@code 。．！？}, and after {@code .!?} followed by a
     * space where the next sentence starts or the text before is Japanese.
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

    /** One line with no Japanese, ending as a statement or a signature does: {@code IO.println(...);}, {@code public static void main(String[] args)}. */
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
