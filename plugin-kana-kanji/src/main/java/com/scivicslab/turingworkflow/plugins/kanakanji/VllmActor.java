package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
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
    @Action("setUrl")
    public ActionResult setUrl(String url) {
        return pojo().setUrl(url);
    }

    /**
     * @param modelName ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setModel")
    public ActionResult setModel(String modelName) {
        return pojo().setModel(modelName);
    }

    /**
     * @param ocrText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("segment")
    public ActionResult segment(String ocrText) {
        return pojo().segment(ocrText);
    }

    /**
     * @param segmentedText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("toHiragana")
    public ActionResult toHiragana(String segmentedText) {
        return pojo().toHiragana(segmentedText);
    }

}