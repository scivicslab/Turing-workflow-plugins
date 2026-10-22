package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link ConceptTreeJob}: {@code open}, {@code question}, {@code answer}, {@code write}. */
public class ConceptTreeJobIIAR extends QuestionWorkIIAR<ConceptTreeJob> {

    public ConceptTreeJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ConceptTreeJob(FileGate.of(system)), system);
    }

    /** Args: {@code {book, name, given}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book", w -> w.open(str(a, "book"), str(a, "name"), str(a, "given")));
    }

    /** Args: {@code {out, book}}; the message is the file written. */
    @Action("write")
    public ActionResult write(String args) {
        JSONObject a = args(args);
        return perform("could not write the concept tree", w -> w.write(str(a, "out"), str(a, "book")));
    }
}
