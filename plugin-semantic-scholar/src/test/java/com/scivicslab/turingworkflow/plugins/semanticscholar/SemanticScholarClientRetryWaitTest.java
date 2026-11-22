package com.scivicslab.turingworkflow.plugins.semanticscholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Checks that a server asking for a long {@code Retry-After} cannot hold a request for longer than the configured
 * longest wait per retry, and that the number of retries stays as it was.
 *
 * <p>A local server answers every request with HTTP 429 and {@code Retry-After: 3600}. Without the cap the first
 * retry alone would wait an hour and later ones three times longer each.</p>
 */
@DisplayName("SemanticScholarClient - a long Retry-After is capped per wait and the retry count is kept")
class SemanticScholarClientRetryWaitTest {

    @AfterEach
    void clearLimit() {
        System.clearProperty(SemanticScholarClient.MAX_RETRY_WAIT_PROPERTY);
    }

    @Test
    void get_serverAsksForAnHour_waitsAtMostTheCapAndKeepsTheRetryCount() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "slow down".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "3600");
            exchange.sendResponseHeaders(429, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException e) {
                // the client went away
            }
        });
        server.start();
        try {
            System.setProperty(SemanticScholarClient.MAX_RETRY_WAIT_PROPERTY, "1");
            SemanticScholarClient client = new SemanticScholarClient();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/query";

            // 4 requests at least 3 s apart (the API pacing) plus 3 waits of at most 1 s
            Exception failure = assertTimeoutPreemptively(Duration.ofSeconds(40),
                    () -> assertThrows(Exception.class, () -> client.get(url)));

            assertThat(failure.getMessage()).contains("HTTP 429");
            assertThat(requests.get()).isEqualTo(4);
        } finally {
            server.stop(0);
        }
    }
}
