package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transforms a tree of concept classes into a single, smaller, standalone file by applying the logic of {@link
 * ConceptConsolidation}.
 *
 * <ul>
 * <li>{@code open(classesDir, input)} loads the concept class files and the result of step 3, then prepares the
 * consolidation process, returning a string that reports the number of classes and files processed.</li>
 * <li>{@code write(out, input)} writes the resulting smaller tree to a new file, returning the path to the written
 * file.</li>
 * </ul>
 *
 * <p>The actor holds the loaded {@link ConceptClasses} and the current {@link ConceptConsolidation} state between
 * calls.</p>
 *
 * <p>One instance serves one workflow at a time.</p>
 *
 * <p>If {@code open} is called with both {@code classesDir} and {@code input} being blank, it throws an {@link
 * IllegalStateException}. If {@code write} is called before {@code open}, it throws an {@link IllegalStateException}.
 * {@code write} refuses to overwrite an existing file.</p>
 */
public final class ConsolidationJob extends QuestionWork implements AutoCloseable {

    private ConceptClasses classes;
    private ConceptConsolidation consolidation;

    public ConsolidationJob(FileGate files) {
        super(files);
    }

    /**
     * Opens the concept classes and the result of step 3 to prepare for consolidation.
     *
     * Returns a string stating how many class files were opened and how many total files were read.
     *
     * If both {@code classesDir} and {@code input} are blank, it throws an {@link IllegalStateException}.
     *
     * Does not perform the actual consolidation; use {@link #write(String, String)} to do that.
     *
     * <pre>{@code
     * open("path/to/classes", "path/to/step3/result")
     * }</pre>
     *
     * @param classesDir the directory containing the class files, or blank if none
     * @param input the path to the result of step 3 to be made smaller, or blank if none
     * @return a string in the form "opened N class(es) from M file(s)"
     * @throws IOException if an error occurs while reading the files
     * @throws IllegalStateException if both {@code classesDir} and {@code input} are blank
     */
    public String open(String classesDir, String input) throws IOException {
        Map<String, String> read = new LinkedHashMap<>();
        if (!blank(classesDir)) read.putAll(lispFiles(path(classesDir, "the classes' directory"), Integer.MAX_VALUE));
        String result = "";
        if (!blank(input)) {
            Path in = path(input, "the result of step 3");
            result = files.read(in);
            read.put(in.toString(), result);
        }
        if (read.isEmpty()) throw new IllegalStateException("no class file and no result of step 3 to make smaller");
        close();
        classes = new ConceptClasses(read);
        int before = classes.names().size();
        consolidation = new ConceptConsolidation(classes, result);
        asking(consolidation);
        return "opened " + before + " class(es) from " + read.size() + " file(s)";
    }

    /**
     * Writes a new file containing the smaller tree of concept classes.
     *
     * <p>Returns a string that is the path to the newly written file.
     *
     * <p>If the output file already exists, the action fails.
     *
     * <p>The {@code input} argument specifies the file containing the result of step 3 to be used as the basis for the
     * smaller tree; if {@code input} is blank, the class files are used instead.
     *
     * @param out the directory for the output file
     * @param input the result of step 3, or blank to use the class files
     * @return the path to the written file
     * @throws IOException if an error occurs during writing
     */
    public String write(String out, String input) throws IOException {
        if (consolidation == null) throw new IllegalStateException("no tree is being made smaller");
        Path f = path(out, "the output file");
        writeNew(f, consolidation.resultFile(blank(input) ? "the class files" : input.strip()), "the smaller tree");
        return f.toString();
    }

    @Override
    public void close() {
        if (classes != null) classes.close();
        classes = null;
    }
}
