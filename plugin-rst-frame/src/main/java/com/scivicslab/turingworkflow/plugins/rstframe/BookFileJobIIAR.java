package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Transforms books into a database of explanation units, their causal relations, properties, and writing prompts by
 * processing section text and rule files through a local LLM.
 *
 * <ul>
 * <li>{@code next} moves to the next section in the book, taking a directory path as {@code {dir}} and returning a
 * success message {@code {"file", "done"}}.</li>
 * <li>{@code open} opens a specific book file, taking a file path as {@code {file}}.</li>
 * <li>{@code write} writes the generated rule files to the database.</li>
 * </ul>
 *
 * <p>One instance of this actor may serve several workflows at once.</p>
 */
public class BookFileJobIIAR extends QuestionWorkIIAR<BookFileJob> {

    public BookFileJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new BookFileJob(FileGate.of(system)), system);
    }

    /**
     * Moves the workflow to the next book in the directory.
     *
     * @param args a JSON object containing the key {@code dir} with the directory path as its value.
     * @return a success result if the next book was found and prepared, with a message {@code {"file", "done"}}; a
     * failure result if the directory could not be searched.
     */
    @Action("next")
    public ActionResult next(String args) {
        JSONObject a = args(args);
        return perform("could not look through the book", w -> w.next(str(a, "dir")));
    }

    /**
     * Opens the book's file specified by the argument.
     *
     * @param args the file path as a string.
     * @return a success result if the file was opened, or a failure result with a message if the file could not be
     * opened.
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book's file", w -> w.open(str(a, "file")));
    }

    /**
     * Writes the rule file for the current section to the H2 database.
     *
     * <p>Returns an action result that is a success and contains the name of the written rule file as its message, or
     * is not a success if the database write fails.</p>
     *
     * @param args unused
     */
    @Action("write")
    public ActionResult write(String args) {
        return perform("could not write the rule file", BookFileJob::write);
    }
}
