package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constructs a YAML representation of a textbook's concept tree by mapping paragraphs from rule files to the hierarchy
 * defined by the book's table of contents.
 *
 * <p>The main operation is:
 * <ul>
 * <li>{@code build} returns a string in the format of a {@code concept-tree.yaml} file, where each subject node
 * contains a list of its paragraphs as {@code file / rule} pairs separated by semicolons.</li>
 * </ul>
 *
 * <p>The construction follows the order of the rule file paths. A paragraph is placed under a node if its heading
 * begins with a number (e.g., {@code 4.1.1. Classes}), names a chapter (e.g., {@code Chapter 4}), or if it is a run-on
 * following a numbered heading. Paragraphs appearing before any numbered heading are noted in the file header but not
 * assigned to a node.
 *
 * <p>One instance may serve several workflows or threads at once.
 */
public final class TextbookConceptTree {

    private static final Pattern SUBJECT = Pattern.compile("^\\(subject \"([^\"]*)\" \"((?:[^\"\\\\]++|\\\\.)*+)\"");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+\\S");
    private static final Pattern CHAPTER = Pattern.compile("^Chapter\\s+(\\d+)\\b");

    private TextbookConceptTree() {
    }

    /**
     * Returns a string representing the textbook's concept tree, where every node from the subject tree has its
     * corresponding paragraphs placed under it.
     *
     * @param book the name of the book
     * @param subjects the book's subject tree as lines of {@code (subject "number" "title" ...)}
     * @param ruleFiles a map of rule file paths to their text content
     * @return a string in the format of a subject tree where each subject node includes its paragraphs as a
     * semicolon-separated list of {@code file / rule} entries
     */
    public static String build(String book, String subjects, Map<String, String> ruleFiles) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String line : subjects.split("\\R")) {
            Matcher m = SUBJECT.matcher(line.strip());
            if (m.find()) titles.put(m.group(1), m.group(2));
        }
        Map<String, List<String>> placed = new LinkedHashMap<>();
        for (String n : titles.keySet()) placed.put(n, new ArrayList<>());
        List<String> notes = new ArrayList<>();
        String current = null;
        int unplaced = 0;
        List<String> paths = new ArrayList<>(ruleFiles.keySet());
        paths.sort(null);
        for (String path : paths) {
            String file = path.substring(path.lastIndexOf('/') + 1);
            for (ConceptTree.Unit u : ConceptTree.paragraphs(path, ruleFiles.get(path))) {
                if (u.rule().equals("title")) continue;      // the file's title repeats a heading; it is no paragraph
                String n = numberOf(u.heading(), titles);
                if (n != null) current = n;
                if (current == null) {
                    unplaced++;
                    continue;
                }
                placed.get(current).add(file + " / " + u.rule());
            }
        }
        if (unplaced > 0) notes.add(unplaced + " paragraph(s) before the first numbered heading were placed under no node");
        StringBuilder sb = new StringBuilder();
        sb.append(";;; Concept tree of ").append(book).append(", a textbook: its table of contents, each paragraph placed under the node\n")
          .append(";;; whose number its heading carries, by computation alone (FrameMakingWorkflow_261022_oo01, step 2).\n");
        for (String note : notes) sb.append(";;; note: ").append(note).append('\n');
        sb.append("(in-package :rst)\n(defsubjects ").append(book).append(" :from \"the table of contents and the headings of the rule files\")\n\n");
        for (Map.Entry<String, String> e : titles.entrySet()) {
            sb.append("(subject \"").append(e.getKey()).append("\" \"").append(e.getValue()).append('"');
            List<String> ps = placed.get(e.getKey());
            if (!ps.isEmpty()) sb.append(" :paragraphs \"").append(RuleFile.escape(String.join("; ", ps) + ";")).append('"');
            sb.append(")\n");
        }
        return sb.toString();
    }

    /**
     * Returns the number of the node that a heading names, or {@code null} if the heading does not name a numbered
     * section or a chapter in the subject tree.
     *
     * A heading that is a run-on marked {@code (continued)} returns {@code null}.
     *
     * @param heading the text of the heading to parse
     * @param titles the map of subject numbers to titles used to verify the heading exists in the tree
     * @return the number of the node (e.g., "4.1.1" or "4") or {@code null} if no match is found
     */
    static String numberOf(String heading, Map<String, String> titles) {
        String h = heading.strip();
        Matcher m = NUMBERED.matcher(h);
        if (m.find() && titles.containsKey(m.group(1)) && !h.contains("(continued)")) return m.group(1);
        Matcher c = CHAPTER.matcher(h);
        if (c.find() && titles.containsKey(c.group(1))) return c.group(1);
        return null;
    }
}
