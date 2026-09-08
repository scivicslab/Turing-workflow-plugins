package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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
     * Which file the pages are read from.
     *
     * @param path the file's path
     */
    public record FileArgs(@NotNull String path) {}

    @Action(value = "loadFile", argsType = FileArgs.class)
    public ActionResult loadFile(FileArgs args) {
        return pojo().loadFile(args.path());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("nextPage")
    public ActionResult nextPage(String args) {
        return pojo().nextPage();
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getPageText")
    public ActionResult getPageText(String args) {
        return pojo().getPageText();
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getPageInfo")
    public ActionResult getPageInfo(String args) {
        return pojo().getPageInfo();
    }

}