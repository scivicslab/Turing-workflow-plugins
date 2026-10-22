package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link BookStructure}: {@code toc} and {@code sections}. A workflow creates it with
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
        super(actorName, new BookStructure(), system);
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

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}
