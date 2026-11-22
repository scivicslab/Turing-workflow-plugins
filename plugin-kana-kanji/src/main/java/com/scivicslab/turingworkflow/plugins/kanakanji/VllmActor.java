package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Operates a vLLM server through its API to perform text segmentation or hiragana conversion.
 *
 * <ul><li>setUrl: Sets the server address using the provided {@code UrlArgs}.</li><li>setModel: Sets the model
 * identifier using the provided {@code ModelArgs}.</li><li>segment: Segments the provided text using {@code
 * TextArgs}.</li><li>toHiragana: Converts the provided text to hiragana using {@code TextArgs}.</li></ul>
 *
 * <p>The actor holds a {@code VllmClient} which maintains the connection state and configuration. If the client is not
 * alive, actions fail without a message.</p>
 *
 * <p>One instance serves one workflow at a time.</p>
 */
public class VllmActor extends IIActorRef<VllmClient> {

    /**
     * Initializes a new actor named {@code name} within the specified {@code system} to control a vLLM server.
     *
     * @param name the name used to identify this actor in workflows
     * @param system the actor system this actor belongs to
     */
    public VllmActor(String name, IIActorSystem system) {
        super(name, new VllmClient(), system);
    }

    private VllmClient pojo() {
        return object;
    }

    /**
     * @param url ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The address of the vLLM server.
     *
     * @param url the server's address
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * The identifier of the model to be used for processing.
     *
     * @param model the model's identifier
     */
    public record ModelArgs(@NotNull String model) {}

    /**
     * Holds the text to be processed by the model, such as text for segmentation or for conversion to hiragana.
     *
     * @param text the text to segment or to read aloud in hiragana
     */
    public record TextArgs(@NotNull String text) {}

    /**
     * The time limits of each request to the vLLM server; an omitted value keeps the current one.
     *
     * @param totalSeconds seconds one request may take in all, queue wait and body included; default 600
     * @param idleSeconds seconds the response body may stall once it has begun; default 60
     */
    public record TimeLimitsArgs(Integer totalSeconds, Integer idleSeconds) {}

    /**
     * Sets the time limits of each request to the vLLM server.
     *
     * <pre>{@code
     * - actor: vllm
     *   method: setTimeLimits
     *   arguments: {totalSeconds: 1200, idleSeconds: 60}
     * }</pre>
     *
     * @param args the limits; omitted ones keep their values
     * @return a success naming the limits in force, or a failure if a value is not positive
     */
    @Action(value = "setTimeLimits", argsType = TimeLimitsArgs.class)
    public ActionResult setTimeLimits(TimeLimitsArgs args) {
        return pojo().setTimeLimits(args.totalSeconds(), args.idleSeconds());
    }

    @Action(value = "setUrl", argsType = UrlArgs.class)
    public ActionResult setUrl(UrlArgs args) {
        return pojo().setUrl(args.url());
    }

    /**
     * Sets the model identifier used for subsequent requests.
     *
     * @param args the model identifier to be used by the vLLM server
     */
    @Action(value = "setModel", argsType = ModelArgs.class)
    public ActionResult setModel(ModelArgs args) {
        return pojo().setModel(args.model());
    }

    /**
     * Segments the provided text into its smallest units of explanation.
     *
     * @param args the text to be segmented
     *
     * @return an action result that is a success and whose message holds the segmented text, or a failure if the
     * segmentation could not be completed
     */
    @Action(value = "segment", argsType = TextArgs.class)
    public ActionResult segment(TextArgs args) {
        return pojo().segment(args.text());
    }

    /**
     * Converts the provided text into hiragana.
     *
     * @param args an object containing the text to be converted
     * @return an action result that is a success and contains the hiragana text in its message, or is not a success if
     * the conversion failed
     */
    @Action(value = "toHiragana", argsType = TextArgs.class)
    public ActionResult toHiragana(TextArgs args) {
        return pojo().toHiragana(args.text());
    }

}