package com.scivicslab.turingworkflow.plugins.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.sun.net.httpserver.HttpServer;

/** Checks that {@code callOpenAi} fails when the model's reply was cut off at the token limit or is empty. */
@DisplayName("LlmClient.callOpenAi - a cut-off or empty reply is a failure")
class LlmClientReplyTest {

    private HttpServer server;
    private volatile String content;
    private volatile String finishReason;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", content))
                    .put("finish_reason", finishReason))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @Test
    void callOpenAi_finishReasonLength_fails() {
        content = "the answer is cut";
        finishReason = "length";
        LlmClient client = new LlmClient();
        client.setOpenAiUrl(url(), "test-model");

        ActionResult result = client.callOpenAi("hello");

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("reply cut off");
    }

    @Test
    void callOpenAi_emptyContent_fails() {
        content = "  ";
        finishReason = "stop";
        LlmClient client = new LlmClient();
        client.setOpenAiUrl(url(), "test-model");

        ActionResult result = client.callOpenAi("hello");

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("empty reply");
    }

    @Test
    void callOpenAi_wholeReply_returnsIt() {
        content = "the answer";
        finishReason = "stop";
        LlmClient client = new LlmClient();
        client.setOpenAiUrl(url(), "test-model");

        ActionResult result = client.callOpenAi("hello");

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(result.getResult()).isEqualTo("the answer");
    }
}
