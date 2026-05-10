package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.core.ActionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for LlmActor using a dedicated AI workspace instance.
 *
 * Starts quarkus-AI-workspace on port 28200, which auto-starts MCP gateway
 * on 28201 and launches chat-ui on 28202. Tests run against this isolated
 * environment and do not depend on any production services.
 *
 * Run with: mvn test -pl plugin-llm -Dgroups=e2e
 */
@Tag("e2e")
class LlmActorE2eTest {

    private static final int PORTAL_PORT  = 28200;
    private static final int GATEWAY_PORT = 28201;
    private static final int CHAT_UI_PORT = 28202;
    // Agent name registered with MCP gateway: "chat-ui-{port}" (no "quarkus-" prefix)
    private static final String AGENT_NAME = "chat-ui-" + CHAT_UI_PORT;
    private static final String GATEWAY_URL = "http://localhost:" + GATEWAY_PORT + "/mcp/_all";

    private static final Path WORKS_DIR = Path.of(System.getProperty("user.home"), "works");
    private static final Path WORKSPACE_JAR = WORKS_DIR.resolve("quarkus-AI-workspace-2.0.0.jar");

    private static Process workspaceProcess;
    private static boolean workspaceWasAlreadyRunning = false;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startWorkspace() throws Exception {
        if (isReachable("http://localhost:" + PORTAL_PORT + "/")) {
            System.out.println("[E2E] AI workspace already running at port " + PORTAL_PORT);
            workspaceWasAlreadyRunning = true;
        } else {
            System.out.println("[E2E] Starting AI workspace on port " + PORTAL_PORT);
            workspaceProcess = new ProcessBuilder(
                    "java",
                    "-Dquarkus.http.port=" + PORTAL_PORT,
                    "-jar", WORKSPACE_JAR.toString()
            )
                    .directory(WORKS_DIR.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            waitForHttp("http://localhost:" + PORTAL_PORT + "/", 60, "AI workspace dashboard");
        }

        waitForGatewayReady(90);

        if (!isChatUiRunning()) {
            System.out.println("[E2E] Launching chat-ui via workspace API");
            httpPost(
                    "http://localhost:" + PORTAL_PORT + "/api/tool/quarkus-chat-ui/launch",
                    "{\"provider\":\"claude\",\"workdir\":\"" + WORKS_DIR + "\"}"
            );
            waitForChatUiReady(60);
        } else {
            System.out.println("[E2E] chat-ui already running at port " + CHAT_UI_PORT);
        }

        waitForAgentRegistered(AGENT_NAME, 30);
        System.out.println("[E2E] Setup complete. Agent: " + AGENT_NAME + ", Gateway: " + GATEWAY_URL);
    }

    @AfterAll
    static void stopWorkspace() {
        if (!workspaceWasAlreadyRunning && workspaceProcess != null && workspaceProcess.isAlive()) {
            System.out.println("[E2E] Stopping AI workspace");
            workspaceProcess.destroy();
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void setUrl_configuresEndpoint() {
        LlmActor actor = new LlmActor("test-llm", null);
        ActionResult result = actor.setUrl(GATEWAY_URL);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains(GATEWAY_URL);
    }

    @Test
    void callAgent_returnsResponseFromChatUi() {
        LlmActor actor = new LlmActor("test-llm", null);
        actor.setUrl(GATEWAY_URL);

        String args = "{\"agent\": \"" + AGENT_NAME + "\", "
                + "\"prompt\": \"What is 2+2? Reply with the number only.\", "
                + "\"caller\": \"e2e-test\"}";

        ActionResult result = actor.callAgent(args);

        System.out.println("[E2E] LLM response: " + result.getResult());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isNotBlank();
        assertThat(result.getResult()).doesNotContain("unknown agent");
        assertThat(result.getResult()).contains("4");
    }

    @Test
    void callAgent_withUnknownAgent_returnsErrorMessage() {
        LlmActor actor = new LlmActor("test-llm", null);
        actor.setUrl(GATEWAY_URL);

        String args = "{\"agent\": \"nonexistent-agent-xyz\", "
                + "\"prompt\": \"hello\", "
                + "\"caller\": \"e2e-test\"}";

        ActionResult result = actor.callAgent(args);

        System.out.println("[E2E] Expected-error result: " + result.getResult());
        // Gateway returns HTTP 200 with error text — not a Java exception
        assertThat(result).isNotNull();
        assertThat(result.getResult()).contains("unknown agent");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isReachable(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            int status = HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitForHttp(String url, int timeoutSecs, String label) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isReachable(url)) {
                System.out.println("[E2E] " + label + " ready at " + url);
                return;
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Timeout waiting for " + label + " at " + url);
    }

    private static void waitForGatewayReady(int timeoutSecs) throws Exception {
        System.out.println("[E2E] Waiting for MCP gateway on port " + GATEWAY_PORT);
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String status = httpGet("http://localhost:" + PORTAL_PORT + "/api/status");
                if (status.contains("quarkus-mcp-gateway") && status.contains("READY")) {
                    System.out.println("[E2E] MCP gateway READY");
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(3000);
        }
        throw new RuntimeException("Timeout waiting for MCP gateway READY");
    }

    private static boolean isChatUiRunning() {
        try {
            String status = httpGet("http://localhost:" + PORTAL_PORT + "/api/status");
            return status.contains("\"port\":" + CHAT_UI_PORT) && status.contains("READY");
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitForChatUiReady(int timeoutSecs) throws Exception {
        System.out.println("[E2E] Waiting for chat-ui on port " + CHAT_UI_PORT);
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isChatUiRunning()) {
                System.out.println("[E2E] chat-ui READY");
                return;
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException("Timeout waiting for chat-ui READY");
    }

    /** Polls /api/status progressLog until the agent registration log line appears. */
    private static void waitForAgentRegistered(String agentName, int timeoutSecs) throws Exception {
        System.out.println("[E2E] Waiting for agent registration: " + agentName);
        String registrationMarker = "Registered MCP server: " + agentName;
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String status = httpGet("http://localhost:" + PORTAL_PORT + "/api/status");
                if (status.contains(registrationMarker)) {
                    System.out.println("[E2E] Agent registered: " + agentName);
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new RuntimeException("Timeout waiting for agent registration: " + agentName);
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void httpPost(String url, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[E2E] POST " + url + " → " + resp.statusCode());
    }
}
