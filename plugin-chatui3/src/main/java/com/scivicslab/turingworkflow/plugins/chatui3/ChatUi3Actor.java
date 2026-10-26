package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Interacts with a ChatUi3 server to send messages to models, update configurations, and retrieve traces or model
 * lists.
 *
 * <ul><li>{@code chat} sends a message to the model and returns an action result containing the model's
 * response.</li><li>{@code updateConfig} changes the server settings like the vLLM URL, model ID, temperature, or max
 * tokens and returns an action result.</li><li>{@code setBaseUrl} changes the server's address and returns an action
 * result.</li><li>{@code getModels} returns a list of available models as an action result.</li><li>{@code getConfig}
 * returns the current configuration as an action result.</li><li>{@code getTrace} returns the execution trace as an
 * action result.</li><li>{@code clearHistory} clears the conversation history and returns an action
 * result.</li><li>{@code stopChat} stops the current chat session and returns an action result.</li></ul>
 *
 * <p>One instance may serve several workflows at once, as actions are executed one at a time.</p>
 *
 * <p>A watchdog child actor is created to stop the {@code chat} action if it hangs while reading SSE.</p>
 */
public class ChatUi3Actor extends IIActorRef<ChatUi3Client> {

    /**
     * Initializes a new actor that communicates with a quarkus-chat-ui3 server.
     *
     * @param name the name used to identify this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public ChatUi3Actor(String name, IIActorSystem system) {
        super(name, new ChatUi3Client(), system, self ->
                self.addChildActor(new ChatUi3WatchdogActor(
                        self.getName() + "-watchdog", (ChatUi3Actor) self, system)));
    }

    private ChatUi3Client client() {
        return object;
    }

    /** Stops the ongoing Server-Sent Events stream. */
    public void stopSse() {
        client().stopSse();
    }

    /** Performs a shutdown of the underlying {@code ChatUi3Client} and then closes the actor. */
    @Override
    public void close() {
        client().shutdown();
        super.close();
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The server's address for the quarkus-chat-ui3 service.
     *
     * @param baseUrl the URL of the server
     */
    public record BaseUrlArgs(@NotNull String baseUrl) {}

    /**
     * The text sent to the model.
     *
     * @param message the text to be sent to the model
     */
    public record MessageArgs(@NotNull String message) {}

    /**
     * The configuration fields to update in the chat client. Every field is optional; omitted fields keep their
     * existing values.
     *
     * @param vllmBaseUrl the address where the vLLM server listens
     * @param modelId the identifier of the model that answers
     * @param temperature the sampling variance
     * @param maxTokens the maximum number of tokens the model may produce
     */
    public record ConfigPatchArgs(String vllmBaseUrl, String modelId,
                                  Double temperature, Integer maxTokens) {}

    @Action("stopChat")
    public ActionResult stopChat(String args) {
        return client().stopChat();
    }

    /**
     * Sets the address of the quarkus-chat-ui3 server.
     *
     * @param args a record containing the server's address as a string
     * @return an action result that is a success if the URL was updated, or contains an error message if the update
     * failed
     */
    @Action(value = "setBaseUrl", argsType = BaseUrlArgs.class)
    public ActionResult setBaseUrl(BaseUrlArgs args) {
        return client().setBaseUrl(args.baseUrl());
    }

    /**
     * Sends a message to the model and returns the result of the chat.
     *
     * @param args the message text to send
     */
    @Action(value = "chat", argsType = MessageArgs.class)
    public ActionResult chat(MessageArgs args) {
        return client().chat(args.message());
    }

    /** Returns an action result containing the trace from the client. */
    @Action("getTrace")
    public ActionResult getTrace(String args) {
        return client().getTrace();
    }

    /**
     * Updates the configuration of the chat interface by patching the existing settings with the provided values.
     *
     * @param args a record containing the fields to change; any field left null keeps the current value
     * @return an action result that is a success if the configuration was updated, or a failure if the update could
     * not be applied
     */
    @Action(value = "updateConfig", argsType = ConfigPatchArgs.class)
    public ActionResult updateConfig(ConfigPatchArgs args) {
        return client().updateConfig(args.vllmBaseUrl(), args.modelId(),
                args.temperature(), args.maxTokens());
    }

    /**
     * Returns an action result containing the current configuration of the chat interface as a text message.
     *
     * @param args the argument from the workflow (unused)
     * @return an action result that is a success with the configuration text in its message, or a failure if the
     * configuration could not be retrieved
     */
    @Action("getConfig")
    public ActionResult getConfig(String args) {
        return client().getConfig();
    }

    /**
     * Clears the conversation history in the chat interface.
     *
     * @param args the arguments from the workflow (unused)
     * @return an action result indicating whether the history was cleared, with the message holding the outcome
     */
    @Action("clearHistory")
    public ActionResult clearHistory(String args) {
        return client().clearHistory();
    }

    /**
     * Returns an action result containing a list of available models.
     *
     * @param args the argument string from the workflow, which is unused
     */
    @Action("getModels")
    public ActionResult getModels(String args) {
        return client().getModels();
    }

}