package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link FrameJob}: {@code open}, {@code question}, {@code answer}, {@code write}. */
public class FrameJobIIAR extends QuestionWorkIIAR<FrameJob> {

    public FrameJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new FrameJob(FileGate.of(system)), system);
    }

    /** Args: {@code {samples, name}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the sample", w -> w.open(str(a, "samples"), str(a, "name")));
    }

    /** Args: {@code {dir}}; the message is the frame's file. */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the frame", w -> w.write(str(a, "dir")));
    }
}
