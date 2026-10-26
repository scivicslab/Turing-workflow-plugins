package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Transforms books into a structured database of explanation units, their core concepts, and their relationships by
 * querying a local LLM.
 *
 * <ul>
 * <li>{@code open} opens a specific book in the H2 database using its name and a given identifier.</li>
 * <li>{@code write} saves the generated concept tree data to a specified output file.</li>
 * <li>{@code question} asks the local LLM a single question about a specific part of the book's structure or
 * concepts.</li>
 * <li>{@code answer} processes the LLM's response and records the result in the database.</li>
 * </ul>
 *
 * <p>One instance of this actor may serve several workflows at once.</p>
 */
public class ConceptTreeJobIIAR extends QuestionWorkIIAR<ConceptTreeJob> {

    public ConceptTreeJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ConceptTreeJob(FileGate.of(system)), system);
    }

    /**
     * Opens a book for processing by specifying its title, a name for the task, and a given context.
     *
     * <p>The action succeeds if the book can be located and opened with the provided parameters, and returns a failure
     * result with a message if the book cannot be opened.</p>
     *
     * @param args a JSON object containing {@code book} (the book title), {@code name} (a name for the task), and
     * {@code given} (the context).
     *
     * @return an action result that is a success if the book is opened, or a failure with a message if it could not be
     * opened.
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book", w -> w.open(str(a, "book"), str(a, "name"), str(a, "given")));
    }

    /**
     * Writes the concept tree to a file, returning a success result with the written file's path in the message, or a
     * failure result if the write fails.
     *
     * @param args a JSON string containing {@code out} (the destination file path) and {@code book} (the book
     * identifier).
     *
     * @return a success result if the file was written, with the file path as the message; otherwise, a failure
     * result.
     */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the concept tree", w -> w.write(str(a, "out"), str(a, "book")));
    }
}
