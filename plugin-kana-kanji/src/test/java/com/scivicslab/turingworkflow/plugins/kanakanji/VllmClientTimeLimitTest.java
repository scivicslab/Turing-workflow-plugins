package com.scivicslab.turingworkflow.plugins.kanakanji;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Checks that a vLLM request whose response stalls after its body began ends at the idle limit, and one that is
 * never answered ends at the total limit, failing with the URL, the model and the limit that was hit.
 */
@DisplayName("VllmClient - total and idle limits")
class VllmClientTimeLimitTest {

    @Test
    void segment_bodyStallsAfterFirstBytes_failsAtIdleLimit() throws Exception {
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.STALL_INSIDE_BODY)) {
            VllmClient client = new VllmClient();
            client.setUrl(server.url());
            client.setModel("test-model");
            client.setTimeLimits(30, 1);

            ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.segment("text"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).contains(server.hostAndPort()).contains("test-model").contains("idle limit");
        }
    }

    @Test
    void segment_neverAnswered_failsAtTotalLimit() throws Exception {
        try (StallingChatServer server = new StallingChatServer(StallingChatServer.NEVER_ANSWER)) {
            VllmClient client = new VllmClient();
            client.setUrl(server.url());
            client.setModel("test-model");
            client.setTimeLimits(1, 60);

            ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.segment("text"));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).contains(server.hostAndPort()).contains("test-model").contains("total limit");
        }
    }
}
