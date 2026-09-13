package com.scivicslab.turingworkflow.plugins.semanticscholar;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link SemanticScholarClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code SemanticScholarClient} のほうであり、これが
 * {@code ActorRef} の前提である（{@code ActorSuffixAndOwnedActorRef_260722_oo01}）。</p>
 */
public class SemanticScholarActor extends IIActorRef<SemanticScholarClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public SemanticScholarActor(String name, IIActorSystem system) {
        super(name, new SemanticScholarClient(), system);
    }

    private SemanticScholarClient pojo() {
        return object;
    }

    /**
     * The key to send as {@code x-api-key}; without one the shared pool is used.
     *
     * @param apiKey the key Semantic Scholar issued
     */
    public record ApiKeyArgs(@NotNull String apiKey) {}

    /**
     * What to search for.
     *
     * @param query the search terms
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * What to search for, how many to return, and how to narrow it.
     *
     * @param query  the search terms
     * @param limit  how many papers to return; ten when absent
     * @param year   a year or a range such as {@code 2020-2024}; every year when absent
     * @param fields a field of study such as {@code Computer Science}; every field when absent
     */
    public record TopPapersArgs(@NotNull String query, Integer limit, String year, String fields) {}

    /**
     * Which paper to look up.
     *
     * @param id a Semantic Scholar id, a DOI, or an arXiv id
     */
    public record PaperArgs(@NotNull String id) {}

    /**
     * Which paper's citations or references, and how many.
     *
     * @param id    the paper
     * @param limit how many to return; ten when absent
     */
    public record RelatedArgs(@NotNull String id, Integer limit) {}

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setApiKey", argsType = ApiKeyArgs.class)
    public ActionResult setApiKey(ApiKeyArgs args) {
        return pojo().setApiKey(args.apiKey());
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
        return pojo().searchPapersTopK(args.query(), args.limit(), args.year(), args.fields());
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
    @Action(value = "getCitations", argsType = RelatedArgs.class)
    public ActionResult getCitations(RelatedArgs args) {
        return pojo().getCitations(args.id(), args.limit());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "getReferences", argsType = RelatedArgs.class)
    public ActionResult getReferences(RelatedArgs args) {
        return pojo().getReferences(args.id(), args.limit());
    }
}
