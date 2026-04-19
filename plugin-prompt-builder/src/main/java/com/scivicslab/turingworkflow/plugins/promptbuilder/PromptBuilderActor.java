package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a prompt step-by-step from warnings, context, and a message.
 *
 * <p>Workflow steps call addWarning/addContext/addMessage in sequence, then call
 * build to produce the final prompt string in ${result}. This ensures constraints
 * are always explicitly included in the prompt rather than relying on LLM memory
 * or SKILL files that may be ignored.</p>
 *
 * <p>Output format of build():</p>
 * <pre>
 * [Constraints]
 * - warning1
 * - warning2
 *
 * [Context]
 * - context1
 *
 * [Message]
 * message body
 * </pre>
 *
 * <p>Sections with no entries are omitted. build() fails if addMessage was not called.</p>
 */
public class PromptBuilderActor extends IIActorRef<PromptBuilderActor> {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> contexts = new ArrayList<>();
    private String message = null;
    private int warningCursor = 0;
    private int contextCursor = 0;

    public PromptBuilderActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    @Action("clear")
    public ActionResult clear(String ignored) {
        warnings.clear();
        contexts.clear();
        message = null;
        warningCursor = 0;
        contextCursor = 0;
        return new ActionResult(true, "buffer cleared");
    }

    @Action("addWarning")
    public ActionResult addWarning(String text) {
        if (text == null || text.isBlank()) {
            return new ActionResult(false, "addWarning: text must not be blank");
        }
        warnings.add(text.trim());
        return new ActionResult(true, "warning added: " + text.trim());
    }

    @Action("addContext")
    public ActionResult addContext(String text) {
        if (text == null || text.isBlank()) {
            return new ActionResult(false, "addContext: text must not be blank");
        }
        contexts.add(text.trim());
        return new ActionResult(true, "context added: " + text.trim());
    }

    @Action("addMessage")
    public ActionResult addMessage(String text) {
        if (text == null || text.isBlank()) {
            return new ActionResult(false, "addMessage: text must not be blank");
        }
        this.message = text.trim();
        return new ActionResult(true, "message set");
    }

    @Action("getWarningCount")
    public ActionResult getWarningCount(String ignored) {
        return new ActionResult(true, String.valueOf(warnings.size()));
    }

    @Action("getWarning")
    public ActionResult getWarning(String indexStr) {
        try {
            int index = parseIndex(indexStr);
            if (index < 0 || index >= warnings.size()) {
                return new ActionResult(false,
                        "getWarning: index " + index + " out of range (size=" + warnings.size() + ")");
            }
            return new ActionResult(true, warnings.get(index));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getWarning: invalid index: " + indexStr);
        }
    }

    @Action("getContextCount")
    public ActionResult getContextCount(String ignored) {
        return new ActionResult(true, String.valueOf(contexts.size()));
    }

    @Action("getContext")
    public ActionResult getContext(String indexStr) {
        try {
            int index = parseIndex(indexStr);
            if (index < 0 || index >= contexts.size()) {
                return new ActionResult(false,
                        "getContext: index " + index + " out of range (size=" + contexts.size() + ")");
            }
            return new ActionResult(true, contexts.get(index));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "getContext: invalid index: " + indexStr);
        }
    }

    @Action("getNextWarning")
    public ActionResult getNextWarning(String ignored) {
        if (warningCursor >= warnings.size()) {
            return new ActionResult(false, "getNextWarning: no more warnings");
        }
        return new ActionResult(true, warnings.get(warningCursor++));
    }

    @Action("getNextContext")
    public ActionResult getNextContext(String ignored) {
        if (contextCursor >= contexts.size()) {
            return new ActionResult(false, "getNextContext: no more contexts");
        }
        return new ActionResult(true, contexts.get(contextCursor++));
    }

    @Action("resetCursor")
    public ActionResult resetCursor(String ignored) {
        warningCursor = 0;
        contextCursor = 0;
        return new ActionResult(true, "cursors reset");
    }

    @Action("getMessage")
    public ActionResult getMessage(String ignored) {
        if (message == null) {
            return new ActionResult(false, "getMessage: no message set");
        }
        return new ActionResult(true, message);
    }

    private int parseIndex(String indexStr) {
        // Interpreter may pass arguments as JSON array: ["0"] -> strip brackets/quotes
        String cleaned = indexStr.trim().replaceAll("[\\[\\]\"\\s]", "");
        return Integer.parseInt(cleaned);
    }

    @Action("build")
    public ActionResult build(String ignored) {
        if (message == null) {
            return new ActionResult(false, "build failed: addMessage has not been called");
        }

        StringBuilder sb = new StringBuilder();

        if (!warnings.isEmpty()) {
            sb.append("[Constraints]\n");
            for (String w : warnings) {
                sb.append("- ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (!contexts.isEmpty()) {
            sb.append("[Context]\n");
            for (String c : contexts) {
                sb.append("- ").append(c).append("\n");
            }
            sb.append("\n");
        }

        sb.append("[Message]\n");
        sb.append(message);

        return new ActionResult(true, sb.toString());
    }
}
