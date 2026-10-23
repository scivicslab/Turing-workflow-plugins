package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads a book into the H2 database of the intermediate data ({@code FrameOntology_261021_oo01}, getting the concept
 * hierarchy), one step per method: its table of contents ({@link BookOutline}) and its text cut into those nodes
 * ({@link BookSections}), with no model; then, asking a model at a chat endpoint, each section as a rule file, its
 * concepts, which of them to keep (no model), how they relate, and a review of those relations. Each call opens the
 * database and closes it again; nothing is kept between calls.
 * The book is read, and the database file checked, through a {@link FileGate}.
 */
public final class BookStructure {

    private final FileGate files;

    /** Reads and writes through {@link FileGate#open()}. */
    public BookStructure() {
        this(FileGate.open());
    }

    public BookStructure(FileGate files) {
        this.files = files;
    }

    /**
     * Reads the table of contents of the book under {@code dir} from the headings of its markdown files and stores it
     * under {@code name}, replacing what the book had.
     *
     * @param db   the H2 database file, without {@code .mv.db}
     * @return what was read, in one line
     */
    public String readToc(String db, String name, String dir) throws Exception {
        Path root = Path.of(dir).toAbsolutePath().toRealPath();
        Map<String, String> md = markdown(root);
        if (md.isEmpty()) throw new IllegalArgumentException("no markdown file under " + root);
        BookOutline.Outline outline = BookOutline.read(md);
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        try (BookStore store = new BookStore(url(dbFile))) {
            store.saveOutline(name, root.toString(), outline);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (BookOutline.Node n : outline.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
        return "read " + md.size() + " markdown file(s) of " + name + ": " + byLevel
                + (outline.notes().isEmpty() ? "" : "; " + String.join("; ", outline.notes()));
    }

    /**
     * Cuts the text of the book stored under {@code name} into the nodes of the table of contents {@link #readToc}
     * stored, and stores the sections, replacing what the book had.
     *
     * @return what was cut, in one line
     */
    public String cutSections(String db, String name) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = new BookStore(url(dbFile))) {
            String dir = store.dirOf(name);
            if (dir == null) throw new IllegalArgumentException("no book " + name + " in " + db + "; read its table of contents first");
            BookSections.Result result = BookSections.split(markdown(Path.of(dir)), store.outline(name));
            store.saveSections(name, result.sections());
            long empty = result.sections().stream().filter(x -> x.text().isEmpty()).count();
            long spanning = result.sections().stream().filter(x -> x.files().size() > 1).count();
            return result.sections().size() + " section(s) of " + name + "; " + empty + " with no text of their own; "
                    + spanning + " spanning more than one file" + (result.notes().isEmpty() ? "" : "; " + String.join("; ", result.notes()));
        }
    }

    /** One step that asks a model at a chat endpoint about every section of a book ({@code SectionTagging.tagAll} and its like). */
    interface Batch {
        String run(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel, Consumer<String> log)
                throws Exception;
    }

    /**
     * Step 3: makes each section into an rst-on-lisp rule file ({@link SectionTagging}).
     *
     * @param url      the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model    the model the endpoint serves
     * @param parallel how many sections are asked at once
     * @param only     the sections to ask again, by number; empty for every one not done yet
     */
    public String rules(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2000, parallel, only, SectionTagging::tagAll);
    }

    /** Step 4: lists the concepts each section explains, with the sentences that explain them ({@link SectionConcepts}). */
    public String concepts(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 400, parallel, only, SectionConcepts::listAll);
    }

    /** Step 5: judges which listed concepts to keep, by the role of their sentences ({@link ConceptImportance}). No model is asked. */
    public String judge(String db, String name) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = new BookStore(url(dbFile))) {
            return ConceptImportance.judgeAll(store, name);
        }
    }

    /** Step 6: asks how each section's kept concepts relate: is-a, part-of, feature ({@link SectionRelations}). */
    public String relations(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 600, parallel, only, SectionRelations::relateAll);
    }

    /** Step 7: reviews step 6's relations: keep, another kind, reverse or drop ({@link SectionReview}). */
    public String review(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 400, parallel, only, SectionReview::reviewAll);
    }

    /** The concrete frame's material: for each concept on the section's list, the rules that say its properties and behaviors ({@link FrameMaterial}). */
    public String collect(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 800, parallel, only, FrameMaterial::collectAll);
    }

    /** Takes off each section's list a concept that got fewer than {@code min} rules. No model is asked. */
    public String prune(String db, String name, int min) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = new BookStore(url(dbFile))) {
            return FrameMaterial.pruneAll(store, name, min);
        }
    }

    private String ask(String db, String name, String url, String model, int maxTokens, int parallel, List<String> only, Batch batch)
            throws Exception {
        if (url == null || url.isBlank() || model == null || model.isBlank()) throw new IllegalArgumentException("url and model are required");
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = new BookStore(url(dbFile))) {
            if (store.dirOf(name) == null) throw new IllegalArgumentException("no book " + name + " in " + db);
            java.util.logging.Logger log = java.util.logging.Logger.getLogger(BookStructure.class.getName());
            return batch.run(store, name, only == null ? List.of() : only, new ChatEndpoint(url, model, maxTokens), Math.max(1, parallel),
                    line -> log.info(name + ": " + line));
        }
    }

    /** @return every markdown file under {@code root}, path under it to text, in path order, read through the gate */
    Map<String, String> markdown(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : files.list(root, ".md", Integer.MAX_VALUE)) out.put(root.relativize(p).toString(), files.read(p));
        return out;
    }

    private static String url(Path dbFile) {
        return "jdbc:h2:" + dbFile + ";AUTO_SERVER=TRUE";
    }
}
