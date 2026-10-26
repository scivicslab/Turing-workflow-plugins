package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Assembles structured prompts for LLMs by accumulating constraints, context, and a task message into a single
 * formatted string.
 *
 * <ul><li>{@code addWarning} adds a text item to the [Constraints] section.</li><li>{@code addContext} adds a text
 * item to the [Background] section.</li><li>{@code addMessage} sets the [Message] section.</li><li>{@code build}
 * returns the complete formatted prompt as a single string in {@code ${result}}.</li><li>{@code clear} resets all
 * sections and the buffer.</li><li>{@code getWarningCount} and {@code getContextCount} return the number of items in
 * each section.</li><li>{@code getWarning} and {@code getContext} return the item at the specified zero-based
 * index.</li><li>{@code getNextWarning} and {@code getNextContext} return the next available item in each
 * section.</li><li>{@code resetCursor} resets the iteration cursors for warnings and contexts.</li><li>{@code
 * getMessage} returns the current task message.</li><li>{@code buildMessages} returns a JSON-formatted array of
 * messages containing the constraints, background, and a user message containing the provided history and
 * task.</li></ul>
 *
 * <p>The actor holds a {@link PromptBuffer} that maintains the state of the current prompt being built. One instance
 * serves one workflow at a time, but several actors can run in parallel across different workflows.</p>
 *
 * <pre>{@code
 * - actor: promptBuilder
 *   method: addWarning
 *   arguments: "Do not modify files unless explicitly instructed."
 * - actor: promptBuilder
 *   method: addContext
 *   arguments: "Repository: oogasawa/k8s-tree"
 * - actor: promptBuilder
 *   method: addMessage
 *   arguments: "${task}"
 * - actor: promptBuilder
 *   method: build
 * }</pre>
 *
 * <p>The {@code build} action fails if {@code addMessage} has not been called. {@code addWarning} and {@code
 * addContext} fail if the provided text is blank. {@code getWarning} and {@code getContext} return a failure if the
 * index is out of range.</p>
 */
public class PromptBuilderActor extends IIActorRef<PromptBuffer> {

    /**
     * Initializes a new prompt builder actor with a fresh, empty buffer.
     *
     * @param name the registry name for this actor
     * @param system the actor system it belongs to
     */
    public PromptBuilderActor(String name, IIActorSystem system) {
        super(name, new PromptBuffer(), system);
    }

    private PromptBuffer buffer() {
        return object;
    }

    /**
     * Holds the text to be added to a section of the prompt buffer.
     *
     * @param text the text to be added to the constraints, context, or message section
     */
    public record TextArgs(@NotNull String text) {}

    /**
     * Represents a zero-based position within one of the buffer's lists.
     *
     * @param index the zero-based position in the list
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

    /**
     * Returns a JSON array of messages for an LLM chat completion, containing a system message with the current
     * constraints and background, the conversation history provided in the argument, and the task body as a user
     * message.
     *
     * <p>The result is a string representing the messages array, where the history is formatted as a JSON array of
     * objects. If the argument is an empty array {@code []}, no history is included.</p>
     *
     * <p>Returns a failure if {@code addMessage} has not been called.</p>
     *
     * <pre>{@code
     * - actor: promptBuilder
     *   method: buildMessages
     *   arguments: '{"text": "[{\"role\": \"user\", \"content\": \"hi\"}]"}'
     * }</pre>
     *
     * @param args a {@link TextArgs} object containing the conversation history as a JSON string.
     * @return a success result with the JSON array of messages in its message, or a failure result if {@code
     * addMessage} was not called or if the input is invalid.
     */
    @Action(value = "buildMessages", argsType = TextArgs.class)
    public ActionResult buildMessages(TextArgs args) {
        try {
            String messages = buffer().buildMessages(args.text());
            return messages == null
                    ? new ActionResult(false, "buildMessages failed: addMessage has not been called")
                    : new ActionResult(true, messages);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "buildMessages: " + e.getMessage());
        }
    }

    @Action("build")
    public ActionResult build(String ignored) {
        String prompt = buffer().build();
        return prompt == null
                ? new ActionResult(false, "build failed: addMessage has not been called")
                : new ActionResult(true, prompt);
    }

}
