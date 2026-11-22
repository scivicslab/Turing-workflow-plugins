package com.scivicslab.turingworkflow.plugins.chatui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import jakarta.validation.constraints.NotNull;

/**
 * Talks to one conversation of a chat-ui-with-audit-trail server through its REST API. Each action is one
 * {@link ChatUiConnection} call; the conversation is the one {@code setChat} named, {@code project1/chat-01} at first.
 *
 * <ul>
 * <li><code>setBaseUrl</code>, <code>setChat</code>, <code>setTimeLimits</code>: settings.</li>
 * <li><code>send</code>: <code>POST /api/projects/{p}/chats/{c}/chat</code>, then polls
 * <code>.../conversation</code> and <code>.../status</code> until the turn's answer is in the history.</li>
 * <li><code>cancel</code>: <code>POST .../chat/cancel</code>; also ends a <code>send</code> that is waiting.</li>
 * <li><code>getStatus</code>, <code>getConversation</code>, <code>clearConversation</code>, <code>getModels</code>,
 * <code>setModel</code>, <code>setProvider</code>, <code>listChats</code>: <code>.../status</code>, <code>GET</code>
 * and <code>DELETE .../conversation</code>, <code>.../models</code>, <code>.../model</code>, <code>.../provider</code>,
 * <code>/api/projects/{p}/chats</code>.</li>
 * <li><code>getSessions</code>, <code>getTurns</code>, <code>getTrace</code>: <code>/api/sessions</code>,
 * <code>/api/sessions/{id}/turns</code>, <code>/api/sessions/{id}/trace/{turn}</code>.</li>
 * </ul>
 *
 * <p>Every action but <code>cancel</code> runs on this actor's mailbox virtual thread, one after another; the HTTP
 * requests run on virtual threads and the mailbox virtual thread waits for them. <code>cancel</code> runs at once on a
 * virtual thread of its own, so that it is not queued behind the <code>send</code> it is meant to end.</p>
 *
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["ROOT", "chat", "com.scivicslab.turingworkflow.plugins.chatui.ChatUiIIAR"]
 * - actor: chat
 *   method: setBaseUrl
 *   arguments: {baseUrl: "http://localhost:28030"}
 * - actor: chat
 *   method: setChat
 *   arguments: {projectId: project1, chatId: "02"}
 * - actor: chat
 *   method: send
 *   arguments: {text: "Say hello."}
 * - actor: this
 *   method: putJson
 *   arguments: {path: answer, value: "jexl: result"}
 * }</pre>
 */
public class ChatUiIIAR extends IIActorRef<ChatUiConnection> {

    public ChatUiIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ChatUiConnection(), system);
    }

    /**
     * The server's address.
     *
     * @param baseUrl the base URL, for example {@code http://localhost:28030}
     */
    public record BaseUrlArgs(@NotNull String baseUrl) {}

    /**
     * The conversation the actions work on.
     *
     * @param projectId the project id, for example {@code project1}
     * @param chatId    the chat id within the project, for example {@code 01}
     */
    public record ChatArgs(@NotNull String projectId, @NotNull String chatId) {}

    /**
     * The time limits; a field left out keeps its value.
     *
     * @param totalSeconds   how long a whole {@code send} may take; ten minutes at first
     * @param requestSeconds how long one HTTP request may take; thirty seconds at first
     * @param pollMillis     how long {@code send} waits between two polls; 500 at first
     */
    public record TimeLimitArgs(Long totalSeconds, Long requestSeconds, Long pollMillis) {}

    /**
     * A prompt to send.
     *
     * @param text    the prompt
     * @param model   the model to answer with; the conversation's own when left out
     * @param noThink true to ask the model not to think aloud, where the conversation supports it
     */
    public record SendArgs(@NotNull String text, String model, Boolean noThink) {}

    /**
     * A model.
     *
     * @param model the model's name, as {@code getModels} lists it
     */
    public record ModelArgs(@NotNull String model) {}

    /**
     * A provider.
     *
     * @param provider for example {@code openai-compat}, {@code claude} or {@code codex}
     * @param tools    {@code full} or {@code harness}; the provider's default when left out
     */
    public record ProviderArgs(@NotNull String provider, String tools) {}

    /**
     * Which sessions.
     *
     * @param all true for every conversation's sessions; only this conversation's when left out
     */
    public record SessionsArgs(Boolean all) {}

    /**
     * A window of a session's turns.
     *
     * @param sessionId the numeric id {@code getSessions} lists
     * @param before    only turns numbered below this; the newest when left out
     * @param limit     how many; 20 when left out
     */
    public record TurnsArgs(@NotNull Long sessionId, Integer before, Integer limit) {}

    /**
     * One turn of a session.
     *
     * @param sessionId the numeric id {@code getSessions} lists
     * @param turn      the turn number, as {@code getTurns} lists it
     */
    public record TraceArgs(@NotNull Long sessionId, @NotNull Integer turn) {}

    /**
     * Sets the server's base URL.
     *
     * @param args the base URL
     * @return true with the URL kept, or false when it is not an http or https URL
     */
    @Action(value = "setBaseUrl", argsType = BaseUrlArgs.class)
    public ActionResult setBaseUrl(BaseUrlArgs args) {
        return onMailbox("setBaseUrl", c -> c.setBaseUrl(args.baseUrl()));
    }

    /**
     * Sets the conversation the other actions work on.
     *
     * @param args the project id and the chat id
     * @return true with {@code <project>/chat-<chat>}, or false when either is blank or holds a slash
     */
    @Action(value = "setChat", argsType = ChatArgs.class)
    public ActionResult setChat(ChatArgs args) {
        return onMailbox("setChat", c -> c.setChat(args.projectId(), args.chatId()));
    }

    /**
     * Sets the time limits of {@code send} and of each HTTP request.
     *
     * @param args the limits to change
     * @return true with the limits now in force, or false when one is not positive
     */
    @Action(value = "setTimeLimits", argsType = TimeLimitArgs.class)
    public ActionResult setTimeLimits(TimeLimitArgs args) {
        return onMailbox("setTimeLimits", c -> c.setTimeLimits(args.totalSeconds(), args.requestSeconds(), args.pollMillis()));
    }

    /**
     * Sends a prompt to the conversation and waits for its turn's answer, at most the total limit.
     *
     * @param args the prompt, and optionally the model and noThink
     * @return true with the answer; false naming the URL and the limit when the limit runs out, the error the turn
     * recorded, or that the turn ended without an answer
     */
    @Action(value = "send", argsType = SendArgs.class)
    public ActionResult send(SendArgs args) {
        return onMailbox("send", c -> c.send(args.text(), args.model(), Boolean.TRUE.equals(args.noThink())));
    }

    /**
     * Cancels the conversation's running turn and ends a {@code send} of this actor that is waiting.
     *
     * @param args ignored
     * @return true with the server's answer, or false naming the URL and what went wrong
     */
    @Action("cancel")
    public ActionResult cancel(String args) {
        // Not on the mailbox: a send that is waiting holds it.
        return waitFor("cancel", askNow(ChatUiConnection::cancel));
    }

    /**
     * Reads the conversation's status.
     *
     * @param args ignored
     * @return true with the JSON object of {@code busy}, {@code model}, {@code provider}, {@code tools}, ...
     */
    @Action("getStatus")
    public ActionResult getStatus(String args) {
        return onMailbox("getStatus", ChatUiConnection::getStatus);
    }

    /**
     * Reads the conversation's history.
     *
     * @param args ignored
     * @return true with the JSON array of {@code {role, content, origin, at, model, durationMs, turnId, requestId}}
     */
    @Action("getConversation")
    public ActionResult getConversation(String args) {
        return onMailbox("getConversation", ChatUiConnection::getConversation);
    }

    /**
     * Starts the conversation over. Nothing is deleted from the I/O log.
     *
     * @param args ignored
     * @return true with {@code {"type":"cleared"}}
     */
    @Action("clearConversation")
    public ActionResult clearConversation(String args) {
        return onMailbox("clearConversation", ChatUiConnection::clearConversation);
    }

    /**
     * Lists the models the conversation's provider offers.
     *
     * @param args ignored
     * @return true with the JSON array of the models
     */
    @Action("getModels")
    public ActionResult getModels(String args) {
        return onMailbox("getModels", ChatUiConnection::getModels);
    }

    /**
     * Puts the conversation on a model.
     *
     * @param args the model
     * @return true with {@code {"type":"accepted"}}
     */
    @Action(value = "setModel", argsType = ModelArgs.class)
    public ActionResult setModel(ModelArgs args) {
        return onMailbox("setModel", c -> c.setModel(args.model()));
    }

    /**
     * Puts the conversation on a provider.
     *
     * @param args the provider, and optionally the tool set
     * @return true with {@code {"type":"accepted","provider":...,"tools":...}}
     */
    @Action(value = "setProvider", argsType = ProviderArgs.class)
    public ActionResult setProvider(ProviderArgs args) {
        return onMailbox("setProvider", c -> c.setProvider(args.provider(), args.tools()));
    }

    /**
     * Lists the chat ids of the project.
     *
     * @param args ignored
     * @return true with the JSON array of the ids
     */
    @Action("listChats")
    public ActionResult listChats(String args) {
        return onMailbox("listChats", ChatUiConnection::listChats);
    }

    /**
     * Lists I/O-log sessions, most recent first.
     *
     * @param args whether to list every conversation's sessions
     * @return true with the JSON array of {@code {sessionId, workflowName, startedAt, endedAt, status, ...}}
     */
    @Action(value = "getSessions", argsType = SessionsArgs.class)
    public ActionResult getSessions(SessionsArgs args) {
        return onMailbox("getSessions", c -> c.getSessions(Boolean.TRUE.equals(args.all())));
    }

    /**
     * Reads a window of a session's turns, newest first.
     *
     * @param args the session, and optionally before and limit
     * @return true with {@code {turns: [{turn, question}], lastTurn}}
     */
    @Action(value = "getTurns", argsType = TurnsArgs.class)
    public ActionResult getTurns(TurnsArgs args) {
        return onMailbox("getTurns", c -> c.getTurns(args.sessionId(), args.before(), args.limit()));
    }

    /**
     * Reads one turn's steps.
     *
     * @param args the session and the turn
     * @return true with the JSON object of the turn
     */
    @Action(value = "getTrace", argsType = TraceArgs.class)
    public ActionResult getTrace(TraceArgs args) {
        return onMailbox("getTrace", c -> c.getTrace(args.sessionId(), args.turn()));
    }

    /** Ends the connection's HTTP client, then the actor. */
    @Override
    public void close() {
        ChatUiConnection c = object;
        if (c != null) {
            c.close();
        }
        super.close();
    }

    private ActionResult onMailbox(String action, Function<ChatUiConnection, ActionResult> work) {
        return waitFor(action, ask(work));
    }

    private ActionResult waitFor(String action, CompletableFuture<ActionResult> pending) {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, getName() + "." + action + ": interrupted");
        } catch (ExecutionException e) {
            ActionResult.rethrowIfFatal(e);
            return ActionResult.error(e.getCause() != null ? e.getCause() : e, getName() + "." + action);
        }
    }
}
