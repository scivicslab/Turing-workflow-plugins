package com.scivicslab.turingworkflow.plugins.promptbuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the components of a prompt—constraints, background, and the message body—to allow a workflow to assemble them
 * incrementally.
 *
 * <p>{@link PromptBuilderActor} uses this to build the text passed to an LLM.
 *
 * <p>{@link #build()} produces a single string formatted with sections for constraints and context, followed by the
 * message body:
 * <pre>{@code
 * [Constraints]
 * - warning1
 *
 * [Context]
 * - context1
 *
 * [Message]
 * message body
 * }</pre>
 * Sections with no entries are omitted.
 *
 * <p>One instance is typically used by a single actor to build a single prompt.
 */
public class PromptBuffer {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> contexts = new ArrayList<>();
    private String message = null;
    private int warningCursor = 0;
    private int contextCursor = 0;

    /**
     * Resets the buffer by removing all constraints, background entries, and the message, and rewinding all cursors to
     * the start.
     */
    public void clear() {
        warnings.clear();
        contexts.clear();
        message = null;
        warningCursor = 0;
        contextCursor = 0;
    }

    /**
     * Adds a single constraint to the [Constraints] section.
     *
     * @param text the constraint text to add
     * @return {@code true} if the text was added, {@code false} if the text was null or blank
     */
    public boolean addWarning(String text) {
        if (text == null || text.isBlank()) return false;
        warnings.add(text);
        return true;
    }

    /**
     * Adds one piece of background to the buffer.
     *
     * @param text the background text to add
     * @return {@code false} if the text is absent or blank, in which case nothing is added
     */
    public boolean addContext(String text) {
        if (text == null || text.isBlank()) return false;
        contexts.add(text);
        return true;
    }

    /**
     * Sets the message body, replacing any previous message.
     *
     * @param text the message body to be used in the [Message] section
     * @return {@code false} if text is null or blank, in which case the message is not updated
     */
    public boolean setMessage(String text) {
        if (text == null || text.isBlank()) return false;
        this.message = text;
        return true;
    }

    /**
     * Returns the message body that was set via {@link #setMessage(String)}.
     *
     * @return the message body, or {@code null} if no message has been set
     */
    public String getMessage() {
        return message;
    }

    /** Returns the number of constraints currently in the buffer. */
    public int warningCount() {
        return warnings.size();
    }

    /** Returns the number of background entries that have been added. */
    public int contextCount() {
        return contexts.size();
    }

    /**
     * Returns the constraint at the specified position in the list.
     *
     * @param index the zero-based position in the constraint list
     * @return the constraint at that position, or {@code null} if the index is negative or greater than or equal to
     * the number of constraints
     */
    public String warningAt(int index) {
        return (index < 0 || index >= warnings.size()) ? null : warnings.get(index);
    }

    /**
     * Returns the background entry at the specified index.
     *
     * @param index the position in the background list to retrieve
     * @return the entry at that position, or {@code null} if the index is outside the list boundaries
     */
    public String contextAt(int index) {
        return (index < 0 || index >= contexts.size()) ? null : contexts.get(index);
    }

    /**
     * Returns the next constraint from the list.
     *
     * @return the next constraint, or {@code null} if no more constraints remain
     */
    public String nextWarning() {
        return warningCursor >= warnings.size() ? null : warnings.get(warningCursor++);
    }

    /**
     * Returns the background entry at the current cursor and advances the cursor to the next entry.
     *
     * @return the background entry at the cursor, or {@code null} once the end of the list is reached
     */
    public String nextContext() {
        return contextCursor >= contexts.size() ? null : contexts.get(contextCursor++);
    }

    /**
     * Resets the positions used to iterate through the constraints and background sections to the beginning of their
     * respective lists.
     */
    public void resetCursor() {
        warningCursor = 0;
        contextCursor = 0;
    }

    /**
     * Returns a string that is the assembled prompt containing the constraints, context, and message.
     *
     * <p>The result is formatted as:
     * <pre>{@code
     * [Constraints]
     * - warning1
     *
     * [Context]
     * - context1
     *
     * [Message]
     * message body
     * }</pre>
     * Sections with no entries are omitted.
     *
     * <p>Returns {@code null} if no message has been set.
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
     * Returns a JSON array text representing a sequence of chat messages, combining a system message containing the
     * constraints and background, the provided conversation history, and a user message containing the task body.
     *
     * @param historyJson a JSON array text of messages with {@code {"role","content"}} structures, or {@code null},
     * blank, or {@code []} for no history
     *
     * @return a JSON array text containing the system, history, and user messages, or {@code null} if no message body
     * has been set
     *
     * @throws IllegalArgumentException if historyJson is not a JSON array text
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

    /**
     * Returns a string containing the constraints and background formatted as a system message, or {@code null} if
     * both sections are empty.
     */
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
