package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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
    /**
     * Which file the pairs are written to.
     *
     * @param path the output file's path
     */
    public record FileArgs(@NotNull String path) {}

    /**
     * Which page the pairs that follow come from.
     *
     * @param pageInfo the page's description
     */
    public record PageInfoArgs(@NotNull String pageInfo) {}

    /**
     * The text the model returned.
     *
     * @param response the model's answer
     */
    public record ResponseArgs(@NotNull String response) {}

    @Action(value = "openOutput", argsType = FileArgs.class)
    public ActionResult openOutput(FileArgs args) {
        return pojo().openOutput(args.path());
    }

    /**
     * @param pageInfo ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setPageInfo", argsType = PageInfoArgs.class)
    public ActionResult setPageInfo(PageInfoArgs args) {
        return pojo().setPageInfo(args.pageInfo());
    }

    /**
     * @param response ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "checkHiragana", argsType = ResponseArgs.class)
    public ActionResult checkHiragana(ResponseArgs args) {
        return pojo().checkHiragana(args.response());
    }

    /**
     * @param response ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "writePairs", argsType = ResponseArgs.class)
    public ActionResult writePairs(ResponseArgs args) {
        return pojo().writePairs(args.response());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("closeOutput")
    public ActionResult closeOutput(String args) {
        return pojo().closeOutput();
    }

}