package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * An actor for structuring books into a database of concepts, relations, and properties.
 *
 * <ul><li>{@code toc} reads a book's table of contents into the database.</li><li>{@code sections} cuts a book's text
 * into its sections.</li><li>{@code rules}, {@code concepts}, {@code judge}, {@code relations}, and {@code review}
 * process the sections into rule files, concept lists, and relational data.</li><li>{@code patterns} describes how
 * each section lays out its blocks.</li><li>{@code groups}, {@code causes}, {@code relationsOfConcepts}, and {@code
 * properties} extract the smallest units of explanation and their properties.</li><li>{@code collect}, {@code items},
 * {@code place}, {@code relate}, {@code frame-names}, {@code frame-items}, {@code identify}, {@code merge}, and {@code
 * generalize} build and manage the registry of generalized frames.</li><li>{@code prune} removes concepts with fewer
 * than a specified number of rules.</li></ul>
 *
 * <p>One instance may serve several workflows at once.</p>
 *
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["ROOT", "books", "com.scivicslab.turingworkflow.plugins.rstframe.BookStructureIIAR"]
 * - actor: books
 *   method: toc
 *   arguments: {db: 'jexl:state.getString("db")', name: 'jexl:state.getString("name")', book: 'jexl:state.getString("book")'}
 * }</pre>
 */
public class BookStructureIIAR extends IIActorRef<BookStructure> {

    public BookStructureIIAR(String actorName, IIActorSystem system) {
        // the gate of the program's files actor, when it has one: what the workflow may read and write is the program's to decide
        super(actorName, new BookStructure(FileGate.of(system)), system);
    }

    /**
     * Reads a book's table of contents into the database.
     *
     * Returns an action result that is a success with a message stating what was read, or a failure with a message
     * describing the error if the table of contents could not be read.
     *
     * @param args a JSON string containing {@code db} (the H2 database URL), {@code name} (the name of the table of
     * contents to read), and {@code book} (the name of the book)
     */
    @Action("toc")
    public ActionResult toc(String args) {
        try {
            JSONObject a = new JSONObject(args);
            String db = a.getString("db");
            String name = a.getString("name");
            String book = a.getString("book");
            return new ActionResult(true, ask(s -> {
                try {
                    return s.readToc(db, name, book);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }).get());
        } catch (Exception e) {
            return new ActionResult(false, "could not read the table of contents: " + reason(e));
        }
    }

    /**
     * Divides a book's text into its constituent sections in the database.
     *
     * @param args a JSON string containing {@code db} (the database name) and {@code name} (the book name).
     * @return an action result that is a success with a message describing the sections that were cut, or a failure
     * with a message describing the error.
     */
    @Action("sections")
    public ActionResult sections(String args) {
        try {
            JSONObject a = new JSONObject(args);
            String db = a.getString("db");
            String name = a.getString("name");
            return new ActionResult(true, ask(s -> {
                try {
                    return s.cutSections(db, name);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }).get());
        } catch (Exception e) {
            return new ActionResult(false, "could not cut the book into sections: " + reason(e));
        }
    }

    /**
     * Creates rst-on-lisp rule files for each section in the database.
     *
     * @param args a JSON string containing:
     * db: the H2 database name;
     * name: the name of the book;
     * url: the URL for the local LLM service;
     * model: the name of the LLM model to use;
     * parallel: the number of sections to process at once (defaults to 8);
     * sections: a list of section names to process (if empty, all sections are processed).
     *
     * @return a success if the rule files were created for all requested sections, or a failure with a message
     * describing the error.
     */
    @Action("rules")
    public ActionResult rules(String args) {
        return step(args, "could not make the rule files", (b, a) -> b.rules(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Identifies the main concepts explained in each section of the book and records them in the database.
     *
     * @param args a JSON string containing the following keys: {@code db} (the database name), {@code name} (the name
     * of the book), {@code url} (the URL for the local LLM), {@code model} (the model name), {@code parallel} (the
     * number of sections to process at once), and {@code sections} (the names of the sections to process).
     *
     * @return an action result that is a success if the concepts were successfully identified and recorded, with a
     * message containing the count or details; otherwise, a failure with a message explaining why the identification
     * failed.
     */
    @Action("concepts")
    public ActionResult concepts(String args) {
        return step(args, "could not list the concepts", (b, a) -> b.concepts(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Decides which concepts to keep in the database by evaluating the role of their sentences.
     *
     * @param args a JSON string containing {@code db} and {@code name}
     * @return a success result if the concepts were filtered, or a failure result with the reason if the operation
     * could not be completed
     */
    @Action("judge")
    public ActionResult judge(String args) {
        return step(args, "could not judge the concepts", (b, a) -> b.judge(a.getString("db"), a.getString("name")));
    }

    /**
     * Extracts how the concepts in each section relate to one another.
     *
     * Returns an action result that is a success and contains a message if the relations were successfully extracted,
     * or a failure message if the process could not be completed.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}
     */
    @Action("relations")
    public ActionResult relations(String args) {
        return step(args, "could not relate the concepts", (b, a) -> b.relations(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Performs a review of the relations extracted in the previous step.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}.
     *
     * @return an action result that is a success and contains the review text, or a failure with a message describing
     * the error.
     */
    @Action("review")
    public ActionResult review(String args) {
        return step(args, "could not review the relations", (b, a) -> b.review(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Returns an action result that is a success if the patterns of how each section explains its main concept—as a
     * sequence, structure, partition, or aspects—are recorded in the database.
     *
     * @param args a JSON string containing:
     * {@code db} the H2 database name;
     * {@code name} the name of the book;
     * {@code url} the URL for the local LLM;
     * {@code model} the name of the LLM model;
     * {@code parallel} the number of sections to process at once (defaults to 8);
     * {@code sections} a list of section names to process (empty for all sections not yet done);
     * {@code examples} an optional file path to a file of worked examples.
     *
     * @return an action result that is a success if the patterns were recorded, and a failure with a message
     * describing the error otherwise.
     */
    @Action("patterns")
    public ActionResult patterns(String args) {
        return step(args, "could not ask the patterns", (b, a) -> b.patterns(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a), a.optString("examples", "")));
    }

    /**
     * Divides each section into one or more units of explanation, each with an assigned axis and a main concept.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections} (a list of section names or empty for all remaining sections).
     *
     * @return a success result with a message describing the units created, or a failure result if the grouping could
     * not be completed.
     */
    @Action("groups")
    public ActionResult groups(String args) {
        return step(args, "could not group the sections", (b, a) -> b.groups(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Extracts the causal and motivational relationships for each group's main concept.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}.
     *
     * @return an action result that is a success and contains a message if the extraction completed, or a failure with
     * a reason if the process could not be completed.
     */
    @Action("causes")
    public ActionResult causes(String args) {
        return step(args, "could not ask the causes", (b, a) -> b.causes(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Extracts how the concepts in each unit relate to other concepts, either through their membership in a set or
     * structure, or through specific rhetorical relations like comparison or analogy. Returns a success if the
     * relations are recorded in the database, with a message describing the failure otherwise.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}.
     */
    @Action("relationsOfConcepts")
    public ActionResult relationsOfConcepts(String args) {
        return step(args, "could not read the relations of the concepts", (b, a) -> b.relationsOfConcepts(a.getString("db"), a.getString("name"),
                a.getString("url"), a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Extracts the properties of each group's main concept, such as what it is, what it has, or what it does, from the
     * book's sections.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}
     *
     * @return an action result that is a success and contains the extracted properties, or a failure with a message
     * describing the error
     */
    @Action("properties")
    public ActionResult properties(String args) {
        return step(args, "could not ask the properties", (b, a) -> b.properties(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Collects the rules that define each listed concept's properties and behaviors for a concrete frame.
     *
     * Returns an action result that is a success and contains a message if the rules were collected, or a failure with
     * a message describing the error.
     *
     * @param args a JSON string containing {@code db} (database name), {@code name} (frame name), {@code url} (model
     * URL), {@code model} (LLM model name), {@code parallel} (number of sections to process at once), and {@code
     * sections} (list of section names).
     */
    @Action("collect")
    public ActionResult collect(String args) {
        return step(args, "could not collect the rules", (b, a) -> b.collect(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Returns the rules that constitute the concrete frame for each concept, based on the rules collected for it.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, and {@code sections}
     * @return an action result that is a success and whose message holds the items, or a failure if the items could
     * not be retrieved
     */
    @Action("items")
    public ActionResult items(String args) {
        return step(args, "could not ask the items", (b, a) -> b.items(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Registers the book's concrete frames into the registry of generalized frames.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, and {@code sections};
     * if {@code sections} is "reset", the registry is emptied before registration.
     *
     * @return a success result with a message if the frames were placed, or a failure result with the reason if the
     * operation could not be completed.
     */
    @Action("place")
    public ActionResult place(String args) {
        return step(args, "could not place the frames", (b, a) -> b.place(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), 1, sections(a)));
    }

    /**
     * Relates the sections of a book to the generalized frames in the registry.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, and {@code model}
     * @return a success result with a message if the relation was established, or a failure result if the relation
     * could not be completed
     */
    @Action("relate")
    public ActionResult relate(String args) {
        return step(args, "could not relate the frames", (b, a) -> b.relate(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), 1, sections(a)));
    }

    /**
     * Assigns a name to each generalized frame in the registry based on the concrete frames it contains.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, and {@code parallel}
     * (the number of sections to process at once, defaults to 8).
     *
     * @return a success result with a message if the frames were named, or a failure result with a message describing
     * the error.
     */
    @Action("frame-names")
    public ActionResult frameNames(String args) {
        return step(args, "could not name the frames", (b, a) -> b.frameNames(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Returns the items belonging to each generalized frame in the registry.
     *
     * @param args a JSON string containing the following keys: {@code db} (the H2 database path), {@code name} (the
     * name of the book), {@code url} (the URL of the book), {@code model} (the LLM model name), {@code parallel} (the
     * number of sections to process at once), and {@code sections} (a list of section names to include).
     *
     * @return an action result that is a success with a message containing the items, or a failure with a message
     * describing the error.
     */
    @Action("frame-items")
    public ActionResult frameItems(String args) {
        return step(args, "could not ask the frames' items", (b, a) -> b.frameItems(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Identifies the concepts explained in each section of the book and records them in the database. Args: {@code
     * {db, name, url, model, parallel}}. Returns an action result that is a success and contains a message if the
     * concepts were successfully recognized, or a failure message if the process could not be completed.
     */
    @Action("identify")
    public ActionResult identify(String args) {
        return step(args, "could not recognize the concepts", (b, a) -> b.identify(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Combines the material of concepts found in multiple sections into a single set of items.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}, and
     * {@code sections}
     *
     * @return an action result that is a success and whose message holds the result of the merge, or a failure if the
     * merge could not be performed
     */
    @Action("merge")
    public ActionResult merge(String args) {
        return step(args, "could not merge the concepts", (b, a) -> b.merge(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Produces a list of generalized concepts, each one level higher in the concept tree than the current ones, along
     * with their constituent items.
     *
     * @param args a JSON string containing {@code db}, {@code name}, {@code url}, {@code model}, {@code parallel}
     * (number of sections to process at once), and {@code sections} (the sections to include).
     *
     * @return an action result that is a success if the generalization completed, with a message containing the list
     * of generalized concepts and their items; otherwise, a failure with an error message.
     */
    @Action("generalize")
    public ActionResult generalize(String args) {
        return step(args, "could not generalize the concepts", (b, a) -> b.generalize(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * Removes concepts from the list that have fewer than the specified minimum number of rules.
     *
     * @param args a JSON object containing {@code db}, {@code name}, and optionally {@code min} (the minimum number of
     * rules required to keep a concept, defaulting to 2).
     *
     * @return an action result that is a success if the pruning completed, or a failure with a message if an error
     * occurred.
     */
    @Action("prune")
    public ActionResult prune(String args) {
        return step(args, "could not review the list", (b, a) -> {
            String m = a.isNull("min") ? "" : a.get("min").toString().strip();
            return b.prune(a.getString("db"), a.getString("name"), m.isEmpty() ? 2 : Integer.parseInt(m));
        });
    }

    /**
     * Executes a single step of a workflow action by passing the arguments to the provided step. Returns an action
     * result that is a success with the step's message if the step completes, or a failure with a message containing
     * the failure string and the reason for the exception if an error occurs.
     *
     * @param args the JSON text of the action arguments
     * @param failure the prefix for the error message in a failed result
     * @param s the logic to execute for the step
     */
    private ActionResult step(String args, String failure, Step s) {
        try {
            JSONObject a = new JSONObject(args);
            return new ActionResult(true, ask(b -> {
                try {
                    return s.on(b, a);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }).get());
        } catch (Exception e) {
            return new ActionResult(false, failure + ": " + reason(e));
        }
    }

    private interface Step {
        String on(BookStructure books, JSONObject args) throws Exception;
    }

    /**
     * Determines the number of sections to process in a single batch.
     *
     * @param a the JSON object containing the arguments for the action
     * @return the number of sections to process at once, which is 8 if the parallel key is missing or empty
     */
    private static int parallel(JSONObject a) {
        String p = a.isNull("parallel") ? "" : a.get("parallel").toString().strip();
        return p.isEmpty() ? 8 : Integer.parseInt(p);
    }

    /**
     * Returns a list of section names extracted from the provided JSON object.
     *
     * The list contains the names found in the {@code sections} field, split by spaces or commas. If the {@code
     * sections} field is missing or empty, an empty list is returned.
     */
    private static java.util.List<String> sections(JSONObject a) {
        String s = a.isNull("sections") ? "" : a.get("sections").toString().strip();
        return s.isEmpty() ? java.util.List.of() : java.util.List.of(s.split("[\\s,]+"));
    }

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}
