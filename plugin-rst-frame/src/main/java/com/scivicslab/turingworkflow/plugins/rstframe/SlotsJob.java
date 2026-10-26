package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Assigns the paragraphs of a book to concept classes and writes the resulting assignments and expanded classes to a
 * file.
 *
 * <ul>
 * <li><code class="action">open</code> assigns the book's paragraphs to concept classes based on the concept tree and
 * existing class files, returning a string that reports the number of rule and class files processed.</li>
 * <li><code class="action">write</code> writes the assignments and the grown classes to a new file, returning the path
 * to that file.</li>
 * </ul>
 *
 * <p>One instance of this actor may serve several workflows at once, but its actions run one at a time.</p>
 *
 * <p>A workflow step might call it as follows:</p>
 * <pre>{@code
 * actor: slots-job
 * method: open
 * arguments:
 *   book: ~/works/rst-on-lisp/db/books/core-java-14-vol1
 *   name: core-java-14-vol1
 *   tree: ~/works/rst-on-lisp/db/books/concept-tree.yaml
 *   classes: ~/works/rst-on-lisp/db/books/concepts
 * }</pre>
 *
 * <p>The {@code open} action fails if the book's name is blank or if no rule files are found for the book. The {@code
 * write} action fails if {@code open} has not been successfully called first.</p>
 */
public final class SlotsJob extends QuestionWork {

    private SlotAssignment assignment;

    public SlotsJob(FileGate files) {
        super(files);
    }

    /**
     * Assigns the paragraphs of a book to concept classes and prepares the data for writing.
     *
     * <p>If the book's name is blank, throws an {@link IllegalArgumentException}. If no rule files are found under the
     * book's directory, throws an {@link IllegalStateException}.
     *
     * @param book the book's directory
     * @param name the book's name
     * @param tree the path to the concept tree file, as written by {@code concept-tree.yaml}
     * @param classes the directory of concept class files, or a blank string to use none
     * @return a string stating the number of rule files and class files opened
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

    /**
     * Writes the assignments and the grown concept classes to a new file, returning the file's path.
     *
     * <p>If the file already exists, the operation fails.
     *
     * @param out the directory for the output file
     * @param tree the name of the concept tree to include in the assignment, or an empty string to use a placeholder
     * @return the absolute path of the written file
     * @throws IllegalStateException if no assignments have been made via {@code open}
     */
    public String write(String out, String tree) throws IOException {
        if (assignment == null) throw new IllegalStateException("no paragraphs are being assigned");
        Path f = path(out, "the output file");
        writeNew(f, assignment.resultFile(blank(tree) ? "?" : tree.strip()), "an assignment");
        return f.toString();
    }
}
