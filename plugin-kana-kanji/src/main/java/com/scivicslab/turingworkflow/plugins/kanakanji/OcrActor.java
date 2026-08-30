package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link OcrPages} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code OcrPages} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class OcrActor extends IIActorRef<OcrPages> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public OcrActor(String name, IIActorSystem system) {
        super(name, new OcrPages(), system);
    }

    private OcrPages pojo() {
        return object;
    }

    /**
     * @param filePath ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("loadFile")
    public ActionResult loadFile(String filePath) {
        return pojo().loadFile(filePath);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("nextPage")
    public ActionResult nextPage(String args) {
        return pojo().nextPage(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getPageText")
    public ActionResult getPageText(String args) {
        return pojo().getPageText(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getPageInfo")
    public ActionResult getPageInfo(String args) {
        return pojo().getPageInfo(args);
    }

}