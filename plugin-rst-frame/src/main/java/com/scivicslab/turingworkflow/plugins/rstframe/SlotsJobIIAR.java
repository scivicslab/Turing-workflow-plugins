package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link SlotsJob}: {@code open}, {@code question}, {@code answer}, {@code write}. */
public class SlotsJobIIAR extends QuestionWorkIIAR<SlotsJob> {

    public SlotsJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new SlotsJob(FileGate.of(system)), system);
    }

    /** Args: {@code {book, name, tree, classes}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book", w -> w.open(str(a, "book"), str(a, "name"), str(a, "tree"), str(a, "classes")));
    }

    /** Args: {@code {out, tree}}; the message is the file written. */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the assignments", w -> w.write(str(a, "out"), str(a, "tree")));
    }
}
