package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link VllmClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code VllmClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class VllmActor extends IIActorRef<VllmClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
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
     * Where the vLLM server listens.
     *
     * @param url the server's address
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * Which model answers.
     *
     * @param model the model's identifier
     */
    public record ModelArgs(@NotNull String model) {}

    /**
     * The text the model works on.
     *
     * @param text the text to segment or to read aloud in hiragana
     */
    public record TextArgs(@NotNull String text) {}

    @Action(value = "setUrl", argsType = UrlArgs.class)
    public ActionResult setUrl(UrlArgs args) {
        return pojo().setUrl(args.url());
    }

    /**
     * @param modelName ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setModel", argsType = ModelArgs.class)
    public ActionResult setModel(ModelArgs args) {
        return pojo().setModel(args.model());
    }

    /**
     * @param ocrText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "segment", argsType = TextArgs.class)
    public ActionResult segment(TextArgs args) {
        return pojo().segment(args.text());
    }

    /**
     * @param segmentedText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "toHiragana", argsType = TextArgs.class)
    public ActionResult toHiragana(TextArgs args) {
        return pojo().toHiragana(args.text());
    }

}