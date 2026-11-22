package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that each attempt of {@link ChatEndpoint#apply} ends at the idle limit when the response stalls after its
 * body began, and at the total limit when it is never answered; the 4 attempts and the sleeps between them
 * (5, 10, 15 and 20 s) stay, so each test takes about 50 s.
 */
@DisplayName("ChatEndpoint - total and idle limits per attempt")
class ChatEndpointTimeLimitTest {

    @AfterEach
    void clearLimits() {
        System.clearProperty(ChatEndpoint.TOTAL_TIMEOUT_PROPERTY);
        System.clearProperty(ChatEndpoint.IDLE_TIMEOUT_PROPERTY);
    }

    @Test
    void apply_bodyStallsAfterFirstBytes_eachAttemptFailsAtIdleLimit() throws Exception {
        System.setProperty(ChatEndpoint.TOTAL_TIMEOUT_PROPERTY, "30");
        System.setProperty(ChatEndpoint.IDLE_TIMEOUT_PROPERTY, "1");
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.STALL_INSIDE_BODY)) {
            ChatEndpoint endpoint = new ChatEndpoint(server.url(), "test-model", 10);

            IllegalStateException failure = assertTimeoutPreemptively(Duration.ofSeconds(120),
                    () -> assertThrows(IllegalStateException.class, () -> endpoint.apply("hello")));

            assertTrue(failure.getMessage().contains(server.hostAndPort()), failure.getMessage());
            assertTrue(failure.getMessage().contains("test-model"), failure.getMessage());
            assertTrue(failure.getMessage().contains("idle limit"), failure.getMessage());
            assertEquals(4, server.requests.get());
        }
    }

    @Test
    void apply_neverAnswered_eachAttemptFailsAtTotalLimit() throws Exception {
        System.setProperty(ChatEndpoint.TOTAL_TIMEOUT_PROPERTY, "1");
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.NEVER_ANSWER)) {
            ChatEndpoint endpoint = new ChatEndpoint(server.url(), "test-model", 10);

            IllegalStateException failure = assertTimeoutPreemptively(Duration.ofSeconds(120),
                    () -> assertThrows(IllegalStateException.class, () -> endpoint.apply("hello")));

            assertTrue(failure.getMessage().contains(server.hostAndPort()), failure.getMessage());
            assertTrue(failure.getMessage().contains("test-model"), failure.getMessage());
            assertTrue(failure.getMessage().contains("total limit"), failure.getMessage());
            assertEquals(4, server.requests.get());
        }
    }
}
