package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Shutdown of a ChatUi3Actor and its watchdog child, in either order. No server is contacted.
 */
class ChatUi3ActorCloseTest {

    private IIActorSystem system;
    private ChatUi3Actor chat;
    private ChatUi3WatchdogActor watchdog;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("chatui3-close-test");
        chat = new ChatUi3Actor("chat", system);
        system.addIIActor(chat);
        watchdog = (ChatUi3WatchdogActor) system.getIIActor("chat-watchdog");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("the watchdog closes without error after the chat actor it guards is closed")
    void watchdogClosesAfterChatActor() {
        chat.close();

        assertThatCode(watchdog::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("closing the chat actor closes its watchdog first")
    void chatActorClosesWatchdog() {
        chat.close();

        assertThat(system.hasActor("chat-watchdog")).isFalse();
        assertThat(system.hasActor("chat")).isFalse();
    }

    @Test
    @DisplayName("terminateIIActors closes every actor without error")
    void terminateIIActorsCompletes() {
        assertThatCode(system::terminateIIActors).doesNotThrowAnyException();

        assertThat(system.hasActor("chat")).isFalse();
        assertThat(system.hasActor("chat-watchdog")).isFalse();
    }
}
