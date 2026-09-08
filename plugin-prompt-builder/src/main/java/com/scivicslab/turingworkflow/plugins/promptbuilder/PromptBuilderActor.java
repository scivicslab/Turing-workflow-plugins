package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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

    /**
     * A piece of text to add to the buffer.
     *
     * @param text the text to add
     */
    public record TextArgs(@NotNull String text) {}

    /**
     * A position in one of the buffer's lists.
     *
     * @param index zero-based position
     */
    public record IndexArgs(@NotNull Integer index) {}

    @Action("clear")
    public ActionResult clear(String ignored) {
        buffer().clear();
        return new ActionResult(true, "buffer cleared");
    }

    @Action(value = "addWarning", argsType = TextArgs.class)
    public ActionResult addWarning(TextArgs args) {
        String unwrapped = args.text();
        if (!buffer().addWarning(unwrapped)) {
            return new ActionResult(false, "addWarning: text must not be blank");
        }
        return new ActionResult(true, "warning added: " + unwrapped);
    }

    @Action(value = "addContext", argsType = TextArgs.class)
    public ActionResult addContext(TextArgs args) {
        String unwrapped = args.text();
        if (!buffer().addContext(unwrapped)) {
            return new ActionResult(false, "addContext: text must not be blank");
        }
        return new ActionResult(true, "context added: " + unwrapped);
    }

    @Action(value = "addMessage", argsType = TextArgs.class)
    public ActionResult addMessage(TextArgs args) {
        if (!buffer().setMessage(args.text())) {
            return new ActionResult(false, "addMessage: text must not be blank");
        }
        return new ActionResult(true, "message set");
    }

    @Action("getWarningCount")
    public ActionResult getWarningCount(String ignored) {
        return new ActionResult(true, String.valueOf(buffer().warningCount()));
    }

    @Action(value = "getWarning", argsType = IndexArgs.class)
    public ActionResult getWarning(IndexArgs args) {
        try {
            int index = args.index();
            String warning = buffer().warningAt(index);
            if (warning == null) {
                return new ActionResult(false, "getWarning: index " + index
                        + " out of range (size=" + buffer().warningCount() + ")");
            }
            return new ActionResult(true, warning);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getWarning: invalid index: " + args.index());
        }
    }

    @Action("getContextCount")
    public ActionResult getContextCount(String ignored) {
        return new ActionResult(true, String.valueOf(buffer().contextCount()));
    }

    @Action(value = "getContext", argsType = IndexArgs.class)
    public ActionResult getContext(IndexArgs args) {
        try {
            int index = args.index();
            String context = buffer().contextAt(index);
            if (context == null) {
                return new ActionResult(false, "getContext: index " + index
                        + " out of range (size=" + buffer().contextCount() + ")");
            }
            return new ActionResult(true, context);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getContext: invalid index: " + args.index());
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

}
