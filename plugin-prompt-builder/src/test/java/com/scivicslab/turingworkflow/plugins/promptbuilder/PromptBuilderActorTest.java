package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.core.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderActorTest {

    static class StubActor extends PromptBuilderActor {
        StubActor() {
            super("test", null);
        }
    }

    private StubActor actor;

    @BeforeEach
    void setUp() {
        actor = new StubActor();
    }

    @Test
    void build_withAllSections_producesExpectedFormat() {
        actor.addWarning("ファイルを書き換えないこと");
        actor.addWarning("git push の前に確認すること");
        actor.addContext("対象リポジトリ: oogasawa/k8s-tree");
        actor.addMessage("README.md を追加してください。");

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(
            "[Constraints]\n" +
            "- ファイルを書き換えないこと\n" +
            "- git push の前に確認すること\n" +
            "\n" +
            "[Context]\n" +
            "- 対象リポジトリ: oogasawa/k8s-tree\n" +
            "\n" +
            "[Message]\n" +
            "README.md を追加してください。"
        );
    }

    @Test
    void build_withMessageOnly_omitsEmptySections() {
        actor.addMessage("タスク本文のみ");

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(
            "[Message]\n" +
            "タスク本文のみ"
        );
        assertThat(result.getResult()).doesNotContain("[Constraints]");
        assertThat(result.getResult()).doesNotContain("[Context]");
    }

    @Test
    void build_withoutMessage_fails() {
        actor.addWarning("何か制約");

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("addMessage has not been called");
    }

    @Test
    void clear_resetsAllSections() {
        actor.addWarning("警告");
        actor.addContext("背景");
        actor.addMessage("メッセージ");
        actor.clear(null);

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void addWarning_withBlankText_fails() {
        ActionResult result = actor.addWarning("   ");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void addMessage_overwritesPreviousMessage() {
        actor.addMessage("最初のメッセージ");
        actor.addMessage("上書きされたメッセージ");

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("上書きされたメッセージ");
        assertThat(result.getResult()).doesNotContain("最初のメッセージ");
    }
}
