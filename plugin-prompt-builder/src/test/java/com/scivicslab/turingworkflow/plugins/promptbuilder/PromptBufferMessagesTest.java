package com.scivicslab.turingworkflow.plugins.promptbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scivicslab.pojoactor.action.ActionResult;
import org.junit.jupiter.api.Test;

/** {@link PromptBuffer#buildMessages}: the {@code messages} array of a request. */
class PromptBufferMessagesTest {

    @Test
    void constraintsAndContextBecomeTheSystemMessageAndTheBodyTheUserMessage() {
        PromptBuffer b = new PromptBuffer();
        b.addWarning("do not use tools");
        b.addContext("book: Networking");
        b.setMessage("pick the chapter starts");

        assertThat(b.buildMessages(null)).isEqualTo(
                "[{\"role\":\"system\",\"content\":\"[Constraints]\\n- do not use tools\\n\\n[Context]\\n- book: Networking\"},"
                        + "{\"role\":\"user\",\"content\":\"pick the chapter starts\"}]");
    }

    @Test
    void withoutConstraintsOrContextThereIsNoSystemMessage() {
        PromptBuffer b = new PromptBuffer();
        b.setMessage("ping");

        assertThat(b.buildMessages("[]")).isEqualTo("[{\"role\":\"user\",\"content\":\"ping\"}]");
    }

    @Test
    void theGivenHistoryComesBetweenTheSystemMessageAndTheUserMessage() {
        PromptBuffer b = new PromptBuffer();
        b.addWarning("short answers");
        b.setMessage("next");

        String messages = b.buildMessages("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"a\"}]");

        assertThat(messages).isEqualTo(
                "[{\"role\":\"system\",\"content\":\"[Constraints]\\n- short answers\"},"
                        + "{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"a\"},"
                        + "{\"role\":\"user\",\"content\":\"next\"}]");
    }

    @Test
    void quotesAndControlCharactersAreEscaped() {
        PromptBuffer b = new PromptBuffer();
        b.setMessage("say \"hi\"\tand\\go");

        assertThat(b.buildMessages(null)).isEqualTo("[{\"role\":\"user\",\"content\":\"say \\\"hi\\\"\\tand\\\\go\"}]");
    }

    @Test
    void withoutAMessageThereIsNothingToBuild() {
        PromptBuffer b = new PromptBuffer();
        b.addWarning("w");

        assertThat(b.buildMessages(null)).isNull();
    }

    @Test
    void aHistoryThatIsNotAnArrayIsRefused() {
        PromptBuffer b = new PromptBuffer();
        b.setMessage("m");

        assertThatThrownBy(() -> b.buildMessages("{\"role\":\"user\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theActorActionReturnsTheArrayOrTheReasonItFailed() {
        PromptBuilderActor actor = new PromptBuilderActor("t", null);
        ActionResult noMessage = actor.buildMessages(new PromptBuilderActor.TextArgs("[]"));
        assertThat(noMessage.isSuccess()).isFalse();

        actor.addMessage(new PromptBuilderActor.TextArgs("m"));
        assertThat(actor.buildMessages(new PromptBuilderActor.TextArgs("[]")).getResult())
                .isEqualTo("[{\"role\":\"user\",\"content\":\"m\"}]");
        assertThat(actor.buildMessages(new PromptBuilderActor.TextArgs("oops")).isSuccess()).isFalse();
    }
}
