package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Transforms books into a database of explanation units, concepts, and writing prompts by processing section text and
 * rule files through a local LLM.
 *
 * <ul><li>open: takes a JSON object with {@code book}, {@code name}, {@code tree}, and {@code classes} and opens the
 * book's data.</li>
 * <li>question: takes a JSON object with {@code question} and returns the LLM's response.</li>
 * <li>answer: takes a JSON object with {@code answer} and returns the LLM's response.</li>
 * <li>write: takes a JSON object with {@code out} and {@code tree} and returns a success result with the path of the
 * written file.</li></ul>
 *
 * <p>One instance serves one workflow at a time.</p>
 */
public class SlotsJobIIAR extends QuestionWorkIIAR<SlotsJob> {

    public SlotsJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new SlotsJob(FileGate.of(system)), system);
    }

    /**
     * Opens a book for processing by specifying its location and structure.
     *
     * @param args a JSON object containing:
     * <ul>
     * <li>{@code book}: the path to the book's text file</li>
     * <li>{@code name}: the name of the book</li>
     * <li>{@code tree}: the path to the table of contents tree</li>
     * <li>{@code classes}: the path to the rule files</li>
     * </ul>
     *
     * @return an action result that is a success if the book was opened, otherwise a failure with a message stating
     * "could not open the book"
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book", w -> w.open(str(a, "book"), str(a, "name"), str(a, "tree"), str(a, "classes")));
    }

    /**
     * Writes the generated assignments to a file.
     *
     * <ul><li>The action takes a JSON object with {@code out} (the destination file path) and {@code tree} (the data
     * structure to write) and returns a success if the file was written, or a failure with a message if the write
     * operation failed.</li></ul>
     */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the assignments", w -> w.write(str(a, "out"), str(a, "tree")));
    }
}
