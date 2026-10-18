package com.scivicslab.turingworkflow.plugins.promptbuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the pieces of a prompt while a workflow assembles it: the constraints that must be stated,
 * the background, and the message itself.
 *
 * <p>A plain object with no dependency on the actor framework — it is turned into an actor by
 * being wrapped ({@link PromptBuilderActor}), which is where the workflow-facing code lives. The
 * class carries no {@code Actor} in its name for the same reason: being run as an actor is a role
 * it acquires when something wraps it, not a property of the class
 * ({@code ActorSuffixAndOwnedActorRef_260722_oo01}).</p>
 *
 * <p>{@link #build()} produces:</p>
 * <pre>
 * [Constraints]
 * - warning1
 *
 * [Context]
 * - context1
 *
 * [Message]
 * message body
 * </pre>
 *
 * <p>Sections with no entries are omitted.</p>
 */
public class PromptBuffer {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> contexts = new ArrayList<>();
    private String message = null;
    private int warningCursor = 0;
    private int contextCursor = 0;

    /** Empties every section and rewinds both cursors. */
    public void clear() {
        warnings.clear();
        contexts.clear();
        message = null;
        warningCursor = 0;
        contextCursor = 0;
    }

    /**
     * @param text one constraint
     * @return {@code false} if the text is absent or blank, in which case nothing is added
     */
    public boolean addWarning(String text) {
        if (text == null || text.isBlank()) return false;
        warnings.add(text);
        return true;
    }

    /**
     * @param text one piece of background
     * @return {@code false} if the text is absent or blank, in which case nothing is added
     */
    public boolean addContext(String text) {
        if (text == null || text.isBlank()) return false;
        contexts.add(text);
        return true;
    }

    /**
     * @param text the message body, replacing any previous one
     * @return {@code false} if the text is absent or blank, in which case nothing is set
     */
    public boolean setMessage(String text) {
        if (text == null || text.isBlank()) return false;
        this.message = text;
        return true;
    }

    /** @return the message body, or {@code null} if none has been set */
    public String getMessage() {
        return message;
    }

    /** @return how many constraints have been added */
    public int warningCount() {
        return warnings.size();
    }

    /** @return how many pieces of background have been added */
    public int contextCount() {
        return contexts.size();
    }

    /**
     * @param index position in the constraint list
     * @return that constraint, or {@code null} if the index is outside the list
     */
    public String warningAt(int index) {
        return (index < 0 || index >= warnings.size()) ? null : warnings.get(index);
    }

    /**
     * @param index position in the background list
     * @return that entry, or {@code null} if the index is outside the list
     */
    public String contextAt(int index) {
        return (index < 0 || index >= contexts.size()) ? null : contexts.get(index);
    }

    /** @return the constraint at the cursor, advancing it; {@code null} once past the end */
    public String nextWarning() {
        return warningCursor >= warnings.size() ? null : warnings.get(warningCursor++);
    }

    /** @return the background entry at the cursor, advancing it; {@code null} once past the end */
    public String nextContext() {
        return contextCursor >= contexts.size() ? null : contexts.get(contextCursor++);
    }

    /** Rewinds both cursors to the start. */
    public void resetCursor() {
        warningCursor = 0;
        contextCursor = 0;
    }

    /**
     * @return the assembled prompt, or {@code null} if no message has been set — a prompt with
     *         constraints and no message is not something to send
     */
    public String build() {
        if (message == null) return null;

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("[Constraints]\n");
            for (String w : warnings) sb.append("- ").append(w).append("\n");
            sb.append("\n");
        }
        if (!contexts.isEmpty()) {
            sb.append("[Context]\n");
            for (String c : contexts) sb.append("- ").append(c).append("\n");
            sb.append("\n");
        }
        sb.append("[Message]\n").append(message);
        return sb.toString();
    }

    /**
     * The messages of a request, as a JSON array text: a {@code system} message holding the
     * constraints and the background (left out when there are none), then the given history
     * messages, then the message body as a {@code user} message.
     *
     * <p>The buffer keeps only what one assembly needs; the history that outlives an assembly
     * belongs to whoever owns the conversation, and is handed in here already selected
     * ({@code RequestMessagesControl_261018_oo01}). The history is spliced in as it is, so it must
     * already be a JSON array of {@code {"role","content"}} messages.</p>
     *
     * @param historyJson the selected history messages as a JSON array text; {@code null}, blank
     *                    or {@code []} for none
     * @return the array, or {@code null} if no message has been set
     * @throws IllegalArgumentException if {@code historyJson} is not an array text
     */
    public String buildMessages(String historyJson) {
        if (message == null) return null;
        String history = historyJson == null ? "" : historyJson.strip();
        if (!history.isEmpty() && !(history.startsWith("[") && history.endsWith("]"))) {
            throw new IllegalArgumentException("history must be a JSON array");
        }
        String inner = history.isEmpty() ? "" : history.substring(1, history.length() - 1).strip();

        StringBuilder out = new StringBuilder("[");
        String system = systemText();
        if (system != null) out.append(messageJson("system", system)).append(',');
        if (!inner.isEmpty()) out.append(inner).append(',');
        out.append(messageJson("user", message)).append(']');
        return out.toString();
    }

    /** @return the constraints and background as the text of a system message, or {@code null} if both are empty */
    private String systemText() {
        if (warnings.isEmpty() && contexts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("[Constraints]\n");
            for (String w : warnings) sb.append("- ").append(w).append("\n");
        }
        if (!contexts.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[Context]\n");
            for (String c : contexts) sb.append("- ").append(c).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String messageJson(String role, String content) {
        return "{\"role\":\"" + role + "\",\"content\":\"" + escape(content) + "\"}";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
