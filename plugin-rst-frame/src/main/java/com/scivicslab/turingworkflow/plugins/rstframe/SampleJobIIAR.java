package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Provides the workflow actions for {@link SampleJob} to transform books into a database of explanation units, causal
 * relations, and properties.
 *
 * <ul>
 * <li>{@code open} opens a sub-section of a book using the specified book, heading, name, and samples.</li>
 * <li>{@code question} asks a local LLM a question and returns the answer.</li>
 * <li>{@code answer} processes an LLM answer and returns the result.</li>
 * <li>{@code write} writes the generated samples to the database.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows.</p>
 */
public class SampleJobIIAR extends QuestionWorkIIAR<SampleJob> {

    public SampleJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new SampleJob(FileGate.of(system)), system);
    }

    /**
     * Opens a sub-section of a book for processing, using the provided parameters to identify the book, heading, and
     * specific name or samples.
     *
     * @param args a JSON string containing {@code book}, {@code heading}, {@code name}, and {@code samples}
     * @return a success if the sub-section was opened, or a failure with a message stating "could not open the
     * sub-section"
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the sub-section",
                w -> w.open(str(a, "book"), str(a, "heading"), str(a, "name"), str(a, "samples")));
    }

    /**
     * Writes the provided samples to the current sub-section.
     *
     * @param args a JSON string containing the key {@code samples} which holds the list of samples to be written.
     * @return an action result that is a success if the samples were written, with a message indicating whether a
     * {@code new <name>} was created, the {@code same <name>} was used, or if the operation was a {@code skip: <why>};
     * otherwise, a failure result with a message stating "could not write the sample".
     */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the sample", w -> w.write(str(a, "samples")));
    }
}
