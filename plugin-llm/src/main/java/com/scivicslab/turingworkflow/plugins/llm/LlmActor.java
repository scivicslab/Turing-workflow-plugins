package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link LlmClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code LlmClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class LlmActor extends IIActorRef<LlmClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public LlmActor(String name, IIActorSystem system) {
        super(name, new LlmClient(), system);
    }

    private LlmClient pojo() {
        return object;
    }

    /**
     * @param url ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setDirectUrl")
    public ActionResult setDirectUrl(String url) {
        return pojo().setDirectUrl(url);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setOpenAiUrl")
    public ActionResult setOpenAiUrl(String args) {
        return pojo().setOpenAiUrl(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setSystemPrompt")
    public ActionResult setSystemPrompt(String args) {
        return pojo().setSystemPrompt(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setEnableThinking")
    public ActionResult setEnableThinking(String args) {
        return pojo().setEnableThinking(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("callOpenAi")
    public ActionResult callOpenAi(String args) {
        return pojo().callOpenAi(args);
    }

    /**
     * @param promptText ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("submitDirect")
    public ActionResult submitDirect(String promptText) {
        return pojo().submitDirect(promptText);
    }

}