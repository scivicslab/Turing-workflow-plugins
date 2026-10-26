package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ConceptTree} from a book's rule files and writes it as a subjects file.
 *
 * <ul>
 * <li>{@code open} returns a summary of the number of rule files, paragraphs, and given concepts processed.</li>
 * <li>{@code write} returns the path to the newly created subjects file.</li>
 * </ul>
 *
 * <p>The job holds a {@link ConceptTree} in memory between calls. One instance may serve several workflows or threads
 * at once.</p>
 */
public final class ConceptTreeJob extends QuestionWork {

    private ConceptTree tree;

    public ConceptTreeJob(FileGate files) {
        super(files);
    }

    /**
     * Opens a book by walking through every rule file in its directory that has a corresponding markdown file beside
     * it, in path order.
     *
     * @param book the book's directory
     * @param name the book's name, which becomes the root of the concept tree
     * @param given a file containing a list of existing concepts, one per line; if this file is blank, no concepts are
     * pre-loaded, but if it contains concepts, the job will refuse to accept any new concepts discovered in the rule
     * files
     *
     * @return a string stating the number of rule files opened, the number of paragraphs found, and the number of
     * given concepts loaded
     *
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if name is blank
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

    /**
     * Writes the current concept tree to a subjects file, provided the destination does not already exist.
     *
     * @param out the path to the output file
     * @param book the name of the book being processed
     * @return the absolute path of the written file
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if no concept tree is currently being built
     */
    public String write(String out, String book) throws IOException {
        if (tree == null) throw new IllegalStateException("no concept tree is being built");
        Path f = path(out, "the output file");
        writeNew(f, tree.subjectsFile("the body of the rule files under " + (blank(book) ? "?" : book.strip())), "a concept tree");
        return f.toString();
    }
}
