package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares a book's sub-section against a directory of existing samples to determine if its structure is new, and
 * updates the samples' directory accordingly.
 *
 * <ul>
 * <li>{@code open} prepares the comparison by loading the sub-section's text and judging it against the existing
 * samples, returning a message that names the sub-section and the number of samples used.</li>
 * <li>{@code write} performs the update in the samples' directory, returning {@code new <name>} if a new structure was
 * written, {@code same <name>} if an existing sample was updated with an "also-seen" note, or {@code skip: <reason>}
 * if the sub-section was not worth keeping.</li>
 * </ul>
 *
 * <p>Between calls, this class holds the results of the comparison for a specific sub-section. One instance may serve
 * only one workflow at a time because it holds the state of the current comparison.</p>
 *
 * <pre>{@code
 * actor: sample-job
 * method: open
 * arguments:
 *   bookFile: "path/to/book.md"
 *   heading: "3.2 Comments"
 *   name: ""
 *   samples: "path/to/samples"
 * method: write
 * arguments:
 *   samples: "path/to/samples"
 * }</pre>
 *
 * <p>If {@code open} is called with a blank heading, it fails with an error. If {@code write} is called before {@code
 * open} or before the comparison is finished, it fails with an error.</p>
 */
public final class SampleJob extends QuestionWork {

    private BookToRules book;

    public SampleJob(FileGate files) {
        super(files);
    }

    /**
     * Opens a book's sub-section to judge it against existing samples in a directory.
     *
     * @param bookFile the path to the book's markdown file
     * @param heading the sub-section's title, as the file writes it
     * @param name the sample's name; a blank string lets the writer name a new structure
     * @param samples the path to the samples' directory
     * @return a message stating the sub-section's heading, the book's file path, and the number of samples it was
     * judged against
     *
     * @throws IOException if the book's file or the samples' directory cannot be read
     * @throws IllegalArgumentException if heading is blank
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
     * Writes the judgement of the current sub-section to the samples' directory as a new or updated sample file.
     *
     * Returns a string describing the result: {@code new <name>} if a new sample was created, {@code same <name>} if
     * an existing sample was updated with an also-seen line, or {@code skip: <reason>} if the structure was not worth
     * keeping.
     *
     * If the sub-section has not been judged yet, the method throws an {@link IllegalStateException}.
     *
     * <pre>{@code
     * write:
     *   arguments:
     *     samples: "/path/to/samples"
     * }</pre>
     *
     * @param samples the samples' directory
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
