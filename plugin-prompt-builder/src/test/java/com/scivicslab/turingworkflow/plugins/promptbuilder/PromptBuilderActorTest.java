package com.scivicslab.turingworkflow.plugins.promptbuilder;

import com.scivicslab.pojoactor.action.ActionResult;
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
        actor.addWarning(new PromptBuilderActor.TextArgs("ファイルを書き換えないこと"));
        actor.addWarning(new PromptBuilderActor.TextArgs("git push の前に確認すること"));
        actor.addContext(new PromptBuilderActor.TextArgs("対象リポジトリ: oogasawa/k8s-tree"));
        actor.addMessage(new PromptBuilderActor.TextArgs("README.md を追加してください。"));

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
        actor.addMessage(new PromptBuilderActor.TextArgs("タスク本文のみ"));

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
        actor.addWarning(new PromptBuilderActor.TextArgs("何か制約"));

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("addMessage has not been called");
    }

    @Test
    void clear_resetsAllSections() {
        actor.addWarning(new PromptBuilderActor.TextArgs("警告"));
        actor.addContext(new PromptBuilderActor.TextArgs("背景"));
        actor.addMessage(new PromptBuilderActor.TextArgs("メッセージ"));
        actor.clear(null);

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void addWarning_withBlankText_fails() {
        ActionResult result = actor.addWarning(new PromptBuilderActor.TextArgs("   "));
        assertThat(result.isSuccess()).isFalse();
    }

    // --- JSON-array unwrapping (Interpreter wraps plain-string args as ["value"]) ---

    @Test
    void addMessage_overwritesPreviousMessage() {
        actor.addMessage(new PromptBuilderActor.TextArgs("最初のメッセージ"));
        actor.addMessage(new PromptBuilderActor.TextArgs("上書きされたメッセージ"));

        ActionResult result = actor.build(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("上書きされたメッセージ");
        assertThat(result.getResult()).doesNotContain("最初のメッセージ");
    }
}
