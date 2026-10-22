package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The work of {@code make-sample.yaml} ({@code FramesAndSamplesFromBooks_261021_oo01}): one sub-section of a book is
 * judged against the samples in a directory ({@link BookToRules}) and kept as a new sample only when its structure is new.
 */
public final class SampleJob extends QuestionWork {

    private BookToRules book;

    public SampleJob(FileGate files) {
        super(files);
    }

    /**
     * @param bookFile the book's markdown file
     * @param heading  the sub-section's title, as the file writes it
     * @param name     the sample's name; blank lets the writer name a new structure
     * @param samples  the samples' directory
     */
    public String open(String bookFile, String heading, String name, String samples) throws IOException {
        if (blank(heading)) throw new IllegalArgumentException("the sub-section's heading is required");
        Path f = path(bookFile, "the book's file");
        Path dir = path(samples, "the samples' directory");
        Map<String, String> known = new TreeMap<>();
        for (Path p : listOrNone(dir, ".lisp", 1)) known.put(p.getFileName().toString().replaceFirst("\\.lisp$", ""), files.read(p));
        book = new BookToRules(files.read(f), f.toString(), heading.strip(), blank(name) ? null : name.strip(), SampleCatalog.entries(known));
        asking(book);
        return "opened \"" + heading.strip() + "\" of " + f + ", against " + known.size() + " sample(s)";
    }

    /**
     * Acts on the judgement, in the samples' directory: a new structure is written as {@code <name>.lisp} with its code
     * blocks' files (never over a file there); the same structure as a sample adds one also-seen line to it; a
     * structure not worth keeping leaves nothing.
     *
     * @return what was done: {@code new <name>}, {@code same <name>} or {@code skip: <why>}
     */
    public String write(String samples) throws IOException {
        if (book == null) throw new IllegalStateException("no book is open");
        if (book.outcome() == null) throw new IllegalStateException("the sub-section has not been judged yet");
        Path d = path(samples, "the samples' directory");
        switch (book.outcome()) {
            case SKIP -> {
                return "skip: " + book.outcomeNote();
            }
            case SAME -> {
                Path f = d.resolve(book.outcomeName() + ".lisp");
                files.mayWrite(f);
                files.write(f, SampleCatalog.withAlsoSeen(files.read(f), book.alsoSeen()));
                return "same " + book.outcomeName();
            }
            default -> {
                Map<Path, String> out = new LinkedHashMap<>();
                out.put(d.resolve(book.outcomeName() + ".lisp"), book.sampleFile());
                for (Map.Entry<String, String> e : book.codeFiles().entrySet()) out.put(d.resolve(e.getKey()), e.getValue());
                writeNew(out, "a sample");
                return "new " + book.outcomeName();
            }
        }
    }
}
