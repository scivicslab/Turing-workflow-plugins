package com.scivicslab.turingworkflow.plugins.rstframe;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The workflow actions of {@link BookFileJob}: {@code next}, {@code open}, {@code question}, {@code answer}, {@code write}. */
public class BookFileJobIIAR extends QuestionWorkIIAR<BookFileJob> {

    public BookFileJobIIAR(String actorName, IIActorSystem system) {
        super(actorName, new BookFileJob(FileGate.of(system)), system);
    }

    /** Args: {@code {dir}}; the message is {@code {"file", "done"}}. */
    @Action("next")
    public ActionResult next(String args) {
        JSONObject a = args(args);
        return perform("could not look through the book", w -> w.next(str(a, "dir")));
    }

    /** Args: {@code {file}}. */
    @Action("open")
    public ActionResult open(String args) {
        JSONObject a = args(args);
        return perform("could not open the book's file", w -> w.open(str(a, "file")));
    }

    /** No args; the message is the rule file written. */
    @Action("write")
    public ActionResult write(String args) {
        return perform("could not write the rule file", BookFileJob::write);
    }
}
