package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.concurrent.ExecutionException;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The actions every {@link QuestionWork} has: {@code question} and {@code answer}. A workflow keeps what
 * {@code question} answers and branches on its {@code done}:
 *
 * <pre>{@code
 * - actor: tree
 *   method: question
 * - actor: this
 *   method: putJson
 *   arguments: {path: q, value: "jexl:result"}
 * # then: jexl:state.getString("q.done") == "true", and the question is state.getString("q.question")
 * - actor: tree
 *   method: answer
 *   arguments: {reply: 'jexl:state.getString("reply")'}
 * }</pre>
 */
public abstract class QuestionWorkIIAR<T extends QuestionWork> extends IIActorRef<T> {

    protected QuestionWorkIIAR(String actorName, T work, IIActorSystem system) {
        super(actorName, work, system);
    }

    /** The next question. No args; the message is {@code {"question", "done"}}. */
    @Action("question")
    public ActionResult question(String args) {
        return perform("could not take the next question", w -> w.question());
    }

    /** Hands the writer's reply to the work. Args: {@code {reply}}. */
    @Action("answer")
    public ActionResult answer(String args) {
        JSONObject a = new JSONObject(args);
        String reply = a.isNull("reply") ? null : a.optString("reply", null);
        return perform("could not take the answer", w -> w.answer(reply));
    }

    /** One call on the work, through this actor's mailbox; an exception becomes a failure naming its reason. */
    protected ActionResult perform(String failure, Step<T> step) {
        try {
            return new ActionResult(true, ask(w -> {
                try {
                    return step.on(w);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, failure + ": interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, failure + ": " + reason(e));
        }
    }

    /** @return the args as an object; empty for none */
    protected static JSONObject args(String args) {
        return args == null || args.isBlank() || !args.strip().startsWith("{") ? new JSONObject() : new JSONObject(args);
    }

    /** @return the value, or {@code ""} when absent or null */
    protected static String str(JSONObject a, String key) {
        return a.isNull(key) ? "" : a.optString(key, "");
    }

    static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** One call on the work. */
    public interface Step<T> {
        String on(T work) throws Exception;
    }
}
