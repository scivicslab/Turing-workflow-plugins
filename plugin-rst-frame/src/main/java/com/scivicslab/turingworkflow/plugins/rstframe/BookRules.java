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
 * Converts a book's paragraphs into rst-on-lisp rules by generating prompts for a human writer to correct OCR
 * misreadings and identify sentence relations.
 *
 * <p>Its main operations are:
 * <ul>
 * <li>ocrPrompt: returns a text prompt asking the writer to correct only OCR errors in a given paragraph, using the
 * surrounding paragraphs for context.</li>
 * <li>correctionAccepted: returns {@code true} if the edit distance between the original and corrected text is at most
 * {@link #MAX_CORRECTION} of the length of the longer string.</li>
 * <li>relationPrompt: returns a text prompt asking the writer to identify the nucleus and the rhetorical relations of
 * all sentences in a list.</li>
 * <li>parseRelations: returns a list of relation names for each sentence in the order they were provided, based on the
 * writer's reply.</li>
 * <li>paragraphRules: returns a string containing the rst-on-lisp rule for the paragraph and the individual rules for
 * its sentences.</li>
 * <li>newUseFile: returns a new rst-on-lisp rule file for a specific document and title.</li>
 * <li>addSection: returns the updated content of a rule file after appending a new section and its constituent
 * blocks.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once. It keeps no state between calls.</p>
 */
public final class BookRules {

    /**
     * Lists the names of the relations a sentence may have to its paragraph's nucleus, as defined in the {@code
     * grammar.lisp} of rst-on-lisp.
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

    /**
     * The maximum proportion of characters in a paragraph that may be changed by an OCR correction, expressed as a
     * fraction of the original length.
     */
    static final double MAX_CORRECTION = 0.1;

    private static final Pattern ANSWER_LINE = Pattern.compile("^\\s*(\\d+)\\s*[:.)\\-]\\s*([A-Za-z][A-Za-z-]*)\\s*$");

    /**
     * Returns a prompt that asks a writer to correct OCR misreadings in a specific paragraph.
     *
     * <p>The prompt includes the target {@code paragraph} and the {@code before} and {@code after} paragraphs for
     * context. It instructs the writer to correct only misread characters or symbols without rephrasing, reordering,
     * or adding words, and to return the corrected paragraph alone.</p>
     *
     * @param paragraph the paragraph as read from the book
     * @param before the paragraph before it, or {@code ""} if none
     * @param after the paragraph after it, or {@code ""} if none
     * @return a string containing the instructions and the context paragraphs for the writer
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
     * Returns whether the number of changes in the corrected text is small enough to be considered a correction rather
     * than a rewrite.
     *
     * The result is true if the edit distance between the original and the corrected text, divided by the length of
     * the longer string, is at most {@link #MAX_CORRECTION}.
     *
     * A result of false is returned if the corrected text is null, blank, or exceeds the allowed change ratio.
     *
     * @param original the text as read from the book
     * @param corrected the text after the writer's proposed corrections
     */
    public boolean correctionAccepted(String original, String corrected) {
        if (corrected == null || corrected.isBlank()) return false;
        String a = original.strip();
        String b = corrected.strip();
        int longest = Math.max(a.length(), b.length());
        return longest == 0 || (double) editDistance(a, b) / longest <= MAX_CORRECTION;
    }

    /**
     * Returns a string that instructs a writer to identify the nucleus of a paragraph and the Rhetorical Structure
     * Theory relations of its satellite sentences.
     *
     * @param sentences the sentences of the paragraph, in order
     */
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
     * Parses a writer's response into a list of relation names for each sentence in a paragraph.
     *
     * @param reply the writer's answer, where each line contains a sentence number and a relation name
     * @param count the number of sentences in the paragraph
     * @return a list of {@code "nucleus"} or a relation name for each sentence, in the order of the sentences
     * @throws IllegalArgumentException if a sentence number is missing from the reply, a name is not a valid relation,
     * or there is not exactly one nucleus
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
                // a name that differs from one relation only in case is that relation ("contrast" for "Contrast")
                String n0 = name;
                List<String> sameLetters = RELATIONS.stream().filter(r -> r.equalsIgnoreCase(n0)).toList();
                if (sameLetters.size() != 1) throw new IllegalArgumentException("sentence " + n + ": '" + name + "' is not a relation");
                name = sameLetters.get(0);
            }
            out.add(name);
        }
        if (nuclei != 1) throw new IllegalArgumentException("the answer has " + nuclei + " nuclei; a paragraph has exactly one");
        return out;
    }

    /**
     * Returns a string containing the Lisp-style rule for a paragraph and the individual rules for each of its
     * sentences.
     *
     * @param name the paragraph's rule name
     * @param sentences the paragraph's sentences, in order
     * @param relations {@code "nucleus"} or a relation name for each sentence
     * @return a string starting with a rule for the paragraph that links the name to its sentences via their
     * relations, followed by one rule per sentence on new lines
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

    /**
     * Returns a string that is a new rst-on-lisp rule file containing a document definition and a title rule.
     *
     * @param documentId the identifier for the document
     * @param title the title of the document
     */
    public String newUseFile(String documentId, String title) {
        return ";;; " + title + ". The sentences are the books' own, with OCR misreadings corrected; each section's\n"
                + ";;; :source names the file it was read from.\n"
                + "(in-package :rst)\n"
                + "(defdocument " + documentId + ")\n\n"
                + "(defrule document -> title sections)\n"
                + "(defrule title -> \"" + RuleFile.escape(title) + "\")\n";
    }

    /**
     * Appends a section to a rule file, including its title, source path, and the rules for its constituent blocks.
     *
     * @param file the current content of the use's rule file
     * @param section the name of the section being added
     * @param heading the section's title, without the {@code #} character
     * @param source the path of the book's file from which the section was read
     * @param children the names of the section's blocks, in order
     * @param body the rule definitions for the section's blocks
     * @return a string containing the updated rule file content with the new section appended
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
