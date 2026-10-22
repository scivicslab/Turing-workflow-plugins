package com.scivicslab.turingworkflow.plugins.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Work on texts a workflow keeps in its state: each method is given the texts and answers the result, which the
 * workflow keeps where it likes. The one thing held between calls is the verdicts {@link #sameAsLast} remembers.
 */
public final class Texts {

    /** How many of one kind {@link #compare} lists before the rest are summed up. */
    private static final int COMPLAINTS_SHOWN = 8;

    private final Map<String, String> lastVerdicts = new HashMap<>();

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
     * Whether this verdict says the same as the one remembered under {@code memo} ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}):
     * when a judge answers with the same words again, the two roles read the rule differently, and another round will
     * produce the same pair of answers. Remembers this verdict either way. Blank space is ignored.
     */
    public boolean sameAsLast(String verdict, String memo) {
        if (verdict == null) throw new IllegalArgumentException("no verdict was given");
        if (memo == null || memo.isBlank()) throw new IllegalArgumentException("memo is required");
        String last = lastVerdicts.put(memo, verdict);
        return last != null && flatten(last).equals(flatten(verdict));
    }

    /**
     * What a rewrite changed that it had no business changing ({@code WhatAProgramCanDo_260915_oo01}): code blocks,
     * identifiers, names and numbers that went missing or appeared.
     *
     * @return {@code ACCEPT} when nothing of the kind changed, and otherwise {@code REVISE:} followed by what changed,
     *         the shape a judge's verdict has
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
     * Takes a document's front matter off, so that what is worked on is the text alone: nothing in a rewrite concerns
     * those lines, and a run that sent them to a model got a document back without them.
     *
     * @return {@code {head, body}}: the front matter ending with its closing line's newline ({@code ""} when there is
     *         none), and the rest
     */
    public String takeFrontMatter(String text) {
        if (text == null) throw new IllegalArgumentException("no text was given");
        int end = frontMatterEnd(text);
        return new JSONObject().put("head", end < 0 ? "" : text.substring(0, end)).put("body", end < 0 ? text : text.substring(end)).toString();
    }

    /** Puts back what {@link #takeFrontMatter} took off, with one blank line between the two. */
    public String joinFrontMatter(String head, String body) {
        if (body == null) throw new IllegalArgumentException("no text was given");
        if (head == null || head.isEmpty()) return body;
        // what comes back from a model does not always keep the blank line it was sent with
        return head + (body.startsWith("\n") ? "" : "\n") + body;
    }

    /**
     * Where a document's front matter ends, or {@code -1} when it has none. Only a block at the very top counts: a
     * line of dashes further down is a horizontal rule.
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

    /** @return what is in {@code from} and not in {@code in}, each value once, in order */
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

    /** The fenced blocks of a markdown text, as their contents. */
    static List<String> codeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```", Pattern.MULTILINE).matcher(text);
        while (m.find()) blocks.add(m.group(1).strip());
        return blocks;
    }

    /** The backquoted spans of a markdown text: class names, paths, options, actor names. */
    static List<String> inlineCode(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> spans = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`\\n]+)`").matcher(withoutBlocks);
        while (m.find()) spans.add(m.group(1).strip());
        return spans;
    }

    /**
     * The names in a text's prose ({@code OpenAlex}, {@code ChatSession}): capitals inside a word mark one, so
     * rewriting a sentence of ordinary words is not a complaint.
     */
    static List<String> names(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Za-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+)\\b").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /**
     * The numbers of a text, read outside the fenced blocks and with the digits of a version or a document id left
     * out: {@code 260913_oo01} is part of a name.
     */
    static List<String> numbers(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![0-9A-Za-z_])([0-9][0-9,]*)(?![0-9A-Za-z_])").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1).replace(",", ""));
        return found;
    }
}
