package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.concurrent.ExecutionException;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Provides the actions for a workflow to interact with a writing procedure by posing questions and receiving replies.
 *
 * <ul>
 * <li>{@code question} returns a JSON object {@code {"question": "...", "done": "true"}} containing the next question
 * to be addressed, or {@code {"question": null, "done": "false"}} if no more questions remain.</li>
 * <li>{@code answer} takes a JSON object with a {@code reply} key and submits the text to the work.</li>
 * </ul>
 *
 * <p>A workflow uses the result of {@code question} to branch based on the {@code done} field and to retrieve the
 * question text via {@code ${result.question}.</p>
 *
 * <pre>{@code
 * - actor: this
 *   method: question
 *   arguments: {}
 * - actor: vars
 *   method: putJson
 *   arguments: {path: q, value: "jexl:result"}
 * # then: jexl:state.getString("q.done") == "true"
 * - actor: this
 *   method: answer
 *   arguments: {reply: 'jexl:state.getString("q.reply")'}
 * }</pre>
 *
 * <p>An action fails if the underlying work cannot provide a question or if the answer cannot be processed.</p>
 */
public abstract class QuestionWorkIIAR<T extends QuestionWork> extends IIActorRef<T> {

    protected QuestionWorkIIAR(String actorName, T work, IIActorSystem system) {
        super(actorName, work, system);
    }

    /**
     * Returns a JSON object containing the next question to be addressed.
     *
     * The result is a JSON object with the keys {@code question} and {@code done}. If the work is finished, {@code
     * done} is {@code "true"} and the {@code question} key is absent.
     *
     * An action result is a failure if the underlying work cannot provide a question.
     *
     * <pre>{@code
     * - actor: this
     *   method: question
     * - actor: tree
     *   method: putJson
     *   arguments: {path: q, value: "jexl:result"}
     * # then: jexl:state.getString("q.done") == "true", and the question is state.getString("q.question")
     * }</pre>
     */
    @Action("question")
    public ActionResult question(String args) {
        return perform("could not take the next question", w -> w.question());
    }

    /**
     * Submits the writer's response to the work.
     *
     * <p>The argument is a JSON object containing the key {@code reply} with the text of the response. The action
     * succeeds if the response is accepted, and returns a failure result if the answer could not be processed.
     *
     * @param args a JSON object containing the key {@code reply} whose value is the response text
     */
    @Action("answer")
    public ActionResult answer(String args) {
        JSONObject a = new JSONObject(args);
        String reply = a.isNull("reply") ? null : a.optString("reply", null);
        return perform("could not take the answer", w -> w.answer(reply));
    }

    /**
     * Executes a single step of the work through this actor's mailbox, returning a success result containing the
     * step's output or a failure result containing the provided failure message.
     *
     * @param failure the message to include in the result if the step or the call fails
     * @param step a function that performs the work on the underlying object and returns a string result
     * @return a success result if the step completes successfully, with the message holding the step's return value; a
     * failure result if the step throws an exception, the thread is interrupted, or the call fails, with the message
     * holding the failure reason
     */
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

    /**
     * Returns a {@link JSONObject} containing the parsed arguments from the given string.
     *
     * <p>If the string is null, blank, or does not start with {@code {}, an empty {@link JSONObject} is returned.
     *
     * @param args the JSON string to parse
     */
    protected static JSONObject args(String args) {
        return args == null || args.isBlank() || !args.strip().startsWith("{") ? new JSONObject() : new JSONObject(args);
    }

    /** Returns the value associated with the key, or an empty string if the key is absent or the value is null. */
    protected static String str(JSONObject a, String key) {
        return a.isNull(key) ? "" : a.optString(key, "");
    }

    static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Performs a single operation on the work.
     *
     * @param <T> the type of the work being operated on
     * @return a string representing the result of the operation
     */
    public interface Step<T> {
        String on(T work) throws Exception;
    }
}
