package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Checks that {@link ChatEndpoint#apply} fails, without asking again, when the model's reply was cut off at the token
 * limit or is empty, so the batch records the item as failed with that reason; a whole reply is returned as before.
 */
@DisplayName("ChatEndpoint - a cut-off or empty reply fails the item")
class ChatEndpointReplyTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String content;
    private volatile String finishReason;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
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

    private ChatEndpoint endpoint() {
        return new ChatEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", "test-model", 10);
    }

    @Test
    void apply_finishReasonLength_failsAtOnceNamingTheLimit() {
        content = "class | 1\ncookie cut";
        finishReason = "length";

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> endpoint().apply("question"));

        assertTrue(failure.getMessage().contains("reply cut off at max_tokens=10"), failure.getMessage());
        assertEquals(1, requests.get(), "a reply cut off at the same max_tokens is not asked again");
    }

    @Test
    void apply_emptyContent_failsAtOnce() {
        content = "";
        finishReason = "stop";

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> endpoint().apply("question"));

        assertTrue(failure.getMessage().contains("empty reply"), failure.getMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void apply_blankContent_failsAtOnce() {
        content = " \n\t ";
        finishReason = "stop";

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> endpoint().apply("question"));

        assertTrue(failure.getMessage().contains("empty reply"), failure.getMessage());
    }

    @Test
    void apply_wholeReply_returnsItsText() {
        content = "class | 1";
        finishReason = "stop";

        assertEquals("class | 1", endpoint().apply("question"));
        assertEquals(1, requests.get());
    }
}
