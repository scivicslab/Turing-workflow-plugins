package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an rst-on-lisp rule file as a sequence of top-level forms and the text between them, allowing a user to
 * replace or add rules while keeping the rest of the file byte-for-byte identical.
 *
 * <ul>
 * <li>{@link #segments()} returns the pieces of the file in order.</li>
 * <li>{@link #rules()} returns the {@link Rule} objects for every {@code (defrule ...)} form in the file.</li>
 * <li>{@link #rule(String)} returns the {@link Rule} with the specified name, or {@code null} if it does not
 * exist.</li>
 * <li>{@link #replace(String, String)} returns a new {@code RuleFile} where the rule of the given name is replaced by
 * the new form.</li>
 * <li>{@link #insertAfter(String, java.util.List)} returns a new {@code RuleFile} where the specified forms are added
 * immediately after the rule of the given name.</li>
 * <li>{@link #text()} returns the entire file content as a single string.</li>
 * </ul>
 *
 * <p>One instance represents one file and may be used by several workflows.</p>
 */
public final class RuleFile {

    /**
     * Represents a single piece of a rule file, either a top-level Lisp form or the text between forms.
     *
     * @param text the text of the segment
     * @param form true if the segment is a Lisp form, false if it is text between forms (such as blank lines or
     * comments)
     */
    public record Segment(String text, boolean form) {}

    /**
     * Represents a single item within a rule's body, such as a symbol, a keyword, or a string.
     *
     * @param kind the type of the token, being {@link Kind#SYMBOL}, {@link Kind#KEYWORD}, or {@link Kind#STRING}
     * @param raw the text of the token as it appears in the file, including any quotes or escapes for strings
     */
    public record Token(Kind kind, String raw) {
        public enum Kind { SYMBOL, KEYWORD, STRING }

        /**
         * Returns the text of the string with Lisp escapes undone, or the symbol's text otherwise.
         *
         * @return the string's characters with the Lisp escapes undone; the symbol's text otherwise
         */
        public String value() {
            return kind == Kind.STRING ? unescape(raw.substring(1, raw.length() - 1)) : raw;
        }
    }

    /**
     * Represents a single {@code (defrule ...)} form from a rule file, providing access to its name and the sequence
     * of items that follow the {@code ->} arrow.
     *
     * <ul>
     * <li>{@code name()} returns the name of the rule.</li>
     * <li>{@code items()} returns the list of tokens following the arrow.</li>
     * </ul>
     *
     * <p>An instance is immutable and may be used by several workflows or threads at once.</p>
     *
     * <pre>{@code
     * Rule rule = ruleFile.rule("s1");
     * String kind = rule.attr(":kind");
     * String code = rule.symbolAfter(":code-block");
     * boolean isSentence = rule.isSentence();
     * }</pre>
     *
     * @param name the name of the rule
     * @param items the list of tokens following the arrow
     */
    public record Rule(String name, List<Token> items) {

        /**
         * Returns the string that follows the keyword {@code key}, or {@code null} if the rule has no such attribute.
         *
         * @param key the keyword to look for, such as {@code :instruction}
         */
        public String attr(String key) {
            for (int i = 0; i + 1 < items.size(); i++) {
                Token t = items.get(i);
                if (t.kind() == Token.Kind.KEYWORD && t.raw().equals(key)
                        && items.get(i + 1).kind() == Token.Kind.STRING) {
                    return items.get(i + 1).value();
                }
            }
            return null;
        }

        /**
         * Returns the text of the symbol that follows the given {@code key} in the rule's items, such as the symbol
         * {@code code-block} that follows {@code :kind}, or {@code null} if no such symbol exists.
         */
        public String symbolAfter(String key) {
            for (int i = 0; i + 1 < items.size(); i++) {
                Token t = items.get(i);
                if (t.kind() == Token.Kind.KEYWORD && t.raw().equals(key)
                        && items.get(i + 1).kind() == Token.Kind.SYMBOL) {
                    return items.get(i + 1).raw();
                }
            }
            return null;
        }

        /**
         * Returns true if the rule's body consists of exactly one string and no other items.
         *
         * @return true if the rule's body is a single string, false otherwise
         */
        public boolean isSentence() {
            return items.size() == 1 && items.get(0).kind() == Token.Kind.STRING;
        }
    }

    private final List<Segment> segments;

    private RuleFile(List<Segment> segments) {
        this.segments = segments;
    }

    /**
     * Parses a text into a {@link RuleFile} by splitting it into top-level Lisp forms and the text between them.
     *
     * @param text the whole rule file
     */
    public static RuleFile parse(String text) {
        List<Segment> out = new ArrayList<>();
        int i = 0;
        int gapStart = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ';') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '(') {
                if (i > gapStart) out.add(new Segment(text.substring(gapStart, i), false));
                int end = endOfForm(text, i);
                out.add(new Segment(text.substring(i, end), true));
                i = end;
                gapStart = end;
            } else {
                i++;
            }
        }
        if (gapStart < text.length()) out.add(new Segment(text.substring(gapStart), false));
        return new RuleFile(out);
    }

    /**
     * Returns the {@code (defrule NAME -> ...)} form of the rule named {@code name} exactly as it appears in the
     * provided {@code text}.
     *
     * @param text the full text of the rule file
     * @param name the name of the rule to find
     * @return the complete {@code (defrule ...)} string, or {@code null} if no rule with that name exists or if the
     * form is syntactically invalid
     */
    public static String formOf(String text, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(defrule\\s+" + java.util.regex.Pattern.quote(name) + "\\s").matcher(text);
        if (!m.find()) return null;
        try {
            return text.substring(m.start(), endOfForm(text, m.start()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the index just past the Lisp form that starts at start.
     *
     * @param start the offset where the form begins
     * @return the index of the character immediately following the closing parenthesis of the form
     * @throws IllegalArgumentException if the form starting at start has unbalanced parentheses
     */
    private static int endOfForm(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i++;
                while (i < text.length() && text.charAt(i) != '"') i += text.charAt(i) == '\\' ? 2 : 1;
            } else if (c == ';') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("unbalanced parentheses in the form that starts at offset " + start);
    }

    /** Returns the list of segments in the order they appear in the file. */
    public List<Segment> segments() {
        return segments;
    }

    /**
     * Returns the entire content of the rule file as a single string, preserving the original text of all segments
     * including any modifications made via {@code replace} or {@code insertAfter}.
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) sb.append(s.text());
        return sb.toString();
    }

    /** Returns a list of all {@code defrule} forms found in the file, in the order they appear. */
    public List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (Segment s : segments) {
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (r != null) rules.add(r);
        }
        return rules;
    }

    /**
     * Returns the {@code defrule} form with the specified name, or {@code null} if no such rule exists.
     *
     * @param name the name of the rule to find
     */
    public Rule rule(String name) {
        for (Rule r : rules()) if (r.name().equals(name)) return r;
        return null;
    }

    /**
     * Returns a new {@code RuleFile} in which the rule named {@code name} is replaced by the text in {@code newForm}.
     *
     * @param name the name of the rule to replace
     * @param newForm the new text for the rule, as a single Lisp form
     * @return a new {@code RuleFile} containing the replacement
     * @throws IllegalArgumentException if no rule with the given {@code name} exists
     */
    public RuleFile replace(String name, String newForm) {
        List<Segment> out = new ArrayList<>();
        boolean done = false;
        for (Segment s : segments) {
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (!done && r != null && r.name().equals(name)) {
                out.add(new Segment(newForm, true));
                done = true;
            } else {
                out.add(s);
            }
        }
        if (!done) throw new IllegalArgumentException("no rule named " + name);
        return new RuleFile(out);
    }

    /**
     * Returns a new {@code RuleFile} that contains the original forms plus the specified forms inserted immediately
     * after the rule named {@code name}.
     *
     * @param name the name of the rule after which the new forms are inserted
     * @param forms a list of strings, each representing a top-level form to be inserted
     * @return a new {@code RuleFile} instance containing the inserted forms
     * @throws IllegalArgumentException if no rule with the given {@code name} exists in the file
     */
    public RuleFile insertAfter(String name, List<String> forms) {
        List<Segment> out = new ArrayList<>();
        boolean done = false;
        for (Segment s : segments) {
            out.add(s);
            Rule r = s.form() ? ruleOf(s.text()) : null;
            if (!done && r != null && r.name().equals(name)) {
                for (String f : forms) {
                    out.add(new Segment("\n", false));
                    out.add(new Segment(f, true));
                }
                done = true;
            }
        }
        if (!done) throw new IllegalArgumentException("no rule named " + name);
        return new RuleFile(out);
    }

    /**
     * Returns a {@link Rule} if the text is a {@code (defrule NAME -> ...)} form, or {@code null} if the text is not a
     * rule.
     *
     * @param form the text of a single top-level form
     * @return a {@link Rule} containing the name and items if the form is a rule; {@code null} otherwise
     */
    static Rule ruleOf(String form) {
        List<Token> tokens = tokens(form.substring(1, form.length() - 1));
        if (tokens.size() < 3 || tokens.get(0).kind() != Token.Kind.SYMBOL
                || !tokens.get(0).raw().equalsIgnoreCase("defrule")
                || tokens.get(1).kind() != Token.Kind.SYMBOL
                || !tokens.get(2).raw().equals("->")) {
            return null;
        }
        return new Rule(tokens.get(1).raw(), List.copyOf(tokens.subList(3, tokens.size())));
    }

    /**
     * Returns a list of tokens that make up the content of a Lisp form, with comments removed.
     *
     * @param inside the text of the form after the opening parenthesis and before the closing parenthesis
     */
    static List<Token> tokens(String inside) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < inside.length()) {
            char c = inside.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == ';') {
                while (i < inside.length() && inside.charAt(i) != '\n') i++;
            } else if (c == '"') {
                int j = i + 1;
                while (j < inside.length() && inside.charAt(j) != '"') j += inside.charAt(j) == '\\' ? 2 : 1;
                out.add(new Token(Token.Kind.STRING, inside.substring(i, Math.min(j + 1, inside.length()))));
                i = j + 1;
            } else {
                int j = i;
                while (j < inside.length() && !Character.isWhitespace(inside.charAt(j))
                        && inside.charAt(j) != '"' && inside.charAt(j) != ';') j++;
                String word = inside.substring(i, j);
                out.add(new Token(word.startsWith(":") ? Token.Kind.KEYWORD : Token.Kind.SYMBOL, word));
                i = j;
            }
        }
        return out;
    }

    /**
     * Returns a string that is the input string with backslashes and double quotes escaped for use inside a Lisp
     * string.
     */
    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Returns a string that is the content of a Lisp string with its backslash escapes undone.
     *
     * @param s the string to unescape
     */
    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
