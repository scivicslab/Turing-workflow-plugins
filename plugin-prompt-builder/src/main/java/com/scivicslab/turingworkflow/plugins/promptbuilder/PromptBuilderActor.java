package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Runs a {@link PromptBuffer} as an actor and exposes its operations to workflow YAML.
 *
 * <p>Workflow steps call {@code addWarning}/{@code addContext}/{@code addMessage} in sequence and
 * then {@code build}, which puts the assembled prompt in {@code ${result}}. Constraints are stated
 * in the prompt every time rather than left to the model's memory of an earlier turn.</p>
 *
 * <p>This class holds no state. It converts workflow arguments (which arrive as JSON) into plain
 * strings, calls {@link PromptBuffer}, and turns the answer back into an {@link ActionResult}.
 * The state lives in the wrapped {@code PromptBuffer}, which is what {@code ActorRef} expects: an
 * actor is a plain object plus the reference that runs it. Keeping the state here instead — with
 * {@code null} passed as the wrapped object — left the actor reporting itself as not alive and
 * made {@code tell}/{@code ask} against it fail silently.</p>
 */
public class PromptBuilderActor extends IIActorRef<PromptBuffer> {

    /**
     * @param name   this actor's registry name
     * @param system the actor system it belongs to
     */
    public PromptBuilderActor(String name, IIActorSystem system) {
        super(name, new PromptBuffer(), system);
    }

    private PromptBuffer buffer() {
        return object;
    }

    @Action("clear")
    public ActionResult clear(String ignored) {
        buffer().clear();
        return new ActionResult(true, "buffer cleared");
    }

    @Action("addWarning")
    public ActionResult addWarning(String text) {
        String unwrapped = unwrapArg(text);
        if (!buffer().addWarning(unwrapped)) {
            return new ActionResult(false, "addWarning: text must not be blank");
        }
        return new ActionResult(true, "warning added: " + unwrapped);
    }

    @Action("addContext")
    public ActionResult addContext(String text) {
        String unwrapped = unwrapArg(text);
        if (!buffer().addContext(unwrapped)) {
            return new ActionResult(false, "addContext: text must not be blank");
        }
        return new ActionResult(true, "context added: " + unwrapped);
    }

    @Action("addMessage")
    public ActionResult addMessage(String text) {
        if (!buffer().setMessage(unwrapArg(text))) {
            return new ActionResult(false, "addMessage: text must not be blank");
        }
        return new ActionResult(true, "message set");
    }

    @Action("getWarningCount")
    public ActionResult getWarningCount(String ignored) {
        return new ActionResult(true, String.valueOf(buffer().warningCount()));
    }

    @Action("getWarning")
    public ActionResult getWarning(String indexStr) {
        try {
            int index = parseIndex(indexStr);
            String warning = buffer().warningAt(index);
            if (warning == null) {
                return new ActionResult(false, "getWarning: index " + index
                        + " out of range (size=" + buffer().warningCount() + ")");
            }
            return new ActionResult(true, warning);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getWarning: invalid index: " + indexStr);
        }
    }

    @Action("getContextCount")
    public ActionResult getContextCount(String ignored) {
        return new ActionResult(true, String.valueOf(buffer().contextCount()));
    }

    @Action("getContext")
    public ActionResult getContext(String indexStr) {
        try {
            int index = parseIndex(indexStr);
            String context = buffer().contextAt(index);
            if (context == null) {
                return new ActionResult(false, "getContext: index " + index
                        + " out of range (size=" + buffer().contextCount() + ")");
            }
            return new ActionResult(true, context);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getContext: invalid index: " + indexStr);
        }
    }

    @Action("getNextWarning")
    public ActionResult getNextWarning(String ignored) {
        String warning = buffer().nextWarning();
        return warning == null
                ? new ActionResult(false, "getNextWarning: no more warnings")
                : new ActionResult(true, warning);
    }

    @Action("getNextContext")
    public ActionResult getNextContext(String ignored) {
        String context = buffer().nextContext();
        return context == null
                ? new ActionResult(false, "getNextContext: no more contexts")
                : new ActionResult(true, context);
    }

    @Action("resetCursor")
    public ActionResult resetCursor(String ignored) {
        buffer().resetCursor();
        return new ActionResult(true, "cursors reset");
    }

    @Action("getMessage")
    public ActionResult getMessage(String ignored) {
        String message = buffer().getMessage();
        return message == null
                ? new ActionResult(false, "getMessage: no message set")
                : new ActionResult(true, message);
    }

    @Action("build")
    public ActionResult build(String ignored) {
        String prompt = buffer().build();
        return prompt == null
                ? new ActionResult(false, "build failed: addMessage has not been called")
                : new ActionResult(true, prompt);
    }

    /** Workflow arguments arrive as a JSON array or a quoted string; this yields the text itself. */
    private static String unwrapArg(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("[\"") && t.endsWith("\"]")) return t.substring(2, t.length() - 2);
        if (t.startsWith("[") && t.endsWith("]")) {
            int first = t.indexOf('"');
            int last = t.lastIndexOf('"');
            if (first >= 0 && last > first) return t.substring(first + 1, last);
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private int parseIndex(String indexStr) {
        String cleaned = indexStr.trim().replaceAll("[\\[\\]\"\\s]", "");
        return Integer.parseInt(cleaned);
    }
}
