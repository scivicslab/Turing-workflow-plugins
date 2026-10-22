package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The work of {@code concept-tree.yaml} ({@code FrameMakingWorkflow_261022_oo01}, step 2): the rule files of a book are
 * walked one paragraph at a time and placed in a {@link ConceptTree}, which is written as a subjects file.
 */
public final class ConceptTreeJob extends QuestionWork {

    private ConceptTree tree;

    public ConceptTreeJob(FileGate files) {
        super(files);
    }

    /**
     * Opens a book: every rule file under its directory that has its markdown beside it, in path order.
     *
     * @param book  the book's directory
     * @param name  the book's name, the root of the tree
     * @param given a file of given concepts, one per line (a textbook's headings); blank for none, and with given
     *              concepts no new concept is accepted
     */
    public String open(String book, String name, String given) throws IOException {
        if (blank(name)) throw new IllegalArgumentException("the book's name is required");
        Path root = path(book, "the book's directory");
        Map<String, String> files = ruleFiles(root);
        if (files.isEmpty()) throw new IllegalStateException("no rule file with its markdown beside it under " + root);
        List<String> concepts = new ArrayList<>();
        if (!blank(given)) {
            for (String l : this.files.read(path(given, "the given concepts")).split("\\R")) if (!l.isBlank()) concepts.add(l.strip());
        }
        tree = new ConceptTree(name.strip(), files, concepts, concepts.isEmpty());
        asking(tree);
        return "opened " + files.size() + " rule file(s), " + tree.units().size() + " paragraph(s), " + concepts.size() + " given concept(s)";
    }

    /** Writes the tree built so far as a subjects file, never over a file already there; the message is its path. */
    public String write(String out, String book) throws IOException {
        if (tree == null) throw new IllegalStateException("no concept tree is being built");
        Path f = path(out, "the output file");
        writeNew(f, tree.subjectsFile("the body of the rule files under " + (blank(book) ? "?" : book.strip())), "a concept tree");
        return f.toString();
    }
}
