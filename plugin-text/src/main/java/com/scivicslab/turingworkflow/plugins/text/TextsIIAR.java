package com.scivicslab.turingworkflow.plugins.text;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link Texts}. The texts go in as arguments and the result comes back as the message, for
 * the workflow to keep. Holding nothing, one actor serves every workflow of a program, which registers it at start-up
 * (a workflow run alone creates it with {@code loader.createChild}):
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

    /** Args: {@code {list}}, a JSON list ({@code 'jexl:state.select("done").toString()'}); the message is its lines, なし for none. */
    @Action("joinLines")
    public ActionResult joinLines(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not join the lines", t -> t.joinLines(opt(a, "list")));
    }

    /** Args: {@code {verdict, last}}; succeeds iff the verdict says the same as the last one. */
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

    /** Args: {@code {before, after}}; the message is {@code ACCEPT} or {@code REVISE: ...}. */
    @Action("compare")
    public ActionResult compare(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not compare the texts", t -> t.compare(opt(a, "before"), opt(a, "after")));
    }

    /** Args: {@code {text}}; the message is {@code {head, body}}. */
    @Action("takeFrontMatter")
    public ActionResult takeFrontMatter(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not take the front matter", t -> t.takeFrontMatter(opt(a, "text")));
    }

    /** Args: {@code {head, body}}; the message is the whole document. */
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
