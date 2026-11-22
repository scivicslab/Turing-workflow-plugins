package com.scivicslab.turingworkflow.plugins.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Checks that {@code callOpenAi} ends a response that stalls after its body began at the idle limit, and a request
 * that is never answered at the total limit, failing with the URL, the model and the limit that was hit.
 */
@DisplayName("LlmClient.callOpenAi - total and idle limits")
class LlmClientTimeLimitTest {

    @Test
    void callOpenAi_bodyStallsAfterFirstBytes_failsAtIdleLimit() throws Exception {
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.STALL_INSIDE_BODY)) {
            LlmClient client = new LlmClient();
            client.setOpenAiUrl(server.url(), "test-model");
            client.setTimeLimits(30, 1);

            ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.callOpenAi("hello"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).contains(server.hostAndPort()).contains("test-model").contains("idle limit");
        }
    }

    @Test
    void callOpenAi_neverAnswered_failsAtTotalLimit() throws Exception {
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.NEVER_ANSWER)) {
            LlmClient client = new LlmClient();
            client.setOpenAiUrl(server.url(), "test-model");
            client.setTimeLimits(1, 60);

            ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.callOpenAi("hello"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).contains(server.hostAndPort()).contains("test-model").contains("total limit");
        }
    }
}
