package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link OcrClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code OcrClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class OcrActor extends IIActorRef<OcrClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public OcrActor(String name, IIActorSystem system) {
        super(name, new OcrClient(), system);
    }

    private OcrClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setMarkerUrl")
    public ActionResult setMarkerUrl(String args) {
        return pojo().setMarkerUrl(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setYomitokuUrl")
    public ActionResult setYomitokuUrl(String args) {
        return pojo().setYomitokuUrl(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("markerOcr")
    public ActionResult markerOcr(String args) {
        return pojo().markerOcr(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("yomitokuOcr")
    public ActionResult yomitokuOcr(String args) {
        return pojo().yomitokuOcr(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("ocr")
    public ActionResult ocr(String args) {
        return pojo().ocr(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("writeFile")
    public ActionResult writeFile(String args) {
        return pojo().writeFile(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("downloadPdf")
    public ActionResult downloadPdf(String args) {
        return pojo().downloadPdf(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("markerOcrPage")
    public ActionResult markerOcrPage(String args) {
        return pojo().markerOcrPage(args);
    }

}