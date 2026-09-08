package com.scivicslab.turingworkflow.plugins.openalex;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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
    /**
     * The address OpenAlex asks callers to send along with each request.
     *
     * @param email the caller's mail address
     */
    public record EmailArgs(@NotNull String email) {}

    /**
     * What to search OpenAlex for.
     *
     * @param query the search terms
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * What to search for, how many papers to return, and in what order.
     *
     * @param query   the search terms
     * @param perPage how many papers to return; ten when absent
     * @param sort    the ordering: {@code citations}, {@code relevance}, {@code newest},
     *                or an OpenAlex sort string such as {@code publication_date:desc};
     *                most cited first when absent
     */
    public record TopWorksArgs(@NotNull String query, Integer perPage, String sort) {}

    /**
     * Which paper to look up.
     *
     * @param id the paper's OpenAlex identifier or its DOI
     */
    public record WorkArgs(@NotNull String id) {}

    @Action(value = "setEmail", argsType = EmailArgs.class)
    public ActionResult setEmail(EmailArgs args) {
        return pojo().setEmail(args.email());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchWorks", argsType = QueryArgs.class)
    public ActionResult searchWorks(QueryArgs args) {
        return pojo().searchWorks(args.query());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchWorksTopK", argsType = TopWorksArgs.class)
    public ActionResult searchWorksTopK(TopWorksArgs args) {
        return pojo().searchWorksTopK(args.query(), args.perPage(), args.sort());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "getWork", argsType = WorkArgs.class)
    public ActionResult getWork(WorkArgs args) {
        return pojo().getWork(args.id());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "getPdfUrl", argsType = WorkArgs.class)
    public ActionResult getPdfUrl(WorkArgs args) {
        return pojo().getPdfUrl(args.id());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchAuthors", argsType = QueryArgs.class)
    public ActionResult searchAuthors(QueryArgs args) {
        return pojo().searchAuthors(args.query());
    }

}