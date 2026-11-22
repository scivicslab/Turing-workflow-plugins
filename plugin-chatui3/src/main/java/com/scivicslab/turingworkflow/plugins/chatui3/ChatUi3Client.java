package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.action.ActionResult;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Communicates with a quarkus-chat-ui3 server to send messages to an LLM and manage its configuration.
 *
 * <ul><li>{@code setBaseUrl} - sets the server's base URL</li><li>{@code chat} - sends a message and returns the LLM's
 * response text via an SSE stream</li><li>{@code getTrace} - returns a JSON array of all I/O records for the current
 * session</li><li>{@code updateConfig} - performs a partial update of the server configuration</li><li>{@code
 * getConfig} - returns the current configuration as a JSON object</li><li>{@code clearHistory} - deletes the
 * conversation history and trace</li><li>{@code getModels} - returns a JSON object containing available model
 * IDs</li><li>{@code stopChat} - aborts an active chat session</li></ul>
 *
 * <p>The client holds an active SSE connection during a {@code chat} call, which can be closed by {@code stopChat} or
 * {@code shutdown}.</p>
 *
 * <p>One instance may serve several workflows at once, but only one {@code chat} call can be active per instance; a
 * new {@code chat} call will displace any existing SSE connection.</p>
 *
 * <p>Example workflow step:</p>
 * <pre>{@code
 * actor: chat_ui_client
 * method: chat
 * arguments:
 *   message: "Hello, how are you?"
 * }</pre>
 *
 * <p>If a {@code chat} call fails, the action result is not a success and the message holds the error description or
 * the HTTP status code.</p>
 */
public class ChatUi3Client {

    private static final Logger LOG = Logger.getLogger(ChatUi3Client.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private String baseUrl = "http://localhost:18090";

    // HTTP/1.1 forced: quarkus-chat-ui3 (Vert.x) does not require HTTP/2,
    // and HTTP/2 negotiation can cause request body loss in some configurations.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The SSE response body stream of an in-flight {@code chat()} call, or {@code null} if no chat is active. */
    private volatile InputStream activeSse;


    /**
     * Closes the underlying {@link HttpClient} before the normal actor cleanup.
     *
     * <p>{@code java.net.http.HttpClient} runs a non-daemon selector-manager thread that
     * stays alive while the client is referenced. This actor holds the client in a field,
     * so without closing it the thread keeps the JVM alive after the workflow finishes.
     * This {@code close()} is invoked by {@code IIActorSystem.terminateIIActors()} during
     * workflow shutdown, letting the process exit on its own without relying on
     * {@code System.exit}.</p>
     */
    /**
     * Stops any active SSE stream and closes the HTTP client.
     *
     * <p>This is called during workflow shutdown to ensure the underlying HTTP client's non-daemon threads are
     * terminated, allowing the JVM to exit. If a chat is in progress, the stream is aborted first.</p>
     */
    public void shutdown() {
        stopSse();
        http.close();
    }

    /**
     * Terminates the in-flight SSE stream of an active {@code chat()} call, if any.
     *
     * <p>Closing the underlying stream causes the blocked read inside {@code chat()} to throw, allowing that action to
     * return instead of waiting indefinitely for a result. This is used during shutdown or via the {@code stopChat}
     * action to abort a stuck chat.
     */
    public void stopSse() {
        InputStream s = this.activeSse;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "stopSse: closing active SSE stream failed", e);
            }
        }
    }

    /**
     * Aborts an active {@code chat()} call by force-closing its underlying SSE stream.
     *
     * Returns an action result that is a success with the message "SSE stream stopped".
     */
    public ActionResult stopChat() {
        stopSse();
        return new ActionResult(true, "SSE stream stopped");
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Sets the base URL for the quarkus-chat-ui3 service.
     *
     * @param url the base address, e.g. "http://192.168.5.14:18090"
     * @return an ActionResult that is a success and contains the trimmed URL if the setting succeeded.
     */
    public ActionResult setBaseUrl(String url) {
        this.baseUrl = url.trim();
        LOG.info("ChatUi3 base URL set to: " + this.baseUrl);
        return new ActionResult(true, this.baseUrl);
    }

    // ── Core primitive: chat ──────────────────────────────────────────────────

    /**
     * Sends a message to the LLM via quarkus-chat-ui3 and returns the response text.
     *
     * @param message the text sent to the model
     * @return an action result that is a success with the LLM response text in its message, or a failure with an error
     * message if the connection fails, the stream ends without a result, or the server returns an error event
     */
    public ActionResult chat(String message) {

        try {
            // Step 1: Open SSE connection.
            // BodyHandlers.ofInputStream() returns after response headers are received,
            // meaning the SSE connection is established before we proceed.
            HttpRequest sseReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<InputStream> sseResp =
                    http.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = sseResp.body();

            if (sseResp.statusCode() != 200) {
                closeQuietly(body);
                return new ActionResult(false,
                        "SSE connection to " + baseUrl + "/api/chat/stream failed with HTTP " + sseResp.statusCode());
            }

            // Step 2: SSE connection is now established.
            // Send POST /api/chat — the server will push events into the SSE stream.
            // A refused POST means no event will come, so the stream is closed and the call fails at once.
            String reqBody = "{\"message\":" + JSONObject.quote(message) + "}";
            HttpRequest chatReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> chatResp;
            try {
                chatResp = http.send(chatReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                closeQuietly(body);
                throw e;
            }
            if (chatResp.statusCode() < 200 || chatResp.statusCode() >= 300) {
                closeQuietly(body);
                return new ActionResult(false, "POST " + baseUrl + "/api/chat returned HTTP " + chatResp.statusCode()
                        + ": " + chatResp.body());
            }

            // Step 3: Read SSE stream until result or error.
            // Publish the stream so stopSse()/close() can force this read to abort.
            this.activeSse = body;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("data:")) continue;

                    String json = line.substring(5).trim();
                    JSONObject event = new JSONObject(json);
                    String type = event.optString("type", "");

                    switch (type) {
                        case "result":
                            return new ActionResult(true, event.optString("text", ""));
                        case "error":
                            return new ActionResult(false, event.optString("message", "error"));
                        case "delta":
                            // Log delta for visibility; Turing Workflow gets the full result at end
                            LOG.fine("delta: " + event.optString("text", ""));
                            break;
                        default:
                            break;
                    }
                }
            } finally {
                this.activeSse = null;
            }

            return new ActionResult(false, "SSE stream ended without result event");

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "chat action failed", e);
            return new ActionResult(false, e.getMessage());
        }
    }

    // ── Trace ─────────────────────────────────────────────────────────────────

    /**
     * Returns a JSON array of all I/O records for the current session.
     *
     * @return an ActionResult containing a JSON array of IoPair objects if successful, or a failure message if the
     * request fails.
     */
    public ActionResult getTrace() {
        return get("/api/trace");
    }

    // ── Config ────────────────────────────────────────────────────────────────

    /**
     * Performs a partial update of the server configuration.
     *
     * Returns an ActionResult that is a success if the configuration was updated, or a failure with an error message
     * if no fields were provided or the server rejected the request.
     *
     * @param vllmBaseUrl the address of the vLLM server, or {@code null} to leave it unchanged
     * @param modelId the identifier of the model to use, or {@code null} to leave it unchanged
     * @param temperature the sampling temperature, or {@code null} to leave it unchanged
     * @param maxTokens the maximum number of tokens for an answer, or {@code null} to leave it unchanged
     * @return an ActionResult indicating success or the error message from the server
     */
    public ActionResult updateConfig(String vllmBaseUrl, String modelId,
                                    Double temperature, Integer maxTokens) {
        JSONObject patch = new JSONObject();
        if (vllmBaseUrl != null) patch.put("vllmBaseUrl", vllmBaseUrl);
        if (modelId     != null) patch.put("modelId",     modelId);
        if (temperature != null) patch.put("temperature", temperature.doubleValue());
        if (maxTokens   != null) patch.put("maxTokens",   maxTokens.intValue());
        if (patch.isEmpty()) {
            return new ActionResult(false, "updateConfig: no configuration field given");
        }
        return post("/api/config", patch.toString());
    }

    /**
     * Returns a JSON object containing the current configuration settings via GET /api/config.
     *
     * @return an ActionResult that is a success with a message holding the JSON configuration object, or a failure
     * with an error message if the request fails.
     */
    public ActionResult getConfig() {
        return get("/api/config");
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Resets the conversation history and the I/O trace on the server.
     *
     * @return an action result that is a success if the history was deleted, or contains an error message if the
     * server returned a non-success status code.
     */
    public ActionResult clearHistory() {
        return delete("/api/history");
    }

    // ── Models ────────────────────────────────────────────────────────────────

    /**
     * Returns an action result containing a JSON object of available model IDs from the vLLM server.
     *
     * @return an action result that is a success with a message holding a JSON object of model IDs, or a failure if
     * the request fails
     */
    public ActionResult getModels() {
        return get("/api/models");
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "closing the SSE stream failed", e);
        }
    }

    private ActionResult get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 300
                    ? new ActionResult(true, resp.body())
                    : new ActionResult(false, "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    private ActionResult post(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 300
                    ? new ActionResult(true, resp.body())
                    : new ActionResult(false, "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    private ActionResult delete(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .DELETE()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 300
                    ? new ActionResult(true, resp.body())
                    : new ActionResult(false, "HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return new ActionResult(false, e.getMessage());
        }
    }
}
