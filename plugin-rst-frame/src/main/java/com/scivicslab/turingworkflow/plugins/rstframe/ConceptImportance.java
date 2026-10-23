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
 * Which of a section's listed concepts to keep ({@code FrameOntology_261021_oo01}, step 5), judged by the section's
 * rst-on-lisp structure alone, not by how often or where else a concept appears. No LLM is used.
 *
 * <p>Every sentence of the section's rule file has a role: the nucleus of its paragraph, or a satellite with a relation
 * to that nucleus; an item of a list; text of a code block. A concept's sentences are the ones step 4 said explain it,
 * by their numbers ({@link #units}); for a concept listed without them, the sentences that name it (the term, or the
 * term with an s or es added or taken off). It is kept when it is in the section's title, in a nucleus, in a definition, or in
 * a list item: what the section says rather than what it adds. It is dropped when it is only in satellites that add
 * around the point (an example, background, a comment, an evaluation, an interpretation, an analogy, a concession, a
 * reference elsewhere) or only in code. A concept found nowhere in the text (the writer named it in other words) is kept
 * and marked so.</p>
 */
public final class ConceptImportance {

    /** The relations of a satellite that adds around the paragraph's point rather than saying it. */
    static final Set<String> AROUND = Set.of("example", "background", "comment", "evaluation-s", "evaluation-n", "Evaluation",
            "interpretation-s", "interpretation-n", "Interpretation", "analogy", "Analogy", "concession", "antithesis",
            "attribution", "attribution-n", "dispatch", "circumstance", "otherwise", "Otherwise", "topic-shift", "Topic-Shift",
            "topic-drift", "Topic-Drift");

    private ConceptImportance() {
    }

    /** One sentence (or list item, or code block) of a rule file and its role. */
    public record Unit(String role, String text) {
    }

    /**
     * @param title the section's title
     * @param rules the section's rule file
     * @param code  the files its code blocks name, by name
     * @return each concept with whether it is kept and why, in the order given
     */
    public static List<BookStore.JudgedConcept> judge(String title, String rules, Map<String, String> code, List<String> concepts) {
        List<BookStore.ListedConcept> listed = new ArrayList<>();
        for (String c : concepts) listed.add(new BookStore.ListedConcept(c, null));
        return judgeListed(title, rules, code, listed);
    }

    /**
     * @param concepts each concept with the numbers of the sentences step 4 said explain it (1 for the first of
     *                 {@link #units}), or {@code null} to look for the concept's words instead
     * @return each concept with whether it is kept and why, in the order given
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

    /** @return the sentences, list items and code blocks of a rule file, each with its role, in the file's order */
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
                if (text != null) out.add(new Unit("code", text));
                continue;
            }
            String role = roleOf.get(r.name());
            if (role == null) continue;
            for (RuleFile.Token t : r.items()) if (t.kind() == RuleFile.Token.Kind.STRING) out.add(new Unit(role, t.value()));
        }
        return out;
    }

    /** @return a pattern for the concept's words as a whole, case ignored, the last word singular or plural */
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
     * Judges the concepts of every section of the book that has both a rule file and a list, and stores the judgement.
     *
     * @return how many sections were judged, and how many concepts kept and dropped, in one line
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
