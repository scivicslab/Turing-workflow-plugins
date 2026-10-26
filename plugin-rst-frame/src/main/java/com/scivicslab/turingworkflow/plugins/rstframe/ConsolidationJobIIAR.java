package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Transforms a book's section-level rule files into a smaller, consolidated tree of explanation units by grouping
 * blocks into units of sequence, structure, or set, and extracting their core concepts, causal relations, and
 * properties.
 *
 * <ul>
 * <li>{@code open} takes a JSON object with {@code classes} and {@code input} paths and opens the specified
 * files.</li>
 * <li>{@code question} asks a local LLM a question about the data and returns the answer.</li>
 * <li>{@code answer} processes an LLM response and returns the result.</li>
 * <li>{@code write} takes a JSON object with {@code out} and {@code input} paths, writes the consolidated tree to the
 * output file, and closes the files.</li>
 * </ul>
 *
 * <p>The files opened by {@code open} remain open until {@code write} is called.</p>
 *
 * <p>One instance serves one workflow at a time.</p>
 *
 * <p>If {@code open} or {@code write} fails, the action result is a failure with a message describing the error.</p>
 */
public class ConsolidationJobIIAR extends QuestionWorkIIAR<ConsolidationJob> {

    public ConsolidationJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ConsolidationJob(FileGate.of(system)), system);
    }

    /**
     * Opens the specified classes and input for processing.
     *
     * @param args a JSON string containing {@code classes} (the path to the classes to be opened) and {@code input}
     * (the path to the input file).
     *
     * @return a success result if the files are opened, or a failure result with the message "could not open the
     * classes" if the files cannot be opened.
     */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the classes", w -> w.open(str(a, "classes"), str(a, "input")));
    }

    /**
     * Writes the smaller tree to a file and closes the classes.
     *
     * <p>Returns an action result that is a success with a message containing the path of the written file, or a
     * failure if the write operation could not be completed.</p>
     *
     * <p>The arguments are a JSON object containing:
     * <ul>
     * <li>{@code out}: the path to the output file</li>
     * <li>{@code input}: the source data or tree to be written</li>
     * </ul>
     * </p>
     *
     * @param args a JSON object with {@code out} and {@code input}
     * @return an action result that is a success with the written file path as the message, or a failure if the write
     * fails
     */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the smaller tree", w -> {
            try {
                return w.write(str(a, "out"), str(a, "input"));
            } finally {
                w.close();
            }
        });
    }
}
