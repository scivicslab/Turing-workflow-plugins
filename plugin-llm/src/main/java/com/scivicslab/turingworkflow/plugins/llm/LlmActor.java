package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link LlmClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code LlmClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class LlmActor extends IIActorRef<LlmClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
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
     * The endpoint to send to.
     *
     * @param url base URL of the endpoint
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * The endpoint to send to and the model to ask for.
     *
     * @param url   base URL of the OpenAI-compatible endpoint
     * @param model model id to request; omit to keep the current one
     */
    public record OpenAiUrlArgs(@NotNull String url, String model) {}

    /**
     * The text to send.
     *
     * @param prompt the text to send
     */
    public record PromptArgs(@NotNull String prompt) {}

    /**
     * Whether the model should be asked to think before answering.
     *
     * @param enabled true to ask for it
     */
    public record EnableThinkingArgs(@NotNull Boolean enabled) {}

    @Action(value = "setDirectUrl", argsType = UrlArgs.class)
    public ActionResult setDirectUrl(UrlArgs args) {
        return pojo().setDirectUrl(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setOpenAiUrl", argsType = OpenAiUrlArgs.class)
    public ActionResult setOpenAiUrl(OpenAiUrlArgs args) {
        return pojo().setOpenAiUrl(args.url(), args.model());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setSystemPrompt", argsType = PromptArgs.class)
    public ActionResult setSystemPrompt(PromptArgs args) {
        return pojo().setSystemPrompt(args.prompt());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setEnableThinking", argsType = EnableThinkingArgs.class)
    public ActionResult setEnableThinking(EnableThinkingArgs args) {
        return pojo().setEnableThinking(String.valueOf(args.enabled()));
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "callOpenAi", argsType = PromptArgs.class)
    public ActionResult callOpenAi(PromptArgs args) {
        return pojo().callOpenAi(args.prompt());
    }

    /**
     * @param promptText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "submitDirect", argsType = PromptArgs.class)
    public ActionResult submitDirect(PromptArgs args) {
        return pojo().submitDirect(args.prompt());
    }

}