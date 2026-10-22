package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link ConsolidationJob}: {@code open}, {@code question}, {@code answer}, {@code write}. */
public class ConsolidationJobIIAR extends QuestionWorkIIAR<ConsolidationJob> {

    public ConsolidationJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ConsolidationJob(FileGate.of(system)), system);
    }

    /** Args: {@code {classes, input}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the classes", w -> w.open(str(a, "classes"), str(a, "input")));
    }

    /** Args: {@code {out, input}}; the message is the file written. The classes are closed after it. */
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
