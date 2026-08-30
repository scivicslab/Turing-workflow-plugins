package com.scivicslab.turingworkflow.plugins.openalex;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link OpenAlexClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code OpenAlexClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class OpenAlexActor extends IIActorRef<OpenAlexClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public OpenAlexActor(String name, IIActorSystem system) {
        super(name, new OpenAlexClient(), system);
    }

    private OpenAlexClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setEmail")
    public ActionResult setEmail(String args) {
        return pojo().setEmail(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("searchWorks")
    public ActionResult searchWorks(String args) {
        return pojo().searchWorks(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("searchWorksTopK")
    public ActionResult searchWorksTopK(String args) {
        return pojo().searchWorksTopK(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getWork")
    public ActionResult getWork(String args) {
        return pojo().getWork(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getPdfUrl")
    public ActionResult getPdfUrl(String args) {
        return pojo().getPdfUrl(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("searchAuthors")
    public ActionResult searchAuthors(String args) {
        return pojo().searchAuthors(args);
    }

}