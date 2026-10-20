package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a book's paragraphs into rst-on-lisp rules, with a writer's help on two points only: the
 * OCR misreadings to correct, and how the sentences of a paragraph are related. The sentences
 * themselves are the book's ({@code FramesAndSamplesFromBooks_261021_oo01}).
 *
 * <p>Takes text and returns text, and keeps nothing between calls.</p>
 */
public final class BookRules {

    /**
     * The relation names a sentence may have to its paragraph's nucleus: {@code relation-name} and
     * {@code program-relation} of rst-on-lisp's {@code grammar.lisp}.
     */
    static final Set<String> RELATIONS = new LinkedHashSet<>(Arrays.asList(
            "attribution", "attribution-n", "background", "circumstance", "cause", "result", "consequence-s",
            "consequence-n", "Consequence", "Cause-Result", "comparison", "Comparison", "preference", "analogy",
            "Analogy", "Proportion", "condition", "hypothetical", "contingency", "otherwise", "Otherwise",
            "antithesis", "Contrast", "concession", "elaboration-additional", "elaboration-general-specific",
            "elaboration-part-whole", "elaboration-process-step", "elaboration-object-attribute",
            "elaboration-set-member", "example", "definition", "purpose", "enablement", "evaluation-s",
            "evaluation-n", "Evaluation", "interpretation-s", "interpretation-n", "Interpretation", "conclusion",
            "Conclusion", "comment", "evidence", "explanation-argumentative", "reason", "Reason", "List",
            "Disjunction", "manner", "means", "problem-solution-s", "problem-solution-n", "Problem-Solution",
            "question-answer-s", "question-answer-n", "Question-Answer", "statement-response-s",
            "statement-response-n", "Statement-Response", "Topic-Comment", "Comment-Topic", "rhetorical-question",
            "summary-s", "summary-n", "restatement", "temporal-before", "temporal-after", "temporal-same-time",
            "Temporal-Same-Time", "Sequence", "Inverted-Sequence", "topic-shift", "Topic-Shift", "topic-drift",
            "Topic-Drift", "Same-Unit", "TextualOrganization",
            "domain-meaning", "assertion", "recommendation", "pitfall", "dispatch"));

    /** The largest share of a paragraph's characters an OCR correction may change. */
    static final double MAX_CORRECTION = 0.1;

    private static final Pattern ANSWER_LINE = Pattern.compile("^\\s*(\\d+)\\s*[:.)\\-]\\s*([A-Za-z][A-Za-z-]*)\\s*$");

    /**
     * What the writer is asked to correct in one paragraph.
     *
     * @param paragraph the paragraph as read from the book
     * @param before    the paragraph before it, or {@code ""}
     * @param after     the paragraph after it, or {@code ""}
     */
    public String ocrPrompt(String paragraph, String before, String after) {
        return "Below is a paragraph of a book that was read by OCR, with the paragraphs around it for context.\n\n"
                + "Paragraph before:\n" + (before.isBlank() ? "(none)" : before) + "\n\n"
                + "The paragraph:\n" + paragraph + "\n\n"
                + "Paragraph after:\n" + (after.isBlank() ? "(none)" : after) + "\n\n"
                + "Correct only what the OCR misread in the paragraph: a wrong character or word "
                + "(\"roct\" where the text means \"root\"), a symbol read for a word (\"$\\pm k$\" where the text means \"または\"). "
                + "Do not rephrase, do not reorder, do not add or remove words, and keep the punctuation as it is. "
                + "Write the paragraph with the corrections and nothing else; if nothing is misread, write it unchanged.";
    }

    /**
     * @return whether a correction changes few enough characters to be taken as corrections rather
     *         than a rewrite: at most {@link #MAX_CORRECTION} of them, by edit distance
     */
    public boolean correctionAccepted(String original, String corrected) {
        if (corrected == null || corrected.isBlank()) return false;
        String a = original.strip();
        String b = corrected.strip();
        int longest = Math.max(a.length(), b.length());
        return longest == 0 || (double) editDistance(a, b) / longest <= MAX_CORRECTION;
    }

    /** What the writer is asked about one paragraph: which sentence is the nucleus, and how each other one relates to it. */
    public String relationPrompt(List<String> sentences) {
        StringBuilder sb = new StringBuilder("Below are the sentences of one paragraph of a book, numbered.\n\n");
        for (int i = 0; i < sentences.size(); i++) sb.append(i + 1).append(". ").append(sentences.get(i)).append('\n');
        sb.append("\nIn the sense of Rhetorical Structure Theory, one sentence is the paragraph's nucleus: what the "
                + "paragraph is there to say. Every other sentence is a satellite that relates to the nucleus by one of "
                + "these relations:\n").append(String.join(", ", RELATIONS)).append("\n\n")
          .append("For each sentence, write one line: its number, a colon, and either \"nucleus\" or the relation's name, "
                + "e.g. \"1: nucleus\" and \"2: definition\". Exactly one sentence is the nucleus. Write nothing else.");
        return sb.toString();
    }

    /**
     * @param reply the writer's answer
     * @param count how many sentences the paragraph has
     * @return {@code "nucleus"} or a relation name for each sentence, in order
     * @throws IllegalArgumentException when a sentence is left out, a name is not a relation, or there is not exactly one nucleus
     */
    public List<String> parseRelations(String reply, int count) {
        TreeMap<Integer, String> byNumber = new TreeMap<>();
        for (String line : reply.split("\\R")) {
            Matcher m = ANSWER_LINE.matcher(line);
            if (m.matches()) byNumber.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        List<String> out = new ArrayList<>();
        int nuclei = 0;
        for (int n = 1; n <= count; n++) {
            String name = byNumber.get(n);
            if (name == null) throw new IllegalArgumentException("sentence " + n + " is not in the answer");
            if (name.equalsIgnoreCase("nucleus")) {
                name = "nucleus";
                nuclei++;
            } else if (!RELATIONS.contains(name)) {
                throw new IllegalArgumentException("sentence " + n + ": '" + name + "' is not a relation");
            }
            out.add(name);
        }
        if (nuclei != 1) throw new IllegalArgumentException("the answer has " + nuclei + " nuclei; a paragraph has exactly one");
        return out;
    }

    /**
     * @param name      the paragraph's rule name
     * @param sentences its sentences, in order
     * @param relations {@code "nucleus"} or a relation name for each sentence
     * @return the paragraph's rule, then one rule per sentence, one per line
     */
    public String paragraphRules(String name, List<String> sentences, List<String> relations) {
        StringBuilder head = new StringBuilder("(defrule " + name + " ->");
        StringBuilder leaves = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            String leaf = name + "/" + (i + 1);
            head.append(" :").append(relations.get(i)).append(' ').append(leaf);
            leaves.append("(defrule ").append(leaf).append(" -> \"").append(RuleFile.escape(sentences.get(i))).append("\")\n");
        }
        return head.append(")\n").append(leaves).toString();
    }

    /** @return a rule file for one use, with no section yet */
    public String newUseFile(String documentId, String title) {
        return ";;; " + title + ". The sentences are the books' own, with OCR misreadings corrected; each section's\n"
                + ";;; :source names the file it was read from.\n"
                + "(in-package :rst)\n"
                + "(defdocument " + documentId + ")\n\n"
                + "(defrule document -> title sections)\n"
                + "(defrule title -> \"" + RuleFile.escape(title) + "\")\n";
    }

    /**
     * Adds one section of a book to the end of a use's rule file.
     *
     * @param file     the use's rule file
     * @param section  the section's rule name
     * @param heading  the section's title, without {@code #}
     * @param source   the path of the book's file the section was read from
     * @param children the section's blocks, by rule name, in order
     * @param body     the rules of those blocks
     */
    public String addSection(String file, String section, String heading, String source, List<String> children, String body) {
        RuleFile rf = RuleFile.parse(file);
        RuleFile.Rule sections = rf.rule("sections");
        String withList;
        if (sections == null) {
            withList = file.stripTrailing() + "\n(defrule sections -> " + section + ")\n";
        } else {
            StringBuilder items = new StringBuilder();
            for (RuleFile.Token t : sections.items()) items.append(t.raw()).append(' ');
            withList = rf.replace("sections", "(defrule sections -> " + items + section + ")").text();
        }
        return withList.stripTrailing() + "\n\n(defrule " + section + " -> " + String.join(" ", children)
                + " :heading \"## " + RuleFile.escape(heading) + "\" :source \"" + RuleFile.escape(source) + "\")\n"
                + body;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
