package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The work of {@code abstract-roles.yaml} ({@code ConceptClasses_261022_oo01}, {@code FrameMakingWorkflow_261022_oo01}
 * step 3): the paragraphs of a book are assigned to concept classes ({@link SlotAssignment}), and the classes grown and
 * the assignments are written to one file.
 */
public final class SlotsJob extends QuestionWork {

    private SlotAssignment assignment;

    public SlotsJob(FileGate files) {
        super(files);
    }

    /**
     * @param book    the book's directory
     * @param name    the book's name
     * @param tree    the concept tree's file, as {@code concept-tree.yaml} wrote it
     * @param classes the directory of concept class files ({@code rst-on-lisp/concepts}); blank for none
     */
    public String open(String book, String name, String tree, String classes) throws IOException {
        if (blank(name)) throw new IllegalArgumentException("the book's name is required");
        Path root = path(book, "the book's directory");
        String treeText = files.read(path(tree, "the concept tree"));
        Map<String, String> rules = ruleFiles(root);
        if (rules.isEmpty()) throw new IllegalStateException("no rule file with its markdown beside it under " + root);
        Map<String, String> classFiles = blank(classes) ? Map.of() : lispFiles(path(classes, "the classes' directory"), Integer.MAX_VALUE);
        assignment = new SlotAssignment(name.strip(), rules, treeText, new ConceptClasses(classFiles));
        asking(assignment);
        return "opened " + rules.size() + " rule file(s) and " + classFiles.size() + " class file(s)";
    }

    /** Writes the classes grown and the assignments, never over a file already there; the message is its path. */
    public String write(String out, String tree) throws IOException {
        if (assignment == null) throw new IllegalStateException("no paragraphs are being assigned");
        Path f = path(out, "the output file");
        writeNew(f, assignment.resultFile(blank(tree) ? "?" : tree.strip()), "an assignment");
        return f.toString();
    }
}
