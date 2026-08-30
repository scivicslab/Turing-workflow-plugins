package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link ChatUi3Client} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code ChatUi3Client} である
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 *
 * <p>生成のたびに専用の見張り役を子アクターとして立てる。{@code chat} はSSEを読んで止まるので、
 * 別のスレッドから止められる相手が要る。</p>
 */
public class ChatUi3Actor extends IIActorRef<ChatUi3Client> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public ChatUi3Actor(String name, IIActorSystem system) {
        super(name, new ChatUi3Client(), system, self ->
                self.addChildActor(new ChatUi3WatchdogActor(
                        self.getName() + "-watchdog", (ChatUi3Actor) self, system)));
    }

    private ChatUi3Client client() {
        return object;
    }

    /** 進行中のSSEを外から止める。見張り役がこれを呼ぶ。 */
    public void stopSse() {
        client().stopSse();
    }

    /** アクター終了時に、包んだ相手の後片付けを先に行う。 */
    @Override
    public void close() {
        client().shutdown();
        super.close();
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("stopChat")
    public ActionResult stopChat(String args) {
        return client().stopChat(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("setBaseUrl")
    public ActionResult setBaseUrl(String args) {
        return client().setBaseUrl(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("chat")
    public ActionResult chat(String args) {
        return client().chat(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getTrace")
    public ActionResult getTrace(String args) {
        return client().getTrace(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("updateConfig")
    public ActionResult updateConfig(String args) {
        return client().updateConfig(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getConfig")
    public ActionResult getConfig(String args) {
        return client().getConfig(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("clearHistory")
    public ActionResult clearHistory(String args) {
        return client().clearHistory(args);
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action("getModels")
    public ActionResult getModels(String args) {
        return client().getModels(args);
    }

}