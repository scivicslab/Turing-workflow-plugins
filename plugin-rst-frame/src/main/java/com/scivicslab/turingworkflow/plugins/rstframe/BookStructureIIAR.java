package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link BookStructure}: {@code toc} and {@code sections}, then {@code rules}, {@code concepts},
 * {@code judge}, {@code relations} and {@code review}, one per step; {@code patterns}, how each section lays out its
 * blocks; then {@code collect} and {@code prune} for each
 * section's concrete frame. A workflow creates it with
 * {@code loader.createChild} and calls it by name, so a sub-workflow called with {@code this.call} can call it too.
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

    /** Reads a book's table of contents into the database. Args: {@code {db, name, book}}; the message says what was read. */
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

    /** Cuts a book's text into its sections in the database. Args: {@code {db, name}}; the message says what was cut. */
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

    /** Step 3: each section as an rst-on-lisp rule file. Args: {@code {db, name, url, model, parallel, sections}}. */
    @Action("rules")
    public ActionResult rules(String args) {
        return step(args, "could not make the rule files", (b, a) -> b.rules(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /** Step 4: the concepts each section explains. Args: {@code {db, name, url, model, parallel, sections}}. */
    @Action("concepts")
    public ActionResult concepts(String args) {
        return step(args, "could not list the concepts", (b, a) -> b.concepts(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /** Step 5: which concepts to keep, by the role of their sentences; no model. Args: {@code {db, name}}. */
    @Action("judge")
    public ActionResult judge(String args) {
        return step(args, "could not judge the concepts", (b, a) -> b.judge(a.getString("db"), a.getString("name")));
    }

    /** Step 6: how each section's concepts relate. Args: {@code {db, name, url, model, parallel, sections}}. */
    @Action("relations")
    public ActionResult relations(String args) {
        return step(args, "could not relate the concepts", (b, a) -> b.relations(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /** Step 7: a review of step 6's relations. Args: {@code {db, name, url, model, parallel, sections}}. */
    @Action("review")
    public ActionResult review(String args) {
        return step(args, "could not review the relations", (b, a) -> b.review(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /**
     * How each section explains its main concept: sequence, structure, partition or aspects. Args:
     * {@code {db, name, url, model, parallel, sections, examples}}, {@code examples} a file of worked examples or blank.
     */
    @Action("patterns")
    public ActionResult patterns(String args) {
        return step(args, "could not ask the patterns", (b, a) -> b.patterns(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a), a.optString("examples", "")));
    }

    /** A concrete frame's material: the rules that say each listed concept's properties and behaviors. Args: {@code {db, name, url, model, parallel, sections}}. */
    @Action("collect")
    public ActionResult collect(String args) {
        return step(args, "could not collect the rules", (b, a) -> b.collect(a.getString("db"), a.getString("name"), a.getString("url"),
                a.getString("model"), parallel(a), sections(a)));
    }

    /** Takes off the list a concept that got fewer than {@code min} rules (2 when not given); no model. Args: {@code {db, name, min}}. */
    @Action("prune")
    public ActionResult prune(String args) {
        return step(args, "could not review the list", (b, a) -> {
            String m = a.isNull("min") ? "" : a.get("min").toString().strip();
            return b.prune(a.getString("db"), a.getString("name"), m.isEmpty() ? 2 : Integer.parseInt(m));
        });
    }

    /** One step of the book, through this actor's mailbox; an exception becomes a failure naming its reason. */
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

    /** @return how many sections are asked at once; 8 when not given */
    private static int parallel(JSONObject a) {
        String p = a.isNull("parallel") ? "" : a.get("parallel").toString().strip();
        return p.isEmpty() ? 8 : Integer.parseInt(p);
    }

    /** @return the sections named, separated by spaces or commas; none for every section not done yet */
    private static java.util.List<String> sections(JSONObject a) {
        String s = a.isNull("sections") ? "" : a.get("sections").toString().strip();
        return s.isEmpty() ? java.util.List.of() : java.util.List.of(s.split("[\\s,]+"));
    }

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}
