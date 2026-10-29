package com.scivicslab.turingworkflow.plugins.promptbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * The messages of one request to an OpenAI-compatible chat endpoint, built up field by field: the constraints and the
 * background that make the system message, the earlier turns of the conversation, and the request that makes the last
 * user message.
 *
 * <p>The request a chat endpoint receives is an array of messages, each a role and a text. This class holds the parts
 * of that array as separate fields, so that a caller can set, read and count each one, and then makes either of two
 * things from them:
 * <ul>
 * <li>{@link #toChatMessages()}: the whole array, as JSON. The constraints and the background become one system message
 * at the head, under the headings {@code [Constraints]} and {@code [Context]}; the earlier turns follow as they were
 * added; the request is the last user message.</li>
 * <li>{@link #toUserPrompt()}: one text that carries all three inside it, under the headings {@code [Constraints]},
 * {@code [Context]} and {@code [Message]}, for a caller that sends a single user message. The earlier turns are not
 * in it.</li>
 * </ul>
 * A heading with nothing under it is left out of either.
 *
 * <p>An instance is not safe to share between threads; one workflow builds one request with it.
 */
public final class ChatPrompt {

    /** The two roles an earlier turn of the conversation can have. */
    public enum Role {
        USER("user"), ASSISTANT("assistant");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        /** @return the role as the chat endpoint spells it ({@code user}, {@code assistant}) */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * One earlier turn of the conversation.
     *
     * @param role    who said it
     * @param content what was said
     */
    public record Turn(Role role, String content) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> constraints = new ArrayList<>();
    private final List<String> contexts = new ArrayList<>();
    private final List<Turn> history = new ArrayList<>();
    private String request;

    /** Removes every field: the constraints, the background, the earlier turns and the request. */
    public void clear() {
        constraints.clear();
        contexts.clear();
        history.clear();
        request = null;
    }

    // ---- constraints: the [Constraints] heading of the system message (or of the user prompt) ----

    /**
     * Adds a constraint the model must keep, after those already added.
     *
     * @param text the constraint
     * @return {@code false}, and nothing added, if the text is null or blank
     */
    public boolean addConstraint(String text) {
        if (blank(text)) return false;
        constraints.add(text);
        return true;
    }

    /** @return the constraints in the order they were added; the list cannot be changed */
    public List<String> constraints() {
        return Collections.unmodifiableList(constraints);
    }

    /**
     * @param index the position of the constraint, from 0
     * @return the constraint, or {@code null} if there is none at that position
     */
    public String constraint(int index) {
        return at(constraints, index);
    }

    /** @return how many constraints there are */
    public int constraintCount() {
        return constraints.size();
    }

    // ---- background: the [Context] heading of the system message (or of the user prompt) ----

    /**
     * Adds a piece of background the model should know, after those already added.
     *
     * @param text the background
     * @return {@code false}, and nothing added, if the text is null or blank
     */
    public boolean addContext(String text) {
        if (blank(text)) return false;
        contexts.add(text);
        return true;
    }

    /** @return the background in the order it was added; the list cannot be changed */
    public List<String> contexts() {
        return Collections.unmodifiableList(contexts);
    }

    /**
     * @param index the position of the background, from 0
     * @return the background, or {@code null} if there is none at that position
     */
    public String context(int index) {
        return at(contexts, index);
    }

    /** @return how many pieces of background there are */
    public int contextCount() {
        return contexts.size();
    }

    // ---- earlier turns: the messages between the system message and the last user message ----

    /**
     * Adds an earlier turn of the conversation, after those already added.
     *
     * @param role    who said it
     * @param content what was said
     * @return {@code false}, and nothing added, if the role is null or the content null or blank
     */
    public boolean addTurn(Role role, String content) {
        if (role == null || blank(content)) return false;
        history.add(new Turn(role, content));
        return true;
    }

    /**
     * Adds earlier turns given as a JSON array of {@code {"role": ..., "content": ...}}, as a chat endpoint takes them.
     * Every element must have the role {@code user} or {@code assistant} and a text content; if one does not, none is
     * added. A null, blank or empty array adds nothing.
     *
     * @param json the turns, as a JSON array text
     * @return how many turns were added
     * @throws IllegalArgumentException if the text is not a JSON array of such turns
     */
    public int addTurns(String json) {
        if (blank(json)) return 0;
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the turns are not JSON: " + e.getOriginalMessage(), e);
        }
        if (!node.isArray()) throw new IllegalArgumentException("the turns must be a JSON array");
        List<Turn> read = new ArrayList<>();
        for (JsonNode m : node) {
            String role = m.path("role").asText("");
            JsonNode content = m.get("content");
            Role r = switch (role) {
                case "user" -> Role.USER;
                case "assistant" -> Role.ASSISTANT;
                default -> throw new IllegalArgumentException("a turn must have the role user or assistant, not '" + role + "'");
            };
            if (content == null || !content.isTextual()) throw new IllegalArgumentException("a turn must have a text content");
            read.add(new Turn(r, content.asText()));
        }
        history.addAll(read);
        return read.size();
    }

    /** @return the earlier turns in the order they were added; the list cannot be changed */
    public List<Turn> turns() {
        return Collections.unmodifiableList(history);
    }

    /** @return how many earlier turns there are */
    public int turnCount() {
        return history.size();
    }

    // ---- request: the last user message ----

    /**
     * Sets the request the model is to answer, replacing any earlier one.
     *
     * @param text the request
     * @return {@code false}, and nothing changed, if the text is null or blank
     */
    public boolean setRequest(String text) {
        if (blank(text)) return false;
        request = text;
        return true;
    }

    /** @return the request, or {@code null} if none has been set */
    public String request() {
        return request;
    }

    // ---- what is made from the fields ----

    /**
     * Returns the text of the system message: the constraints under {@code [Constraints]} and the background under
     * {@code [Context]}, each item on a line of its own after {@code "- "}, the two headings separated by a blank line.
     *
     * @return the text, or {@code null} if there are no constraints and no background
     */
    public String systemText() {
        if (constraints.isEmpty() && contexts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        section(sb, "Constraints", constraints);
        section(sb, "Context", contexts);
        return sb.toString().stripTrailing();
    }

    /**
     * Returns the array of messages for a chat endpoint, as JSON: the system message (if there are constraints or
     * background), then the earlier turns, then the request as the last user message.
     *
     * @return the messages, as a JSON array text
     * @throws IllegalStateException if no request has been set
     */
    public String toChatMessages() {
        if (request == null) throw new IllegalStateException("no request has been set");
        ArrayNode out = JSON.createArrayNode();
        String system = systemText();
        if (system != null) out.addObject().put("role", "system").put("content", system);
        for (Turn t : history) out.addObject().put("role", t.role().wireName()).put("content", t.content());
        out.addObject().put("role", "user").put("content", request);
        return out.toString();
    }

    /**
     * Returns one text that carries the constraints under {@code [Constraints]}, the background under {@code [Context]}
     * and the request under {@code [Message]}, for a caller that sends it as a single user message. The earlier turns
     * are not in it.
     *
     * @return the text
     * @throws IllegalStateException if no request has been set
     */
    public String toUserPrompt() {
        if (request == null) throw new IllegalStateException("no request has been set");
        StringBuilder sb = new StringBuilder();
        section(sb, "Constraints", constraints);
        section(sb, "Context", contexts);
        sb.append("[Message]\n").append(request);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String heading, List<String> items) {
        if (items.isEmpty()) return;
        sb.append('[').append(heading).append("]\n");
        for (String item : items) sb.append("- ").append(item).append('\n');
        sb.append('\n');
    }

    private static String at(List<String> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
