package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link SectionIterator} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code SectionIterator} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class SectionIteratorActor extends IIActorRef<SectionIterator> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public SectionIteratorActor(String name, IIActorSystem system) {
        super(name, new SectionIterator(), system);
    }

    private SectionIterator pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The markdown to split into sections.
     *
     * @param markdown the text produced by OCR
     */
    public record MarkdownArgs(@NotNull String markdown) {}

    /**
     * How short a section may be before it is dropped.
     *
     * @param minChars the smallest section kept, in characters
     */
    public record MinCharsArgs(@NotNull Integer minChars) {}

    @Action(value = "extractSections", argsType = MarkdownArgs.class)
    public ActionResult extractSections(MarkdownArgs args) {
        return pojo().extractSections(args.markdown());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getNext")
    public ActionResult getNext(String args) {
        return pojo().getNext();
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setMinChars", argsType = MinCharsArgs.class)
    public ActionResult setMinChars(MinCharsArgs args) {
        return pojo().setMinChars(args.minChars());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("reset")
    public ActionResult reset(String args) {
        return pojo().reset();
    }

}