package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Communicates with an LLM through one of two endpoints: an OpenAI-compatible chat completions endpoint (such as
 * vLLM), or the REST API of a quarkus-chat-ui instance (Direct REST mode).
 *
 * <ul><li ><code>setDirectUrl</code> sets the base URL for Direct REST mode.</li><li ><code>setOpenAiUrl</code> sets
 * the URL posted to (e.g. {@code http://host:8000/v1/chat/completions}) and model ID for OpenAI-compatible requests.</li><li ><code>setSystemPrompt</code> sets the system
 * instructions for the model.</li><li ><code>setEnableThinking</code> enables or disables the model's reasoning
 * step.</li><li ><code>callOpenAi</code> sends a prompt to the OpenAI-compatible endpoint.</li><li
 * ><code>submitDirect</code> sends a prompt to the Direct REST endpoint.</li></ul>
 *
 * <p>One instance may serve several workflows at once, as actions are executed one at a time.</p>
 */
public class LlmActor extends IIActorRef<LlmClient> {

    /**
     * Initializes a new {@code LlmActor} with a new {@code LlmClient}.
     *
     * @param name the name to register this actor under
     * @param system the actor system this actor belongs to
     */
    public LlmActor(String name, IIActorSystem system) {
        super(name, new LlmClient(), system);
    }

    private LlmClient pojo() {
        return object;
    }

    /**
     * @param url ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The base URL of an endpoint to be used by the actor.
     *
     * @param url the base URL of the endpoint
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * Holds the URL of an OpenAI-compatible chat completions endpoint and an optional model ID.
     *
     * @param url the URL requests are posted to, as given, e.g. {@code http://host:8000/v1/chat/completions}
     * @param model the model ID to request; if null, the current model is kept
     */
    public record OpenAiUrlArgs(@NotNull String url, String model) {}

    /**
     * The text to be sent to the LLM.
     *
     * @param prompt the text to send
     */
    public record PromptArgs(@NotNull String prompt) {}

    /**
     * Sets whether the model should be asked to think before answering.
     *
     * @param enabled true to enable thinking, false to disable it
     */
    public record EnableThinkingArgs(@NotNull Boolean enabled) {}

    @Action(value = "setDirectUrl", argsType = UrlArgs.class)
    public ActionResult setDirectUrl(UrlArgs args) {
        return pojo().setDirectUrl(args.url());
    }

    /**
     * Sets the URL of the OpenAI-compatible chat completions endpoint and the model ID to be used.
     *
     * @param args a record containing the endpoint URL and the model ID to request; omitting the model ID keeps the
     * current one
     *
     * @return an action result representing the outcome of the operation
     */
    @Action(value = "setOpenAiUrl", argsType = OpenAiUrlArgs.class)
    public ActionResult setOpenAiUrl(OpenAiUrlArgs args) {
        return pojo().setOpenAiUrl(args.url(), args.model());
    }

    /**
     * Sets the system prompt used by the LLM to guide its behavior.
     *
     * @param args the text to be used as the system prompt
     */
    @Action(value = "setSystemPrompt", argsType = PromptArgs.class)
    public ActionResult setSystemPrompt(PromptArgs args) {
        return pojo().setSystemPrompt(args.prompt());
    }

    /**
     * Sets whether the model should be asked to think before answering.
     *
     * @param args a record containing the boolean value to set
     * @return an action result that is a success if the setting was applied, or a failure otherwise
     */
    @Action(value = "setEnableThinking", argsType = EnableThinkingArgs.class)
    public ActionResult setEnableThinking(EnableThinkingArgs args) {
        return pojo().setEnableThinking(String.valueOf(args.enabled()));
    }

    /**
     * Sends a prompt to an OpenAI-compatible endpoint and returns the model's response.
     *
     * @param args the text to send as a prompt
     * @return an action result that is a success if the model responded, with the response text in the message;
     * otherwise, a failure result
     */
    @Action(value = "callOpenAi", argsType = PromptArgs.class)
    public ActionResult callOpenAi(PromptArgs args) {
        return pojo().callOpenAi(args.prompt());
    }

    /**
     * Sends a text prompt directly to the configured REST endpoint and returns the LLM's response.
     *
     * @param args the text to send as the prompt
     * @return an action result that is a success containing the LLM's response text in the message, or a failure if
     * the request could not be completed
     */
    @Action(value = "submitDirect", argsType = PromptArgs.class)
    public ActionResult submitDirect(PromptArgs args) {
        return pojo().submitDirect(args.prompt());
    }

}