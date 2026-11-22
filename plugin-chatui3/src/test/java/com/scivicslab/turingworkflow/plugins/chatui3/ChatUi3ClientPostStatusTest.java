package com.scivicslab.turingworkflow.plugins.chatui3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Checks that {@link ChatUi3Client#chat} fails at once when the chat POST is refused, instead of reading an SSE
 * stream on which no result will come, and that it closes that stream.
 *
 * <p>A local server stands in for quarkus-chat-ui3: its SSE endpoint keeps the stream open and writes a comment line
 * every 100 ms until the client closes it.</p>
 */
@DisplayName("ChatUi3Client - a refused chat POST fails at once and closes the SSE stream")
class ChatUi3ClientPostStatusTest {

    private HttpServer server;
    private final CountDownLatch sseClosed = new CountDownLatch(1);
    private volatile int sseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/chat/stream", this::stream);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "model not loaded".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
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

    @Test
    void chat_postReturns500_failsWithStatusAndBodyAndClosesSse() throws Exception {
        ChatUi3Client client = new ChatUi3Client();
        client.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.chat("hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("500").contains("model not loaded");
        assertThat(sseClosed.await(10, TimeUnit.SECONDS)).as("SSE stream closed by the client").isTrue();
        client.shutdown();
    }

    @Test
    void chat_sseReturns503_failsWithStatus() throws Exception {
        sseStatus = 503;
        ChatUi3Client client = new ChatUi3Client();
        client.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        ActionResult result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> client.chat("hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("503");
        client.shutdown();
    }

    private void stream(HttpExchange exchange) throws IOException {
        if (sseStatus != 200) {
            byte[] body = "busy".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(sseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            while (true) {
                out.write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            }
        } catch (IOException e) {
            sseClosed.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
