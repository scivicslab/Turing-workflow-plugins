package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * Generates a sequence of questions for a writer by using a {@link Questioner} to drive the workflow, and writes the
 * resulting data to files via a {@link FileGate}.
 *
 * <ul>
 * <li>{@code question} returns a JSON string containing the next question and a {@code "done"} flag ({@code "true"} if
 * no questions remain)</li>
 * <li>{@code answer(String reply)} accepts the writer's response to the last question and returns the string {@code
 * "answered"}</li>
 * </ul>
 *
 * <p>One instance may serve a single workflow at a time.</p>
 */
public abstract class QuestionWork {

    protected final FileGate files;
    private Questioner questioner;

    protected QuestionWork(FileGate files) {
        this.files = files;
    }

    /** Sets {@code q} as the source of questions for the work. */
    protected void asking(Questioner q) {
        this.questioner = q;
    }

    /**
     * Returns a JSON string containing the next question for the writer.
     *
     * The string is either {@code {"question": <text>, "done": "false"}} if a question is available, or {@code
     * {"question": "", "done": "true"}} if no questions remain.
     *
     * Throws an {@link IllegalStateException} if no questioner has been set.
     */
    public String question() {
        if (questioner == null) throw new IllegalStateException("nothing is open");
        String q = questioner.nextQuestion();
        return new JSONObject().put("question", q == null ? "" : q).put("done", q == null ? "true" : "false").toString();
    }

    /**
     * Submits the writer's reply to the current question.
     *
     * Returns a string that is {@code "answered"}.
     *
     * If the reply is {@code null}, throws an {@link IllegalArgumentException}. If no questioner has been set via
     * {@link #asking(Questioner)}, throws an {@link IllegalStateException}.
     *
     * @param reply the text provided by the writer as an answer to the current question
     */
    public String answer(String reply) {
        if (questioner == null) throw new IllegalStateException("nothing is open");
        if (reply == null) throw new IllegalArgumentException("no reply was given");
        questioner.answer(reply);
        return "answered";
    }

    /**
     * Returns a map of all rule files found under {@code root} that have a corresponding markdown file in the same
     * directory, where the key is the absolute path to the {@code .lisp} file and the value is its text content.
     *
     * <p>The map is ordered by the file paths.
     *
     * <p>An {@link IOException} is thrown if a file cannot be read.
     */
    protected Map<String, String> ruleFiles(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : files.list(root, ".lisp", Integer.MAX_VALUE)) {
            Path md = p.resolveSibling(p.getFileName().toString().replaceFirst("\\.lisp$", ".md"));
            if (files.exists(md)) out.put(p.toString(), files.read(p));
        }
        return out;
    }

    /**
     * Returns a map of every {@code .lisp} file found under {@code dir} up to {@code depth} levels, where the key is
     * the file path and the value is the file content, ordered by path. If {@code dir} does not exist, returns an
     * empty map.
     *
     * @param dir the directory to search
     * @param depth the maximum number of levels to descend into the directory structure
     * @return a map of file paths to their contents, ordered by path; an empty map if {@code dir} is not found
     * @throws IOException if an I/O error occurs during reading
     */
    protected Map<String, String> lispFiles(Path dir, int depth) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : listOrNone(dir, ".lisp", depth)) out.put(p.toString(), files.read(p));
        return out;
    }

    protected List<Path> listOrNone(Path dir, String suffix, int depth) throws IOException {
        try {
            return files.list(dir, suffix, depth);
        } catch (NoSuchFileException e) {
            return List.of();
        }
    }

    /**
     * Writes a set of new files if none of them already exist.
     *
     * <p>If any file in {@code out} already exists, the method throws an {@link IllegalStateException} and no files
     * are written. The {@code what} argument is used in the error message.
     *
     * @param out a map where each key is the destination path and each value is the text to be written
     * @param what a description of the work being done, used in the error message if a file already exists
     * @throws IOException if a file cannot be read or written
     */
    protected void writeNew(Map<Path, String> out, String what) throws IOException {
        for (Path f : out.keySet()) {
            files.mayWrite(f);
            if (files.exists(f)) throw new IllegalStateException(f + " already exists; " + what + " is not overwritten");
        }
        for (Map.Entry<Path, String> e : out.entrySet()) files.write(e.getKey(), e.getValue());
    }

    protected void writeNew(Path f, String text, String what) throws IOException {
        Map<Path, String> one = new LinkedHashMap<>();
        one.put(f, text);
        writeNew(one, what);
    }

    /**
     * Returns an absolute, normalized path from the given string, stripping leading or trailing whitespace and
     * removing {@code .} or {@code ..} components.
     *
     * Throws an {@link IllegalArgumentException} if the string is null or blank.
     *
     * @param p the string representing the path
     * @param what the name of the required argument to use in the exception message
     * @return the absolute and normalized {@link Path}
     */
    protected static Path path(String p, String what) {
        if (p == null || p.isBlank()) throw new IllegalArgumentException(what + " is required");
        return Path.of(p.strip()).toAbsolutePath().normalize();
    }

    protected static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static boolean isDirectory(Path p) {
        return Files.isDirectory(p);
    }
}
