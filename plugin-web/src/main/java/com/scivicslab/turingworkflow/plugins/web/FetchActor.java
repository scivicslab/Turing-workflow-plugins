package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link PageFetcher} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code PageFetcher} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class FetchActor extends IIActorRef<PageFetcher> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public FetchActor(String name, IIActorSystem system) {
        super(name, new PageFetcher(), system);
    }

    private PageFetcher pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("fetch")
    public ActionResult fetch(String args) {
        return pojo().fetch(args);
    }

}