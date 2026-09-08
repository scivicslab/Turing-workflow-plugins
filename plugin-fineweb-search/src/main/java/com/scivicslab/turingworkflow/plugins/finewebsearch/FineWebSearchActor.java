package com.scivicslab.turingworkflow.plugins.finewebsearch;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link FineWebSearchClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code FineWebSearchClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class FineWebSearchActor extends IIActorRef<FineWebSearchClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public FineWebSearchActor(String name, IIActorSystem system) {
        super(name, new FineWebSearchClient(), system);
    }

    private FineWebSearchClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * Where the FineWeb search server listens.
     *
     * @param url the server's address
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * What to search the FineWeb index for.
     *
     * @param query the search query
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * What to search for, and how many results to return.
     *
     * @param query the search query
     * @param topK  how many results to return; ten when absent
     */
    public record TopKArgs(@NotNull String query, Integer topK) {}

    @Action(value = "setUrl", argsType = UrlArgs.class)
    public ActionResult setUrl(UrlArgs args) {
        return pojo().setUrl(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "search", argsType = QueryArgs.class)
    public ActionResult search(QueryArgs args) {
        return pojo().search(args.query());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchTopK", argsType = TopKArgs.class)
    public ActionResult searchTopK(TopKArgs args) {
        return pojo().searchTopK(args.query(), args.topK());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("health")
    public ActionResult health(String args) {
        return pojo().health();
    }

}