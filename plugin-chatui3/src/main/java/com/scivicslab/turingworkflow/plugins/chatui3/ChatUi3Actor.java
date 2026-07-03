package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
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
 * Turing Workflow actor that calls quarkus-chat-ui3 primitives.
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code setBaseUrl}    - Set quarkus-chat-ui3 base URL (default: http://localhost:18090)</li>
 *   <li>{@code chat}          - Send a message via POST /api/chat, receive result via SSE</li>
 *   <li>{@code getTrace}      - GET /api/trace — full I/O record of the session</li>
 *   <li>{@code updateConfig}  - POST /api/config (partial update)</li>
 *   <li>{@code clearHistory}  - DELETE /api/history</li>
 *   <li>{@code getModels}     - GET /api/models</li>
 * </ul>
 *
 * <p><b>SSE note:</b> {@code chat} opens a new SSE connection for each call.
 * An existing browser connection to /api/chat/stream will be displaced.
 * Use this actor in automation contexts where no browser UI is simultaneously connected.</p>
 */
public class ChatUi3Actor extends IIActorRef<ChatUi3Actor> {

    private static final Logger LOG = Logger.getLogger(ChatUi3Actor.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private String baseUrl = "http://localhost:18090";

    // HTTP/1.1 forced: quarkus-chat-ui3 (Vert.x) does not require HTTP/2,
    // and HTTP/2 negotiation can cause request body loss in some configurations.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * The SSE response body stream of an in-flight {@code chat()} call, or {@code null}.
     * Published by {@code chat()} while it reads the stream so that {@link #stopSse()} can
     * close it from another thread and force a blocked read to abort. Marked {@code volatile}
     * because it is written by the pool thread running {@code chat()} and read/closed by the
     * thread that runs {@link #close()} or the {@code stopChat} action.
     */
    private volatile InputStream activeSse;

    public ChatUi3Actor(String name, IIActorSystem system) {
        // Always pair this LLM actor with a dedicated watchdog, attached as a CHILD of this
        // actor, whose trip/close() stops this actor's SSE stream. The companion-setup callback
        // runs on the IIActorRef layer (which knows the actor system); see the IIActorRef
        // constructor. The watchdog runs on its own thread, so it can abort a chat() that is
        // blocked reading the SSE while the workflow thread is stuck. Actors are cheap, so
        // every chat actor gets its own watchdog.
        super(name, null, system, self ->
                self.addChildActor(new ChatUi3WatchdogActor(
                        self.getName() + "-watchdog", (ChatUi3Actor) self, system)));
    }

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
    @Override
    public void close() {
        stopSse();
        http.close();
        super.close();
    }

    /**
     * Force-terminates the in-flight SSE stream of an active {@code chat()} call, if any.
     *
     * <p>Closing the underlying stream makes the blocked {@code readLine()} inside
     * {@code chat()} throw, so {@code chat()} returns instead of waiting forever for a
     * {@code result} event that never arrives. This is called from {@link #close()} during
     * shutdown, and is also exposed as the {@code stopChat} action so an external controller
     * can command this actor to stop a stuck chat without waiting for a timeout.</p>
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
     * Action wrapper for {@link #stopSse()}: lets a Turing Workflow (or an external
     * supervisor) command this actor to abort a stuck {@code chat()} via a message.
     *
     * @param args ignored
     * @return success result
     */
    @Action("stopChat")
    public ActionResult stopChat(String args) {
        stopSse();
        return new ActionResult(true, "SSE stream stopped");
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Sets the quarkus-chat-ui3 base URL.
     *
     * @param args base URL string, e.g. "http://192.168.5.14:18090"
     */
    @Action("setBaseUrl")
    public ActionResult setBaseUrl(String args) {
        this.baseUrl = args.trim();
        LOG.info("ChatUi3 base URL set to: " + this.baseUrl);
        return new ActionResult(true, this.baseUrl);
    }

    // ── Core primitive: chat ──────────────────────────────────────────────────

    /**
     * Sends a message to the LLM via quarkus-chat-ui3 and returns the response text.
     *
     * <p>Internally:
     * <ol>
     *   <li>Opens GET /api/chat/stream (SSE) — returns after headers received</li>
     *   <li>Sends POST /api/chat with the message</li>
     *   <li>Reads the SSE stream until a {@code result} or {@code error} event arrives</li>
     * </ol>
     * </p>
     *
     * @param args message text, or JSON {"message":"..."}
     * @return ActionResult with the LLM response text
     */
    @Action("chat")
    public ActionResult chat(String args) {
        String message = extractMessage(args);

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

            if (sseResp.statusCode() != 200) {
                return new ActionResult(false,
                        "SSE connection failed with HTTP " + sseResp.statusCode());
            }

            // Step 2: SSE connection is now established.
            // Send POST /api/chat — the server will push events into the SSE stream.
            String reqBody = "{\"message\":" + JSONObject.quote(message) + "}";
            HttpRequest chatReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            http.send(chatReq, HttpResponse.BodyHandlers.discarding());

            // Step 3: Read SSE stream until result or error.
            // Publish the stream so stopSse()/close() can force this read to abort.
            InputStream body = sseResp.body();
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
     * Returns all I/O records for the current session (GET /api/trace).
     *
     * @param args ignored
     * @return ActionResult with JSON array of IoPair objects
     */
    @Action("getTrace")
    public ActionResult getTrace(String args) {
        return get("/api/trace");
    }

    // ── Config ────────────────────────────────────────────────────────────────

    /**
     * Updates configuration fields (POST /api/config, partial update).
     *
     * @param args JSON patch, e.g. {"temperature":0.2} or {"modelId":"...", "maxTokens":8192}
     * @return ActionResult indicating success
     */
    @Action("updateConfig")
    public ActionResult updateConfig(String args) {
        return post("/api/config", args.trim());
    }

    /**
     * Returns the current configuration (GET /api/config).
     *
     * @param args ignored
     * @return ActionResult with JSON config object
     */
    @Action("getConfig")
    public ActionResult getConfig(String args) {
        return get("/api/config");
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Resets the conversation history and trace (DELETE /api/history).
     *
     * @param args ignored
     * @return ActionResult indicating success
     */
    @Action("clearHistory")
    public ActionResult clearHistory(String args) {
        return delete("/api/history");
    }

    // ── Models ────────────────────────────────────────────────────────────────

    /**
     * Returns the list of models available on the vLLM server (GET /api/models).
     *
     * @param args ignored
     * @return ActionResult with JSON object containing model IDs
     */
    @Action("getModels")
    public ActionResult getModels(String args) {
        return get("/api/models");
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String extractMessage(String args) {
        if (args == null) return "";
        // Turing Workflow passes action arguments as a JSON array (e.g. ["Hello"]).
        // parseFirstArgument unwraps ["..."] to the first element, or returns the
        // string unchanged when it is not a JSON array (direct Java call).
        String unwrapped = parseFirstArgument(args.trim()).trim();
        if (unwrapped.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(unwrapped);
                if (obj.has("message")) return obj.getString("message");
            } catch (Exception ignored) {}
        }
        return unwrapped;
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
