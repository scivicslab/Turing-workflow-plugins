package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * The work of {@code book-to-rules.yaml}: the markdown files of a book are made into rule files one at a time
 * ({@link BookFileToRules}), each written beside its markdown with its code blocks' files.
 */
public final class BookFileJob extends QuestionWork {

    private BookFileToRules file;
    private Path md;

    public BookFileJob(FileGate files) {
        super(files);
    }

    /**
     * @return {@code {"file": <path>, "done": "false"}} for the first markdown file under {@code dir}, in path order,
     *         with no rule file beside it, or {@code {"file": "", "done": "true"}} when every one has its rule file
     */
    public String next(String dir) throws IOException {
        Path root = path(dir, "the book's directory");
        for (Path m : files.list(root, ".md", Integer.MAX_VALUE)) {
            if (!files.exists(ruleFileOf(m))) return new JSONObject().put("file", m.toString()).put("done", "false").toString();
        }
        return new JSONObject().put("file", "").put("done", "true").toString();
    }

    /** Opens one markdown file to be made into a rule file, one question at a time. */
    public String open(String path) throws IOException {
        Path m = path(path, "the book's file");
        String id = "Book_" + m.getFileName().toString().replaceFirst("\\.md$", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        file = new BookFileToRules(files.read(m), m.toString(), id);
        md = m;
        asking(file);
        return "opened " + m + ": " + file.paragraphs() + " paragraph(s)";
    }

    /** Writes the open file's rule file beside it, with its code blocks' files, never over a file there; the message is the rule file's path. */
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
