package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The concept tree of a textbook, by computation alone ({@code FrameMakingWorkflow_261022_oo01}, step 2): a textbook's
 * table of contents already is its concept tree, so each paragraph goes to the node whose number its heading carries.
 *
 * <p>The rule files are read in path order. A heading that begins with a number ({@code 4.1.1. Classes}) puts the
 * paragraphs under it into the node of that number; a heading that names a chapter ({@code Chapter 4 ▪ ...}) into the
 * chapter's node; a heading with neither ({@code In this chapter}, a run-on marked {@code (continued)}, a listing)
 * leaves them in the node of the last numbered heading before it. The result is in the form {@code concept-tree.yaml}
 * writes, with each paragraph as {@code file / rule} followed by {@code ;}, since a book's file names hold spaces.</p>
 */
public final class TextbookConceptTree {

    private static final Pattern SUBJECT = Pattern.compile("^\\(subject \"([^\"]*)\" \"((?:[^\"\\\\]++|\\\\.)*+)\"");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+\\S");
    private static final Pattern CHAPTER = Pattern.compile("^Chapter\\s+(\\d+)\\b");

    private TextbookConceptTree() {
    }

    /**
     * @param book      the book's name
     * @param subjects  the book's subject tree: {@code (subject "4.1.1" "Classes" ...)} lines
     * @param ruleFiles the book's rule files, path to text
     * @return the concept tree, every node of the subject tree with the paragraphs placed under it
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

    /** @return the node a heading names by its number, or {@code null} when it names none that is in the tree */
    static String numberOf(String heading, Map<String, String> titles) {
        String h = heading.strip();
        Matcher m = NUMBERED.matcher(h);
        if (m.find() && titles.containsKey(m.group(1)) && !h.contains("(continued)")) return m.group(1);
        Matcher c = CHAPTER.matcher(h);
        if (c.find() && titles.containsKey(c.group(1))) return c.group(1);
        return null;
    }
}
