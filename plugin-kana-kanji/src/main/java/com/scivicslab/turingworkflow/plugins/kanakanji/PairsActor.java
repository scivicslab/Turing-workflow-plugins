package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link PairWriter} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code PairWriter} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class PairsActor extends IIActorRef<PairWriter> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public PairsActor(String name, IIActorSystem system) {
        super(name, new PairWriter(), system);
    }

    private PairWriter pojo() {
        return object;
    }

    /**
     * @param filePath ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("openOutput")
    public ActionResult openOutput(String filePath) {
        return pojo().openOutput(filePath);
    }

    /**
     * @param pageInfo ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setPageInfo")
    public ActionResult setPageInfo(String pageInfo) {
        return pojo().setPageInfo(pageInfo);
    }

    /**
     * @param response ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("checkHiragana")
    public ActionResult checkHiragana(String response) {
        return pojo().checkHiragana(response);
    }

    /**
     * @param response ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("writePairs")
    public ActionResult writePairs(String response) {
        return pojo().writePairs(response);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("closeOutput")
    public ActionResult closeOutput(String args) {
        return pojo().closeOutput(args);
    }

}