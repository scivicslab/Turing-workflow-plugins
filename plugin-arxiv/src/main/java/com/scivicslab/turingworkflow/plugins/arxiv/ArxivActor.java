package com.scivicslab.turingworkflow.plugins.arxiv;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link ArxivClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code ArxivClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01}）。</p>
 */
public class ArxivActor extends IIActorRef<ArxivClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public ArxivActor(String name, IIActorSystem system) {
        super(name, new ArxivClient(), system);
    }

    private ArxivClient pojo() {
        return object;
    }

    /**
     * The address arXiv asks callers to send along with each request.
     *
     * @param email the caller's mail address
     */
    public record EmailArgs(@NotNull String email) {}

    /**
     * What to search arXiv for.
     *
     * @param query the search terms; arXiv's own field prefixes are passed through
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * What to search for, how many papers to return, and in what order.
     *
     * @param query      the search terms
     * @param maxResults how many papers to return; ten when absent
     * @param sort       {@code relevance}, {@code newest} or {@code updated}; relevance when absent
     */
    public record TopPapersArgs(@NotNull String query, Integer maxResults, String sort) {}

    /**
     * Which paper to look up.
     *
     * @param id the arXiv id, with or without its version, prefix or URL
     */
    public record PaperArgs(@NotNull String id) {}

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setEmail", argsType = EmailArgs.class)
    public ActionResult setEmail(EmailArgs args) {
        return pojo().setEmail(args.email());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchPapers", argsType = QueryArgs.class)
    public ActionResult searchPapers(QueryArgs args) {
        return pojo().searchPapers(args.query());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchPapersTopK", argsType = TopPapersArgs.class)
    public ActionResult searchPapersTopK(TopPapersArgs args) {
        return pojo().searchPapersTopK(args.query(), args.maxResults(), args.sort());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "getPaper", argsType = PaperArgs.class)
    public ActionResult getPaper(PaperArgs args) {
        return pojo().getPaper(args.id());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "getPdfUrl", argsType = PaperArgs.class)
    public ActionResult getPdfUrl(PaperArgs args) {
        return pojo().getPdfUrl(args.id());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "searchByAuthor", argsType = QueryArgs.class)
    public ActionResult searchByAuthor(QueryArgs args) {
        return pojo().searchByAuthor(args.query());
    }
}
