package com.scivicslab.turingworkflow.plugins.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Work on texts a workflow keeps in its state: each method is given the texts and answers the result, which the
 * workflow keeps where it likes. Nothing is held between calls, so one instance serves every workflow at once.
 */
public final class Texts {

    /**
     * The maximum number of specific items of a certain kind (such as code blocks, identifiers, names, or numbers)
     * that are listed in a {@link #compare} result before the remaining items are summarized as a count.
     */
    private static final int COMPLAINTS_SHOWN = 8;

    /**
     * Writes a list as one line each, for a person to read. An empty or absent list is written as なし, so a heading
     * never stands over nothing.
     *
     * @param list the list as JSON ({@code ["a","b"]}); a JSON text ({@code "a"}) is answered as that text, any other
     *             text as it is
     */
    public String joinLines(String list) {
        if (list == null || list.isBlank()) return "なし";
        String t = list.strip();
        // a value read out of the state as JSON: a quoted text is that text
        if (t.startsWith("\"")) return new JSONArray("[" + t + "]").getString(0);
        if (!t.startsWith("[")) return list;
        JSONArray a = new JSONArray(t);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object one = a.get(i);
            lines.add(one instanceof String s ? s : one.toString());
        }
        return lines.isEmpty() ? "なし" : String.join("\n", lines);
    }

    /**
     * Returns whether the current verdict is equivalent to the previous one, ignoring differences in whitespace.
     *
     * A verdict of {@code null} results in an error.
     *
     * A {@code last} value that is {@code null} or empty results in {@code false}.
     *
     * @param verdict the current verdict to check; must not be {@code null}
     * @param last the previous verdict; if {@code null} or empty, the result is {@code false}
     * @return {@code true} if the two strings are identical after stripping extra whitespace, {@code false} otherwise
     * @throws IllegalArgumentException if {@code verdict} is {@code null}
     */
    public boolean sameAsLast(String verdict, String last) {
        if (verdict == null) throw new IllegalArgumentException("no verdict was given");
        return last != null && !last.isEmpty() && flatten(last).equals(flatten(verdict));
    }

    /**
     * Compares a text before and after a rewrite to identify unauthorized changes to code blocks, identifiers, names,
     * or numbers.
     *
     * @param before the text before the rewrite
     * @param after the text after the rewrite
     * @return {@code ACCEPT} if no such changes were found, otherwise a string starting with {@code REVISE:} followed
     * by a list of the specific changes found
     *
     * @throws IllegalArgumentException if either before or after is null
     */
    public String compare(String before, String after) {
        if (before == null || after == null) throw new IllegalArgumentException("both texts are required");
        List<String> complaints = new ArrayList<>();
        complain(complaints, "消えたコードブロック", missing(codeBlocks(before), codeBlocks(after)));
        complain(complaints, "元の本文に無いコードブロック", missing(codeBlocks(after), codeBlocks(before)));
        complain(complaints, "消えた識別子", missing(inlineCode(before), inlineCode(after)));
        complain(complaints, "消えた名前", missing(names(before), names(after)));
        complain(complaints, "消えた数値", missing(numbers(before), numbers(after)));
        complain(complaints, "元の本文に無い数値", missing(numbers(after), numbers(before)));
        return complaints.isEmpty() ? "ACCEPT" : "REVISE: 元の本文にあったものが変わっています。\n" + String.join("\n", complaints);
    }

    /**
     * Returns a JSON string containing the document's front matter and the remaining body.
     *
     * @param text the document text to process
     * @return a JSON string with two fields: {@code "head"} contains the front matter including its closing newline
     * (or an empty string if none exists), and {@code "body"} contains the rest of the text
     *
     * @throws IllegalArgumentException if text is null
     */
    public String takeFrontMatter(String text) {
        if (text == null) throw new IllegalArgumentException("no text was given");
        int end = frontMatterEnd(text);
        return new JSONObject().put("head", end < 0 ? "" : text.substring(0, end)).put("body", end < 0 ? text : text.substring(end)).toString();
    }

    /**
     * Combines a document's front matter and its body into a single text, with one blank line between them.
     *
     * @param head the front matter text; if null or empty, only the body is returned
     * @param body the body of the document; must not be null
     * @return the concatenated text starting with the head and ending with the body
     */
    public String joinFrontMatter(String head, String body) {
        if (body == null) throw new IllegalArgumentException("no text was given");
        if (head == null || head.isEmpty()) return body;
        // what comes back from a model does not always keep the blank line it was sent with
        return head + (body.startsWith("\n") ? "" : "\n") + body;
    }

    /**
     * Returns the index immediately following the closing line of a document's front matter, or {@code -1} if no such
     * matter exists. Only a block starting at the very beginning of the text counts; a line of dashes appearing later
     * in the text is treated as a horizontal rule and does not mark the end of front matter.
     *
     * @param text the text to search
     */
    static int frontMatterEnd(String text) {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return -1;
        int from = text.indexOf('\n') + 1;
        while (from < text.length()) {
            int lineEnd = text.indexOf('\n', from);
            String line = (lineEnd < 0 ? text.substring(from) : text.substring(from, lineEnd)).strip();
            if (line.equals("---")) return lineEnd < 0 ? text.length() : lineEnd + 1;
            if (lineEnd < 0) return -1;
            from = lineEnd + 1;
        }
        return -1;
    }

    private static String flatten(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static void complain(List<String> complaints, String what, List<String> found) {
        if (found.isEmpty()) return;
        List<String> shown = found.size() > COMPLAINTS_SHOWN ? found.subList(0, COMPLAINTS_SHOWN) : found;
        StringBuilder sb = new StringBuilder("- ").append(what).append(": ");
        sb.append(String.join(" / ", shown.stream().map(Texts::oneLine).toList()));
        if (found.size() > shown.size()) sb.append(" ほか").append(found.size() - shown.size()).append("件");
        complaints.add(sb.toString());
    }

    /**
     * Returns a list of elements that are present in {@code from} but not in {@code in}, with each missing element
     * appearing at most once and in the order they first appeared in {@code from}.
     */
    private static List<String> missing(List<String> from, List<String> in) {
        List<String> rest = new ArrayList<>(in);
        List<String> gone = new ArrayList<>();
        for (String one : from) {
            if (!rest.remove(one) && !gone.contains(one)) gone.add(one);
        }
        return gone;
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
    }

    /** Returns a list of the contents of the fenced blocks in a markdown text. */
    static List<String> codeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```", Pattern.MULTILINE).matcher(text);
        while (m.find()) blocks.add(m.group(1).strip());
        return blocks;
    }

    /**
     * Returns a list of the contents of backquoted spans in a markdown text, such as class names, paths, options, or
     * actor names.
     */
    static List<String> inlineCode(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> spans = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`\\n]+)`").matcher(withoutBlocks);
        while (m.find()) spans.add(m.group(1).strip());
        return spans;
    }

    /**
     * Returns a list of names found in the prose of a text.
     *
     * <p>Names are identified as words containing at least one capital letter inside them, such as {@code OpenAlex} or
     * {@code ChatSession}, while ordinary words are ignored. Fenced code blocks are excluded from the search.
     *
     * @param text the text to search in
     */
    static List<String> names(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Za-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+)\\b").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /**
     * Returns a list of numbers found in the text, excluding those within fenced code blocks and those that are part
     * of an alphanumeric identifier like a version or document ID.
     *
     * @param text the text to search through
     * @return a list of the found numbers as strings, with commas removed
     */
    static List<String> numbers(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![0-9A-Za-z_])([0-9][0-9,]*)(?![0-9A-Za-z_])").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1).replace(",", ""));
        return found;
    }
}
