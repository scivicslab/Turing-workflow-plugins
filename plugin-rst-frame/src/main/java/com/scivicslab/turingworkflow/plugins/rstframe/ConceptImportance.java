package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which concepts from a section's list should be kept for a generalized frame, based solely on the section's
 * rst-on-lisp structure.
 *
 * <ul>
 * <li>{@link #judge(String, String, Map, List)} returns a list of judged concepts for a section, indicating whether
 * each is kept and why.</li>
 * <li>{@link #judgeListed(String, String, Map, List)} returns a list of judged concepts for a section, using specific
 * sentence numbers to identify the concept's presence.</li>
 * <li>{@link #units(String, Map)} returns the sentences, list items, and code blocks of a rule file with their
 * roles.</li>
 * <li>{@link #judgeAll(BookStore, String)} returns a summary string of how many sections were judged and the counts of
 * kept and dropped concepts.</li>
 * </ul>
 *
 * <p>A concept is kept if it appears in the section title, in a nucleus, in a definition, or in a list item. It is
 * dropped if it appears only in satellites that add around the point (such as an example, background, or analogy) or
 * only in code. A concept not found in the text is kept and marked as being named by the writer.</p>
 */
public final class ConceptImportance {

    /**
     * The set of relation names for satellites that add information around the paragraph's point rather than stating
     * it.
     */
    static final Set<String> AROUND = Set.of("example", "background", "comment", "evaluation-s", "evaluation-n", "Evaluation",
            "interpretation-s", "interpretation-n", "Interpretation", "analogy", "Analogy", "concession", "antithesis",
            "attribution", "attribution-n", "dispatch", "circumstance", "otherwise", "Otherwise", "topic-shift", "Topic-Shift",
            "topic-drift", "Topic-Drift");

    private ConceptImportance() {
    }

    /**
     * Represents a single sentence, list item, or code block from a rule file, its role in the section's structure,
     * and the name of the rule that contains it.
     *
     * @param role the role of the unit (e.g., nucleus, definition, list item, or code)
     * @param text the actual text of the sentence, item, or code block
     * @param rule the name of the rule that holds this unit, or {@code null} if not applicable
     */
    public record Unit(String role, String text, String rule) {
        public Unit(String role, String text) {
            this(role, text, null);
        }
    }

    /**
     * Decides which concepts from a list to keep based on the section's title, rule file, and code blocks, returning a
     * list of each concept paired with its kept status and the reason.
     *
     * @param title the section's title
     * @param rules the section's rule file
     * @param code a map of file names to their text content
     * @param concepts the list of concept names to judge
     * @return a list of {@link BookStore.JudgedConcept} objects, each containing the concept name, whether it was
     * kept, and the reason, in the same order as the input concepts
     */
    public static List<BookStore.JudgedConcept> judge(String title, String rules, Map<String, String> code, List<String> concepts) {
        List<BookStore.ListedConcept> listed = new ArrayList<>();
        for (String c : concepts) listed.add(new BookStore.ListedConcept(c, null));
        return judgeListed(title, rules, code, listed);
    }

    /**
     * Decides which of the listed concepts to keep based on the section's rst-on-lisp structure, returning a list of
     * results that specify whether each concept is kept and the reason why.
     *
     * @param title the section's title
     * @param rules the section's rule file
     * @param code the files its code blocks name, by name
     * @param concepts each concept with the numbers of the sentences step 4 said explain it (1 for the first of {@link
     * #units}), or {@code null} to look for the concept's words instead
     *
     * @return a list of judged concepts in the order given, where each result contains a boolean indicating if it is
     * kept and a message explaining the decision
     */
    public static List<BookStore.JudgedConcept> judgeListed(String title, String rules, Map<String, String> code,
            List<BookStore.ListedConcept> concepts) {
        List<Unit> units = units(rules, code);
        List<BookStore.JudgedConcept> out = new ArrayList<>();
        for (BookStore.ListedConcept lc : concepts) {
            String c = lc.concept();
            Pattern p = pattern(c);
            if (p.matcher(title).find()) {
                out.add(new BookStore.JudgedConcept(c, true, "in the title"));
                continue;
            }
            Set<String> roles = new LinkedHashSet<>();
            boolean pointed = lc.sentences() != null && !lc.sentences().isEmpty();
            if (pointed) {
                for (int n : lc.sentences()) if (n >= 1 && n <= units.size()) roles.add(units.get(n - 1).role());
            } else {
                for (Unit u : units) if (p.matcher(u.text()).find()) roles.add(u.role());
            }
            String at = pointed ? " (sentences " + String.join(", ", lc.sentences().stream().map(String::valueOf).toList()) + ")" : "";
            if (roles.isEmpty()) {
                out.add(new BookStore.JudgedConcept(c, true, pointed ? "its sentences are not in the rule file: kept" : "not found in the text: kept as the writer named it"));
            } else if (roles.contains("nucleus")) {
                out.add(new BookStore.JudgedConcept(c, true, "in a nucleus" + at));
            } else if (roles.contains("definition")) {
                out.add(new BookStore.JudgedConcept(c, true, "in a definition" + at));
            } else if (roles.contains("list item")) {
                out.add(new BookStore.JudgedConcept(c, true, "in a list item" + at));
            } else if (roles.stream().allMatch(r -> AROUND.contains(r) || r.equals("code"))) {
                out.add(new BookStore.JudgedConcept(c, false, "only in " + String.join(", ", roles) + at));
            } else {
                Set<String> said = new LinkedHashSet<>(roles);
                said.removeAll(AROUND);
                said.remove("code");
                out.add(new BookStore.JudgedConcept(c, true, "in " + String.join(", ", said) + at));
            }
        }
        return out;
    }

    /**
     * Returns a list of the sentences, list items, and code blocks found in a section's rule file, each paired with
     * its role and the name of the rule that contains it.
     *
     * @param rules the text of the section's rule file
     * @param code a map of file names to their text content
     * @return a list of {@link Unit} objects in the order they appear in the section
     */
    public static List<Unit> units(String rules, Map<String, String> code) {
        RuleFile f = RuleFile.parse(rules);
        Map<String, String> roleOf = new LinkedHashMap<>();
        List<Unit> out = new ArrayList<>();
        for (RuleFile.Rule r : f.rules()) {
            if (r.attr(":file") != null) continue;
            List<RuleFile.Token> items = r.items();
            boolean list = r.attr(":kind") != null || r.symbolAfter(":kind") != null;
            for (int i = 0; i < items.size(); i++) {
                RuleFile.Token t = items.get(i);
                if (t.kind() == RuleFile.Token.Kind.KEYWORD && i + 1 < items.size() && items.get(i + 1).kind() == RuleFile.Token.Kind.SYMBOL
                        && !t.raw().equals(":kind")) {
                    roleOf.put(items.get(i + 1).raw(), t.raw().substring(1));
                    i++;
                } else if (list && t.kind() == RuleFile.Token.Kind.SYMBOL) {
                    roleOf.put(t.raw(), "list item");
                }
            }
        }
        // in the file's order, so the numbers a question shows run down the section
        for (RuleFile.Rule r : f.rules()) {
            if (r.attr(":file") != null) {
                String text = code.get(r.attr(":file"));
                if (text != null) out.add(new Unit("code", text, r.name()));
                continue;
            }
            String role = roleOf.get(r.name());
            if (role == null) continue;
            for (RuleFile.Token t : r.items()) if (t.kind() == RuleFile.Token.Kind.STRING) out.add(new Unit(role, t.value(), r.name()));
        }
        return out;
    }

    /**
     * Returns a pattern that matches the concept's words as a whole, case-insensitive, including its singular or
     * plural forms.
     *
     * The pattern matches the concept name or its variations where the last word is singular or plural.
     */
    static Pattern pattern(String concept) {
        String c = concept.strip().replaceAll("\\s+", " ");
        Set<String> forms = new LinkedHashSet<>(List.of(c, c + "s", c + "es"));
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.endsWith("es") && c.length() > 3) forms.add(c.substring(0, c.length() - 2));
        if (lower.endsWith("s") && !lower.endsWith("ss") && c.length() > 2) forms.add(c.substring(0, c.length() - 1));
        StringBuilder alt = new StringBuilder();
        for (String f : forms) alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(f));
        return Pattern.compile("(?i)(?<![\\w])(?:" + alt + ")(?![\\w])");
    }

    /**
     * Returns a summary string stating how many sections of a book were judged and how many concepts were kept or
     * dropped.
     *
     * <p>The method processes every section in the specified book that contains both a rule file and a list of
     * concepts. For each such section, it determines which concepts are essential based on their presence in the
     * section's title, nucleus, definition, or list items, and whether they appear only in peripheral satellites or
     * code. The results are saved back to the provided {@code BookStore}.</p>
     *
     * @param store the data store containing the book's outline, rules, and concepts
     * @param book the name of the book to process
     * @return a string in the format "X section(s) of [book] judged: Y concept(s) kept, Z dropped"
     * @throws Exception if an error occurs during database access or processing
     */
    public static String judgeAll(BookStore store, String book) throws Exception {
        Map<String, String> titles = new LinkedHashMap<>();
        for (BookOutline.Node n : store.outline(book)) titles.put(n.number(), n.title());
        Set<String> withRules = store.sectionsWithRules(book);
        int sections = 0, kept = 0, dropped = 0;
        for (Map.Entry<String, List<BookStore.ListedConcept>> e : store.listedConcepts(book).entrySet()) {
            if (!withRules.contains(e.getKey()) || e.getValue().isEmpty()) continue;
            BookStore.SectionRules r = store.sectionRules(book, e.getKey());
            List<BookStore.JudgedConcept> j = judgeListed(titles.getOrDefault(e.getKey(), ""), r.rules(), r.code(), e.getValue());
            store.saveJudgement(book, e.getKey(), j);
            sections++;
            for (BookStore.JudgedConcept x : j) if (Boolean.TRUE.equals(x.kept())) kept++; else dropped++;
        }
        return sections + " section(s) of " + book + " judged: " + kept + " concept(s) kept, " + dropped + " dropped";
    }
}
