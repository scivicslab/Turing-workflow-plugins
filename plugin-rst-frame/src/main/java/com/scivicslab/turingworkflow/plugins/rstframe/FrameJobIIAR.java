package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Turns books into a database of explanation units, their main concepts, causal relations, properties, and writing
 * prompts by querying a local LLM and storing results in an H2 database.
 *
 * <ul><li>{@code open} takes a JSON object with {@code samples} and {@code name} and opens the specified
 * sample.</li><li>{@code question} takes a JSON object with {@code samples} and {@code name} and performs the
 * multi-stage extraction process of dividing sections into units, identifying concepts, and extracting relations and
 * properties.</li><li>{@code answer} takes a JSON object with {@code samples} and {@code name} and returns the LLM's
 * response to a specific question.</li><li>{@code write} takes a JSON object with {@code dir} and writes the generated
 * frames to the specified directory.</li></ul>
 *
 * <p>One instance of this actor may serve several workflows at once.</p>
 */
public class FrameJobIIAR extends QuestionWorkIIAR<FrameJob> {

    public FrameJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new FrameJob(FileGate.of(system)), system);
    }

    /**
     * Opens a sample book for processing, using the directory and name provided in the arguments.
     *
     * <p>The arguments must be a JSON object containing {@code samples} (the directory path) and {@code name} (the
     * book name).
     *
     * <p>Returns an action result that is a success if the book was opened, and a failure with the message "could not
     * open the sample" otherwise.
     *
     * @param args a JSON string containing {@code samples} as the directory path and {@code name} as the book name.
     * @return an action result indicating whether the book was successfully opened.
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the sample", w -> w.open(str(a, "samples"), str(a, "name")));
    }

    /**
     * Writes the generated frame data to a file in the specified directory.
     *
     * <ul><li>{@code write}: Returns an action result that is a success if the frame was written to the directory
     * specified by {@code dir}, and is not a success if the write fails.</li></ul>
     *
     * <pre>{@code
     * actor: frame-job
     * method: write
     * arguments: {dir: "/path/to/output"}
     * }</pre>
     */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the frame", w -> w.write(str(a, "dir")));
    }
}
