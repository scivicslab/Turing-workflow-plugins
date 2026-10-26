package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * Converts markdown files of a book into rule files and code block files, one book at a time.
 *
 * <ul>
 * <li>{@code next(dir)} returns a JSON string containing the path of the first markdown file in {@code dir} that lacks
 * a corresponding rule file, or a JSON string indicating completion when all files have rule files.</li>
 * <li>{@code open(path)} prepares a markdown file for processing, returning a message stating the file is opened and
 * the number of paragraphs found.</li>
 * <li>{@code write()} writes the generated rule file and any associated code block files beside the original markdown
 * file, returning the path of the rule file.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows, but only one book file is open at a time.</p>
 */
public final class BookFileJob extends QuestionWork {

    private BookFileToRules file;
    private Path md;

    public BookFileJob(FileGate files) {
        super(files);
    }

    /**
     * Returns a JSON string indicating the next markdown file to process or a completion signal.
     *
     * <p>If there is a markdown file in {@code dir} that lacks a corresponding {@code .lisp} rule file, returns a JSON
     * string {@code {"file": "<path>", "done": "false"}}, where {@code <path>} is the absolute path to that file in
     * path order. If every markdown file in {@code dir} already has a corresponding rule file, returns {@code {"file":
     * "", "done": "true"}.
     *
     * @param dir the directory containing the book's markdown files
     * @return a JSON string containing either the path to the next file to be processed with {@code "done": "false"},
     * or an empty file field with {@code "done": "true"} if no work remains
     *
     * @throws IOException if an error occurs while accessing the directory or its files
     */
    public String next(String dir) throws IOException {
        Path root = path(dir, "the book's directory");
        for (Path m : files.list(root, ".md", Integer.MAX_VALUE)) {
            if (!files.exists(ruleFileOf(m))) return new JSONObject().put("file", m.toString()).put("done", "false").toString();
        }
        return new JSONObject().put("file", "").put("done", "true").toString();
    }

    /**
     * Opens a markdown file to be processed into a rule file, one file at a time.
     *
     * <p>The result is a success message containing the path to the opened file and the number of paragraphs found in
     * it.
     *
     * <p>One instance of this actor may serve several workflows or threads at once.
     *
     * <p>If the path is invalid or the file cannot be read, an IOException is thrown.
     *
     * @param path the path to the markdown file to be opened
     * @return a success message that holds the file path and the number of paragraphs in the format "opened <path>:
     * <n> paragraph(s)"
     *
     * @throws IOException if the file cannot be read or the path is invalid
     */
    public String open(String path) throws IOException {
        Path m = path(path, "the book's file");
        String id = "Book_" + m.getFileName().toString().replaceFirst("\\.md$", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        file = new BookFileToRules(files.read(m), m.toString(), id);
        md = m;
        asking(file);
        return "opened " + m + ": " + file.paragraphs() + " paragraph(s)";
    }

    /**
     * Writes the rule file and any associated code block files for the currently open markdown file to the file
     * system.
     *
     * <p>The result is a string containing the path to the newly created rule file.
     *
     * <p>If no file is currently open via {@link #open(String)}, the action fails with an error. It does not overwrite
     * any existing files.
     *
     * <pre>{@code
     * action: write
     * method: write
     * }</pre>
     *
     * @throws IOException if an I/O error occurs while writing the files.
     * @return the path to the rule file that was written.
     */
    public String write() throws IOException {
        if (file == null) throw new IllegalStateException("no book's file is open");
        Map<Path, String> out = new LinkedHashMap<>();
        out.put(ruleFileOf(md), file.ruleFile());
        for (Map.Entry<String, String> e : file.codeFiles().entrySet()) out.put(md.resolveSibling(e.getKey()), e.getValue());
        writeNew(out, "a rule file");
        return ruleFileOf(md).toString();
    }

    private static Path ruleFileOf(Path md) {
        return md.resolveSibling(md.getFileName().toString().replaceFirst("\\.md$", ".lisp"));
    }
}
