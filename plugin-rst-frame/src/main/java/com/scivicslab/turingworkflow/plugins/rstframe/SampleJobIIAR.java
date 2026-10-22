package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link SampleJob}: {@code open}, {@code question}, {@code answer}, {@code write}. */
public class SampleJobIIAR extends QuestionWorkIIAR<SampleJob> {

    public SampleJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new SampleJob(FileGate.of(system)), system);
    }

    /** Args: {@code {book, heading, name, samples}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the sub-section",
                w -> w.open(str(a, "book"), str(a, "heading"), str(a, "name"), str(a, "samples")));
    }

    /** Args: {@code {samples}}; the message is what was done ({@code new <name>}, {@code same <name>}, {@code skip: <why>}). */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the sample", w -> w.write(str(a, "samples")));
    }
}
