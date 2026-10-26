package com.scivicslab.turingworkflow.plugins.text;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Performs text processing and comparison tasks, returning results as action messages for use in workflows. One actor
 * instance serves every workflow of a program.
 *
 * <ul>
 * <li><code>joinLines</code>: Takes a JSON list {@code {list}} and returns its lines as a single string.</li>
 * <li><code>sameAsLast</code>: Compares a new verdict against a previous one using {@code {verdict, last}} and returns
 * a success if they match.</li>
 * <li><code>compare</code>: Compares two texts using {@code {before, after}} and returns {@code ACCEPT} or {@code
 * REVISE: ...}.</li>
 * <li><code>takeFrontMatter</code>: Extracts the front matter from a text using {@code {text}} and returns a JSON
 * string containing {@code {head, body}}.</li>
 * <li><code>joinFrontMatter</code>: Recombines a head and a body using {@code {head, body}} and returns the whole
 * document.</li>
 * </ul>
 *
 * <pre>{@code
 * - actor: texts
 *   method: compare
 *   arguments: {before: 'jexl:state.getString("original")', after: 'jexl:state.getString("text")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: verdict, value: "jexl:result"}
 * }</pre>
 */
public class TextsIIAR extends IIActorRef<Texts> {

    public TextsIIAR(String actorName, IIActorSystem system) {
        super(actorName, new Texts(), system);
    }

    /**
     * Returns a single string consisting of the lines in the provided list.
     *
     * @param args a JSON object containing a {@code list} key, which is a JSON list of strings.
     * @return a success result with the joined lines as the message, or a failure result if the input is invalid or
     * the operation is interrupted.
     */
    @Action("joinLines")
    public ActionResult joinLines(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not join the lines", t -> t.joinLines(opt(a, "list")));
    }

    /**
     * Returns an action result that is a success if the verdict matches the last one, and a failure otherwise.
     *
     * The result is a success with the message "the same verdict as last time" if the verdict in the arguments matches
     * the last verdict stored by the actor, and a failure with the message "a different verdict" if they do not match.
     *
     * If the comparison is interrupted, it returns a failure with the message "interrupted". If the comparison fails
     * due to an execution error, it returns a failure with a message containing the reason for the error.
     *
     * @param args a JSON object containing the keys {@code verdict} and {@code last}, representing the current verdict
     * and the previous verdict to compare against.
     *
     * @return an action result that is a success if the two verdicts are equal, and a failure if they are different or
     * if an error occurred.
     */
    @Action("sameAsLast")
    public ActionResult sameAsLast(String args) {
        JSONObject a = new JSONObject(args);
        try {
            boolean same = ask(t -> t.sameAsLast(opt(a, "verdict"), opt(a, "last"))).get();
            return new ActionResult(same, same ? "the same verdict as last time" : "a different verdict");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, "could not compare the verdicts: " + reason(e));
        }
    }

    /**
     * Compares two texts and returns a verdict.
     *
     * @param args a JSON object containing the keys `before` and `after` representing the two texts to compare.
     * @return an action result that succeeds with a message of `ACCEPT` if the texts are considered equivalent, or
     * fails with a message of `REVISE: <reason>` if they are not.
     */
    @Action("compare")
    public ActionResult compare(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not compare the texts", t -> t.compare(opt(a, "before"), opt(a, "after")));
    }

    /**
     * Extracts the front matter from a text, returning a JSON object containing the {@code head} and the {@code body}.
     *
     * @param args a JSON object containing the key {@code text} which holds the document string to be processed.
     * @return an action result that is a success with a message containing a JSON object with the {@code head} and
     * {@code body} keys, or a failure if the text could not be processed.
     */
    @Action("takeFrontMatter")
    public ActionResult takeFrontMatter(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not take the front matter", t -> t.takeFrontMatter(opt(a, "text")));
    }

    /**
     * Combines a document's head and body into a single text.
     *
     * @param args a JSON object containing the keys {@code head} and {@code body}
     * @return an action result that is a success with the combined document as its message, or a failure if the front
     * matter could not be put back
     */
    @Action("joinFrontMatter")
    public ActionResult joinFrontMatter(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not put the front matter back", t -> t.joinFrontMatter(opt(a, "head"), opt(a, "body")));
    }

    private ActionResult perform(String failure, Function<Texts, String> f) {
        try {
            return new ActionResult(true, ask(f::apply).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, failure + ": interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, failure + ": " + reason(e));
        }
    }

    private static String opt(JSONObject a, String key) {
        return a.isNull(key) ? null : a.get(key).toString();
    }

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
