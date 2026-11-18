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
 * Transforms a book's markdown files into a structured database of concepts, relations, and properties in an H2
 * database.
 *
 * <ul><li>{@link #readToc(String, String, String)} reads the table of contents from markdown headings and stores
 * it.</li><li>{@link #cutSections(String, String)} partitions the book text into the sections defined by the table of
 * contents.</li><li>{@link #rules(String, String, String, String, int, List)} creates RST-on-Lisp rule files for every
 * section using a chat endpoint.</li><li>{@link #concepts(String, String, String, String, int, List)} identifies the
 * concepts explained in each section.</li><li>{@link #judge(String, String)} filters the listed concepts based on
 * their importance.</li><li>{@link #relations(String, String, String, String, int, List)} determines how the kept
 * concepts relate to one another.</li><li>{@link #patterns(String, String, String, String, int, List, String)}
 * identifies the explanatory pattern of each section.</li><li>{@link #groups(String, String, String, String, int,
 * List)} splits sections into the smallest units of explanation with an axis and a main concept.</li><li>{@link
 * #causes(String, String, String, String, int, List)} extracts the causal and motivational relations for each unit's
 * main concept.</li><li>{@link #relationsOfConcepts(String, String, String, String, int, List)} extracts how a unit's
 * concepts relate to other concepts.</li><li>{@link #properties(String, String, String, String, int, List)} extracts
 * the properties of each unit's main concept.</li><li>{@link #review(String, String, String, String, int, List)}
 * reviews and refines the extracted relations.</li><li>{@link #identify(String, String, String, String, int, List)}
 * recognizes the same concepts across different sections.</li><li>{@link #merge(String, String, String, String, int,
 * List)} aggregates the material for each concept across the whole book.</li><li>{@link #generalize(String, String,
 * String, String, int, List)} creates a hierarchy of generalized concepts.</li><li>{@link #place(String, String,
 * String, String, int, List)} registers concrete frames into the generalized frame registry.</li><li>{@link
 * #relate(String, String, String, String, int, List)} links sections to the generalized frames in the
 * registry.</li><li>{@link #frameNames(String, String, String, String, int, List)} derives names for generalized
 * frames from their concrete instances.</li><li>{@link #frameItems(String, String, String, String, int, List)} lists
 * the items belonging to each generalized frame.</li><li>{@link #items(String, String, String, String, int, List)}
 * provides the specific rules and questions for each concept's concrete frame.</li><li>{@link #collect(String, String,
 * String, String, int, List)} gathers the material for each concept.</li><li>{@link #prune(String, String, int)}
 * removes concepts that have too few associated rules.</li></ul>
 *
 * <p>Each method opens the database and closes it before returning. No state is kept between calls.</p>
 *
 * <p>One instance may serve several workflows at once.</p>
 */
public final class BookStructure {

    private final FileGate files;

    /** Initializes a new instance using the default file gate. */
    public BookStructure() {
        this(FileGate.open());
    }

    public BookStructure(FileGate files) {
        this.files = files;
    }

    /**
     * Extracts the table of contents from the markdown files in {@code dir} and stores it in the H2 database under
     * {@code name}, replacing any existing outline for that book.
     *
     * @param db the H2 database file path, without the {@code .mv.db} extension
     * @param name the name of the book to store
     * @param dir the directory containing the book's markdown files
     * @return a string summarizing the number of files read, the distribution of heading levels, and any notes found
     * in the outline
     *
     * @throws Exception if no markdown files are found under {@code dir} or if a database error occurs
     */
    public String readToc(String db, String name, String dir) throws Exception {
        Path root = Path.of(dir).toAbsolutePath().toRealPath();
        Map<String, String> md = markdown(root);
        if (md.isEmpty()) throw new IllegalArgumentException("no markdown file under " + root);
        BookOutline.Outline outline = BookOutline.withDirectoryChapters(md, BookOutline.read(md));
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        try (BookStore store = BookStore.forWriting(url(dbFile))) {
            store.saveOutline(name, root.toString(), outline);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (BookOutline.Node n : outline.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
        return "read " + md.size() + " markdown file(s) of " + name + ": " + byLevel
                + (outline.notes().isEmpty() ? "" : "; " + String.join("; ", outline.notes()));
    }

    /**
     * Cuts the text of the book into the sections defined by the table of contents stored under {@code name},
     * replacing the existing sections in the H2 database.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @return a string stating the number of sections processed, how many had no text of their own, how many spanned
     * more than one file, and any additional notes
     */
    public String cutSections(String db, String name) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = BookStore.forWriting(url(dbFile))) {
            String dir = store.dirOf(name);
            if (dir == null) throw new IllegalArgumentException("no book " + name + " in " + db + "; read its table of contents first");
            BookSections.Result result = cutAndStore(store, name, dir, markdown(Path.of(dir)));
            long empty = result.sections().stream().filter(x -> x.text().isEmpty()).count();
            long spanning = result.sections().stream().filter(x -> x.files().size() > 1).count();
            return result.sections().size() + " section(s) of " + name + "; " + empty + " with no text of their own; "
                    + spanning + " spanning more than one file" + (result.notes().isEmpty() ? "" : "; " + String.join("; ", result.notes()));
        }
    }

    /**
     * Cuts a book's text into the nodes of its stored outline, splits sections that are too large into smaller pieces,
     * and stores the resulting sections and the updated outline.
     *
     * @param store the database store to use
     * @param name the name of the book
     * @param dir the book's directory
     * @param md the map of markdown file paths to their text content, in the order they are read
     * @return a result containing the new sections and any notes from the splitting process
     * @throws java.sql.SQLException if a database error occurs
     */
    static BookSections.Result cutAndStore(BookStore store, String name, String dir, Map<String, String> md) throws java.sql.SQLException {
        List<BookOutline.Node> outline = new java.util.ArrayList<>(store.outline(name));
        boolean cutBefore = outline.removeIf(n -> BookSections.PIECE.matcher(n.number()).find());
        BookSections.Result whole = BookSections.split(md, outline);
        BookSections.Pieces p = BookSections.splitLarge(outline, whole.sections(), BookSections.limit(whole.sections()));
        if (cutBefore || p.outline().size() != outline.size()) store.saveOutline(name, dir, new BookOutline.Outline(p.outline(), List.of()));
        List<String> notes = new java.util.ArrayList<>(whole.notes());
        notes.addAll(p.notes());
        store.saveSections(name, p.sections());
        return new BookSections.Result(p.sections(), notes);
    }

    /**
     * Executes a single step of a book-processing workflow by sending requests to a chat endpoint for every section of
     * a book.
     *
     * <p>The {@code run} method performs the following:
     * <ul>
     * <li>Takes a {@link BookStore}, a book name, a list of section numbers to process (or an empty list for all
     * remaining sections), a text-writing function, a concurrency limit, and a logging consumer, and returns a summary
     * string of the work performed.</li>
     * </ul>
     *
     * <p>The implementation is called by the various {@link BookStructure} methods to orchestrate the interaction
     * between the H2 database and the local LLM.
     */
    interface Batch {
        String run(BookStore store, String book, List<String> only, Function<String, String> writer, int parallel, Consumer<String> log)
                throws Exception;
    }

    /**
     * Asks a model at a chat endpoint to turn every section of a book into an {@link rst-on-lisp} rule file.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a summary of the results in one line
     */
    public String rules(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2000, parallel, only, SectionTagging::tagAll);
    }

    /**
     * Asks a model at a chat endpoint to list the concepts explained in each section of a book and the sentences that
     * explain them.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel how many sections are asked at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a summary of the concepts and their explanatory sentences found, in one line
     */
    public String concepts(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 400, parallel, only, SectionConcepts::listAll);
    }

    /**
     * Filters the concepts listed for a book by evaluating the role of the sentences that explain them, keeping only
     * those that are significant.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @return a summary of the judging process, in one line
     * @throws Exception if the database file cannot be accessed or the book is not found
     */
    public String judge(String db, String name) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = BookStore.forWriting(url(dbFile))) {
            return ConceptImportance.judgeAll(store, name);
        }
    }

    /**
     * Asks a model at a chat endpoint how the concepts kept in each section relate to one another, specifically
     * identifying if they are a kind of, a part of, or a feature of each other.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message describing the results of the relation extraction
     */
    public String relations(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 600, parallel, only, SectionRelations::relateAll);
    }

    /**
     * Asks a model at a chat endpoint how each section explains its main concept through a sequence, structure,
     * partition, or aspects, and returns a summary of the results.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a summary of the identified patterns for the sections
     */
    public String patterns(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return patterns(db, name, url, model, parallel, only, null);
    }

    /**
     * Determines how each section explains its main concept by identifying its axis (sequence, structure, or set) and
     * pattern, using a file of worked examples to guide the model through difficult cases.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @param examples a file of worked examples, read through the gate; none when {@code null} or blank
     * @return a summary of the identified patterns for the sections processed
     */
    public String patterns(String db, String name, String url, String model, int parallel, List<String> only, String examples)
            throws Exception {
        String text = examples == null || examples.isBlank() ? "" : files.read(Path.of(examples).toAbsolutePath().normalize());
        return ask(db, name, url, model, 600, parallel, only, (s, b, o, w, p, l) -> SectionPattern.askAll(s, b, o, w, p, l, text));
    }

    /**
     * Divides each section of a book into the smallest units of explanation, assigning each unit an axis and a main
     * concept, by asking a model at a chat endpoint.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message stating the result of the operation, or a failure message if the book is not found or
     * parameters are missing
     *
     * @throws IllegalArgumentException if url or model is blank, or if the book is not found in the database
     */
    public String groups(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 800, parallel, only, SectionGroups::askAll);
    }

    /**
     * Asks a model at a chat endpoint to identify the causes and motives for the main concepts of each explanation
     * unit in a book.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a status message describing the result of the operation
     */
    public String causes(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 1200, parallel, only, ConceptCauses::askAll);
    }

    /**
     * Asks a model to identify how the concepts in each unit relate to other concepts, such as being a part of, a
     * member of, or a specific case of another, and stores these relations in the database.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a string summarizing the results of the relation extraction
     */
    public String relationsOfConcepts(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2000, parallel, only, ConceptRelations::askAll);
    }

    /**
     * Extracts the properties of each group's main concept, specifically what it is, what it has, and what it does,
     * and stores them in the database.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a string containing the result of the action, where a success message holds the summary of the work done
     * and a failure indicates an error
     */
    public String properties(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 1200, parallel, only, ConceptProperties::askAll);
    }

    /**
     * Asks a model to review the relations found in the previous step, deciding whether to keep, classify as another
     * kind, reverse, or drop them.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a summary of the review results as a single line of text
     */
    public String review(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 400, parallel, only, SectionReview::reviewAll);
    }

    /** The concrete frame's material: for each concept on the section's list, the rules that say its properties and behaviors ({@link FrameMaterial}). */
    /**
     * Asks a model at a chat endpoint to identify the concepts of a book across its sections.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message describing the result of the identification process
     */
    public String identify(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 400, parallel, only, GeneralFrames::identifyAll);
    }

    /**
     * Combines the material for each concept found in several sections into a single collection of items across the
     * book.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message describing the result of the merge, or a failure message if the process fails
     */
    public String merge(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2500, parallel, only, GeneralFrames::mergeAll);
    }

    /**
     * Produces a list of generalized concepts and their constituent items by moving one level up the concept tree in
     * the hierarchy.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a string describing the generalization process and its results
     */
    public String generalize(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2500, parallel, only, GeneralFrames::generalizeAll);
    }

    /**
     * Registers the book's concrete frames into the registry of generalized frames, one at a time.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message describing the registration progress or a summary of the result
     */
    public String place(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 300, parallel, only, FrameRegistry::placeAll);
    }

    /**
     * Relates the concepts found in the book's sections to the generalized frames in the registry.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a message describing the result of the relation process
     */
    public String relate(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 300, parallel, only, FrameRegistry::relateAll);
    }

    /**
     * Assigns a name to each generalized frame in the registry based on the collection of its concrete frames.
     *
     * Returns a string that summarizes the naming process.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a summary of the naming results
     */
    public String frameNames(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 100, parallel, only, FrameRegistry::nameAll);
    }

    /**
     * Aggregates the material for each generalized frame in the registry by combining the items found across all its
     * concrete frames.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a string describing the result of the aggregation
     */
    public String frameItems(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 2500, parallel, only, FrameRegistry::itemsAll);
    }

    /**
     * Asks a model at a chat endpoint to list the items of each concept's concrete frame, specifically the questions
     * that its gathered rules answer.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param url the chat endpoint, e.g. the GPU broker's {@code /v1/chat/completions}
     * @param model the model the endpoint serves
     * @param parallel the number of sections to ask at once
     * @param only the sections to ask again, by number; empty for every one not done yet
     * @return a string containing the list of items for each concept's concrete frame
     */
    public String items(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 1000, parallel, only, FrameItems::askAll);
    }

    public String collect(String db, String name, String url, String model, int parallel, List<String> only) throws Exception {
        return ask(db, name, url, model, 800, parallel, only, FrameMaterial::collectAll);
    }

    /**
     * Removes from each section's list any concept that has fewer than {@code min} rules.
     *
     * @param db the H2 database file, without {@code .mv.db}
     * @param name the name of the book
     * @param min the threshold number of rules (inclusive)
     * @return a summary of the pruning results in one line
     */
    public String prune(String db, String name, int min) throws Exception {
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = BookStore.forWriting(url(dbFile))) {
            return FrameMaterial.pruneAll(store, name, min);
        }
    }

    private String ask(String db, String name, String url, String model, int maxTokens, int parallel, List<String> only, Batch batch)
            throws Exception {
        if (url == null || url.isBlank() || model == null || model.isBlank()) throw new IllegalArgumentException("url and model are required");
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        files.mayWrite(dbFile);
        try (BookStore store = BookStore.forWriting(url(dbFile))) {
            if (store.dirOf(name) == null) throw new IllegalArgumentException("no book " + name + " in " + db);
            java.util.logging.Logger log = java.util.logging.Logger.getLogger(BookStructure.class.getName());
            return batch.run(store, name, only == null ? List.of() : only, new ChatEndpoint(url, model, maxTokens), Math.max(1, parallel),
                    line -> log.info(name + ": " + line));
        }
    }

    /**
     * Returns a map where each key is a relative path to a markdown file under {@code root} and each value is the
     * file's text content, ordered by path.
     */
    Map<String, String> markdown(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : files.list(root, ".md", Integer.MAX_VALUE)) out.put(root.relativize(p).toString(), files.read(p));
        return out;
    }

    private static String url(Path dbFile) {
        return "jdbc:h2:" + dbFile + ";AUTO_SERVER=TRUE";
    }
}
