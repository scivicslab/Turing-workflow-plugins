package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.concurrent.ExecutionException;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link FrameFilling}. The rule file goes in as {@code file} and comes back as the message
 * (or as {@code file} of a JSON message), for the workflow to keep:
 *
 * <pre>{@code
 * - actor: filler
 *   method: next
 *   arguments: {file: 'jexl:state.getString("cur")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: blank, value: "jexl:result"}
 * # ... ask the writer, keep the reply ...
 * - actor: filler
 *   method: fill
 *   arguments: {file: 'jexl:state.getString("cur")', reply: 'jexl:state.getString("reply")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: cur, value: "jexl:result"}
 * }</pre>
 */
public class FrameFillingIIAR extends IIActorRef<FrameFilling> {

    public FrameFillingIIAR(String actorName, IIActorSystem system) {
        super(actorName, new FrameFilling(FileGate.of(system)), system);
    }

    /** Args: {@code {file}}; the message is the blank, {@code {name, instruction, sample, frame, codeKind, include, kind}}. */
    @Action("next")
    public ActionResult next(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not take the next blank", f -> f.next(opt(a, "file")));
    }

    /** Args: {@code {request, framesRoot, frameDir, works, max}}; remembers the examples for {@code prompt}. */
    @Action("examples")
    public ActionResult examples(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not find examples",
                f -> f.examples(opt(a, "request"), opt(a, "framesRoot"), opt(a, "frameDir"), opt(a, "works"), opt(a, "max")));
    }

    /** Args: {@code {file, subject, names, language, maxItems}}; the message is the prompt. */
    @Action("prompt")
    public ActionResult prompt(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not build the prompt",
                f -> f.prompt(opt(a, "file"), opt(a, "subject"), opt(a, "names"), opt(a, "language"), opt(a, "maxItems")));
    }

    /** Args: {@code {file, reply}}; the message is the rule file. */
    @Action("fill")
    public ActionResult fill(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not fill the blank", f -> f.fill(opt(a, "file"), opt(a, "reply")));
    }

    /** Args: {@code {file, frameText}}; the message is the rule file. */
    @Action("include")
    public ActionResult include(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not include the child frame", f -> f.include(opt(a, "file"), opt(a, "frameText")));
    }

    /** Args: {@code {file, reply}}; the message is the rule file. */
    @Action("fillItems")
    public ActionResult fillItems(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not fill the list", f -> f.fillItems(opt(a, "file"), opt(a, "reply")));
    }

    /** Args: {@code {file, frameText, reply, maxItems}}; the message is {@code {file, names}}. */
    @Action("expand")
    public ActionResult expand(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not expand the list",
                f -> f.expand(opt(a, "file"), opt(a, "frameText"), opt(a, "reply"), opt(a, "maxItems")));
    }

    /** Args: {@code {file, reply, out}}; the message is {@code {file, code, path}}. */
    @Action("fillCode")
    public ActionResult fillCode(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not write the code", f -> f.fillCode(opt(a, "file"), opt(a, "reply"), opt(a, "out")));
    }

    /** @return the value, or {@code null} when absent or null */
    private static String opt(JSONObject a, String key) {
        return a.isNull(key) ? null : a.get(key).toString();
    }

    private ActionResult perform(String failure, QuestionWorkIIAR.Step<FrameFilling> step) {
        try {
            return new ActionResult(true, ask(f -> {
                try {
                    return step.on(f);
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
            return new ActionResult(false, failure + ": " + QuestionWorkIIAR.reason(e));
        }
    }
}
