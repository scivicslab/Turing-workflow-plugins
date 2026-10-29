package com.scivicslab.turingworkflow.plugins.promptbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChatPromptTest {

    @Test
    void eachFieldIsSetReadAndCounted() {
        ChatPrompt p = new ChatPrompt();
        assertThat(p.addConstraint("Answer in Japanese.")).isTrue();
        assertThat(p.addConstraint(" ")).isFalse();
        assertThat(p.addContext("The log is from 5.13.")).isTrue();
        assertThat(p.addTurn(ChatPrompt.Role.USER, "hi")).isTrue();
        assertThat(p.addTurn(ChatPrompt.Role.ASSISTANT, "hello")).isTrue();
        assertThat(p.setRequest("Summarize the log.")).isTrue();

        assertThat(p.constraintCount()).isEqualTo(1);
        assertThat(p.constraint(0)).isEqualTo("Answer in Japanese.");
        assertThat(p.constraint(1)).isNull();
        assertThat(p.contextCount()).isEqualTo(1);
        assertThat(p.context(0)).isEqualTo("The log is from 5.13.");
        assertThat(p.turnCount()).isEqualTo(2);
        assertThat(p.turns().get(1)).isEqualTo(new ChatPrompt.Turn(ChatPrompt.Role.ASSISTANT, "hello"));
        assertThat(p.request()).isEqualTo("Summarize the log.");
    }

    @Test
    void chatMessagesPutTheSystemMessageFirstAndTheRequestLast() {
        ChatPrompt p = new ChatPrompt();
        p.addConstraint("Answer in Japanese.");
        p.addContext("The log is \"short\".");
        p.addTurn(ChatPrompt.Role.USER, "hi");
        p.addTurn(ChatPrompt.Role.ASSISTANT, "hello");
        p.setRequest("Summarize\nthe log.");

        assertThat(p.toChatMessages()).isEqualTo("["
                + "{\"role\":\"system\",\"content\":\"[Constraints]\\n- Answer in Japanese.\\n\\n[Context]\\n- The log is \\\"short\\\".\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":\"hello\"},"
                + "{\"role\":\"user\",\"content\":\"Summarize\\nthe log.\"}]");
    }

    @Test
    void withNoConstraintsOrBackgroundThereIsNoSystemMessage() {
        ChatPrompt p = new ChatPrompt();
        p.setRequest("hi");
        assertThat(p.systemText()).isNull();
        assertThat(p.toChatMessages()).isEqualTo("[{\"role\":\"user\",\"content\":\"hi\"}]");
    }

    @Test
    void theUserPromptCarriesAllThreeUnderHeadingsAndNotTheTurns() {
        ChatPrompt p = new ChatPrompt();
        p.addConstraint("Answer in Japanese.");
        p.addContext("The log is from 5.13.");
        p.addTurn(ChatPrompt.Role.USER, "hi");
        p.setRequest("Summarize the log.");

        assertThat(p.toUserPrompt()).isEqualTo("[Constraints]\n- Answer in Japanese.\n\n[Context]\n- The log is from 5.13.\n\n"
                + "[Message]\nSummarize the log.");
    }

    @Test
    void theUserPromptLeavesOutAHeadingWithNothingUnderIt() {
        ChatPrompt p = new ChatPrompt();
        p.addContext("c");
        p.setRequest("m");
        assertThat(p.toUserPrompt()).isEqualTo("[Context]\n- c\n\n[Message]\nm");
    }

    @Test
    void neitherCanBeMadeWithoutARequest() {
        ChatPrompt p = new ChatPrompt();
        p.addConstraint("x");
        assertThatThrownBy(p::toChatMessages).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::toUserPrompt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void turnsAreReadFromAJsonArrayAndABadOneAddsNone() {
        ChatPrompt p = new ChatPrompt();
        assertThat(p.addTurns("[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"hello\"}]")).isEqualTo(2);
        assertThat(p.addTurns("[]")).isZero();
        assertThat(p.addTurns("  ")).isZero();
        assertThatThrownBy(() -> p.addTurns("[{\"role\":\"system\",\"content\":\"x\"}]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.addTurns("[{\"role\":\"user\",\"content\":\"ok\"},{\"role\":\"user\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.addTurns("{\"role\":\"user\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.turnCount()).isEqualTo(2);
    }

    @Test
    void clearRemovesEveryField() {
        ChatPrompt p = new ChatPrompt();
        p.addConstraint("a");
        p.addContext("b");
        p.addTurn(ChatPrompt.Role.USER, "c");
        p.setRequest("d");
        p.clear();
        assertThat(p.constraintCount()).isZero();
        assertThat(p.contextCount()).isZero();
        assertThat(p.turnCount()).isZero();
        assertThat(p.request()).isNull();
    }

    @Test
    void theListsHandedOutCannotBeChanged() {
        ChatPrompt p = new ChatPrompt();
        p.addConstraint("a");
        assertThatThrownBy(() -> p.constraints().add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.turns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
