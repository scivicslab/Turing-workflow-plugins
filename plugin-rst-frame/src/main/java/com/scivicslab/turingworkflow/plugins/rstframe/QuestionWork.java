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
 * A job that asks a writer one question at a time ({@link Questioner}): its {@code open} step decides what the
 * questions come from, {@link #question()} and {@link #answer(String)} walk them, and its {@code write} step writes
 * what was made. Every file is read and written through the job's {@link FileGate}.
 */
public abstract class QuestionWork {

    protected final FileGate files;
    private Questioner questioner;

    protected QuestionWork(FileGate files) {
        this.files = files;
    }

    /** Makes {@code q} the work the questions come from. */
    protected void asking(Questioner q) {
        this.questioner = q;
    }

    /**
     * @return {@code {"question": <text>, "done": "false"}}, or {@code {"question": "", "done": "true"}} when no
     *         question is left
     */
    public String question() {
        if (questioner == null) throw new IllegalStateException("nothing is open");
        String q = questioner.nextQuestion();
        return new JSONObject().put("question", q == null ? "" : q).put("done", q == null ? "true" : "false").toString();
    }

    /** Hands the writer's reply to the question {@link #question()} gave. */
    public String answer(String reply) {
        if (questioner == null) throw new IllegalStateException("nothing is open");
        if (reply == null) throw new IllegalArgumentException("no reply was given");
        questioner.answer(reply);
        return "answered";
    }

    /** @return every rule file under {@code root} that has its markdown beside it, path to text, in path order */
    protected Map<String, String> ruleFiles(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : files.list(root, ".lisp", Integer.MAX_VALUE)) {
            Path md = p.resolveSibling(p.getFileName().toString().replaceFirst("\\.lisp$", ".md"));
            if (files.exists(md)) out.put(p.toString(), files.read(p));
        }
        return out;
    }

    /** @return every {@code .lisp} file under {@code dir}, path to text, in path order; none when {@code dir} is not there */
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

    /** Writes each file; none is written when any of them is already there or may not be written. */
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

    /** @return the path, absolute and without {@code .} or {@code ..}; refused when blank */
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
