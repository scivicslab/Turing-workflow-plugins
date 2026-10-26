package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Communicates with LLM services via either a direct REST API for chat-ui instances or an OpenAI-compatible endpoint.
 *
 * <ul>
 * <li>{@code setDirectUrl} sets the base URL for the chat-ui REST API.</li>
 * <li>{@code submitDirect} submits a prompt to the chat-ui and polls for the result.</li>
 * <li>{@code setOpenAiUrl} configures the OpenAI-compatible endpoint and an optional model name.</li>
 * <li>{@code setSystemPrompt} sets a system instruction prepended to OpenAI-compatible requests.</li>
 * <li>{@code setEnableThinking} enables or disables extended thinking for Qwen3-family models.</li>
 * <li>{@code callOpenAi} sends a prompt to the OpenAI-compatible endpoint and returns the reply.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at the same time, as actions are executed sequentially for a single
 * actor.</p>
 */
public class LlmClient {

    private static final Logger logger = Logger.getLogger(LlmClient.class.getName());

    /** The maximum time to wait for an LLM response, set to 5 minutes. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** The base URL of the chat-ui REST API used for direct access via {@link #submitDirect}. */
    private String directBaseUrl = null;

    /**
     * The URL of the OpenAI-compatible API endpoint, such as a vLLM instance at {@code
     * http://host:8000/v1/chat/completions}.
     */
    private String openAiUrl = null;

    /** The model ID used for OpenAI-compatible API calls. */
    private String openAiModel = null;

    /** The system prompt text prepended to every {@code callOpenAi} request. */
    private String openAiSystemPrompt = null;

    /** Enables or disables extended thinking for Qwen3-family models. */
    private boolean enableThinking = false;

    /**
     * An optional listener that receives output messages such as status updates, LLM responses, and error
     * notifications.
     */
    private volatile Consumer<String> outputListener;

    /**
     * Creates a new {@code LlmActor} with the given name and actor system.
     *
     * @param name   the actor name used for identification within the workflow
     * @param system the actor system this actor belongs to
     */

    /**
     * Sets an output listener that receives status messages, LLM responses, and error notifications.
     *
     * @param listener a consumer that receives output messages, or {@code null} to disable
     */
    public void setOutputListener(Consumer<String> listener) {
        this.outputListener = listener;
    }

    private void emit(String message) {
        var listener = this.outputListener;
        if (listener != null) {
            listener.accept(message);
        }
    }

    /**
     * Sets the base URL for direct chat-ui REST API access.
     *
     * Returns an action result that is a success if the URL is non-blank, with a message containing the new URL;
     * otherwise, it is a failure with a message stating that the URL is required.
     *
     * @param url the chat-ui base URL, which may be a bare string, a quoted string, or a single-element JSON array
     */
    public ActionResult setDirectUrl(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.directBaseUrl = unwrapJsonArray(url.trim()).replaceAll("/$", "");
        emit("Direct URL set to: " + this.directBaseUrl);
        return new ActionResult(true, "Direct URL set to " + this.directBaseUrl);
    }

    // ── OpenAI-compatible API (vLLM, etc.) ────────────────────────────────────

    /**
     * Configures the OpenAI-compatible chat completions endpoint (e.g. vLLM).
     *
     * <p>Argument forms:</p>
     * <ul>
     *   <li>JSON array: {@code ["http://host:8000/v1/chat/completions", "model-name"]}</li>
     *   <li>Plain string: {@code "http://host:8000/v1/chat/completions"} (model must be set separately)</li>
     * </ul>
     *
     * @param args URL, or JSON array [URL, model]
     * @return ActionResult indicating success or failure
     */
    /**
     * Sets the endpoint and, when provided, the model name for OpenAI-compatible API calls.
     *
     * Returns an {@link ActionResult} indicating success or failure.
     *
     * A {@code null} or blank model has no effect on the current model.
     *
     * @param url the base URL of the OpenAI-compatible endpoint
     * @param model the model ID to request, or {@code null} to keep the current one
     * @return an {@link ActionResult} containing a message describing what was set
     */
    public ActionResult setOpenAiUrl(String url, String model) {
        this.openAiUrl = url == null ? null : url.trim();
        if (model != null && !model.isBlank()) {
            this.openAiModel = model.trim();
        }
        String msg = "OpenAI URL set to: " + this.openAiUrl
                + (this.openAiModel != null ? " (model: " + this.openAiModel + ")" : "");
        emit(msg);
        return new ActionResult(true, msg);
    }

    public ActionResult setOpenAiUrl(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(trimmed);
                this.openAiUrl   = arr.getString(0).trim();
                this.openAiModel = arr.length() > 1 ? arr.getString(1).trim() : this.openAiModel;
            } catch (Exception e) {
                return new ActionResult(false, "setOpenAiUrl: invalid JSON array: " + e.getMessage());
            }
        } else {
            this.openAiUrl = unwrapJsonArray(trimmed);
        }
        String msg = "OpenAI URL set to: " + this.openAiUrl
                + (this.openAiModel != null ? " (model: " + this.openAiModel + ")" : "");
        emit(msg);
        return new ActionResult(true, msg);
    }

    /**
     * Sets a system-level instruction that is prepended to every {@code callOpenAi} request.
     *
     * @param args the system prompt text, or an empty string or null to clear the prompt
     * @return an {@link ActionResult} indicating success
     */
    public ActionResult setSystemPrompt(String args) {
        this.openAiSystemPrompt = (args == null || args.isBlank()) ? null : unwrapJsonArray(args.trim());
        emit("System prompt " + (this.openAiSystemPrompt != null ? "set (" + this.openAiSystemPrompt.length() + " chars)" : "cleared"));
        return new ActionResult(true, "system prompt set");
    }

    /**
     * Sets whether extended thinking is enabled for Qwen3-family models.
     *
     * @param args a string that is {@code "true"} to enable or {@code "false"} to disable; defaults to disabling if
     * the argument is not {@code "true"}
     *
     * @return an {@link ActionResult} indicating success
     */
    public ActionResult setEnableThinking(String args) {
        this.enableThinking = Boolean.parseBoolean(unwrapJsonArray(args == null ? "" : args.trim()));
        return new ActionResult(true, "enable_thinking set to " + this.enableThinking);
    }

    /**
     * Sends a prompt to an OpenAI-compatible endpoint and returns the model's reply.
     *
     * <p>Blocks for up to 5 minutes until the response is complete.</p>
     *
     * <p>Returns the model's text content as the message on success, or an error message if the URL is not set, the
     * prompt is blank, or the API returns an error.</p>
     *
     * @param args the user prompt text or a JSON array containing the prompt
     *
     * @return an {@link ActionResult} where a success contains the model's reply text in the message
     */
    public ActionResult callOpenAi(String args) {
        if (openAiUrl == null || openAiUrl.isBlank()) {
            return new ActionResult(false, "OpenAI URL not set. Call setOpenAiUrl first.");
        }
        String model = (openAiModel != null && !openAiModel.isBlank()) ? openAiModel : "default";
        String userText = unwrapJsonArray(args == null ? "" : args.trim());
        if (userText.isBlank()) {
            return new ActionResult(false, "callOpenAi: prompt text is required");
        }

        emit(">>> callOpenAi [" + model + "]: " + truncate(userText, 80));

        java.nio.file.Path tempFile = null;
        try {
            // Build messages array
            StringBuilder messages = new StringBuilder("[");
            if (openAiSystemPrompt != null && !openAiSystemPrompt.isBlank()) {
                messages.append("{\"role\":\"system\",\"content\":").append(jsonEscape(openAiSystemPrompt)).append("},");
            }
            messages.append("{\"role\":\"user\",\"content\":").append(jsonEscape(userText)).append("}]");

            // Build full request body
            String body = "{\"model\":" + jsonEscape(model)
                    + ",\"messages\":" + messages
                    + ",\"temperature\":0.1"
                    + (!enableThinking ? ",\"chat_template_kwargs\":{\"enable_thinking\":false}" : "")
                    + "}";

            // Write body to a temp file — avoids shell-escaping issues with large prompts
            tempFile = java.nio.file.Files.createTempFile("vllm-req-", ".json");
            java.nio.file.Files.writeString(tempFile, body, StandardCharsets.UTF_8);

            // Use system curl for reliable HTTP (Java HttpClient has body-delivery issues on this host)
            java.util.List<String> cmd = java.util.List.of(
                    "curl", "-s", "-m", "300",
                    "-X", "POST", openAiUrl,
                    "-H", "Content-Type: application/json",
                    "--data", "@" + tempFile.toString()
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String responseBody = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr       = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished    = process.waitFor(320, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { process.destroyForcibly(); throw new RuntimeException("curl timed out"); }
            int exitCode = process.exitValue();
            if (exitCode != 0) throw new RuntimeException("curl failed (exit " + exitCode + "): " + stderr.trim());

            // Parse: choices[0].message.content
            org.json.JSONObject json = new org.json.JSONObject(responseBody);
            if (json.has("error")) {
                return new ActionResult(false, "OpenAI API error: " + json.getJSONObject("error").optString("message", responseBody));
            }
            String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (content == null || content.isBlank()) {
                return new ActionResult(true, "");
            }

            emit("<<< callOpenAi response (" + content.length() + " chars)");
            return new ActionResult(true, content);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "callOpenAi: interrupted");
        } catch (Exception e) {
            logger.log(Level.WARNING, "callOpenAi failed", e);
            return new ActionResult(false, "callOpenAi failed: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    // ── Direct REST (chat-ui) ─────────────────────────────────────────────────

    /**
     * Submits a prompt to a chat-ui instance via its REST API and waits for the response.
     *
     * Returns the LLM's response text as a message on success, or an error message if the submission fails, the
     * session cannot be found, or the request times out.
     *
     * A timeout of 5 minutes is enforced for the entire polling process.
     *
     * Does not support tool calling; use {@link #callOpenAi(String)} for MCP-based agent loops.
     *
     * @param promptText the prompt text to send, provided as a plain string or a JSON array containing one string
     * @return an ActionResult where a success contains the LLM response text in the message, and a failure contains
     * the error description
     */
    public ActionResult submitDirect(String promptText) {
        if (directBaseUrl == null || directBaseUrl.isBlank()) {
            return new ActionResult(false, "Direct URL not set. Call setDirectUrl first.");
        }
        if (promptText == null || promptText.isBlank()) {
            return new ActionResult(false, "Prompt text is required");
        }

        String text = unwrapJsonArray(promptText);
        emit(">>> submitDirect: " + truncate(text, 100));

        try {
            // Step 1: submit prompt
            String submitBody = "{\"text\": " + jsonEscape(text) + ", \"model\": \"\"}";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest submitReq = HttpRequest.newBuilder()
                    .uri(URI.create(directBaseUrl + "/api/chat/submit"))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(submitBody))
                    .build();

            HttpResponse<String> submitResp = client.send(submitReq,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (submitResp.statusCode() != 200) {
                return new ActionResult(false,
                        "submit failed HTTP " + submitResp.statusCode() + ": " + submitResp.body());
            }

            // Extract sessionId from {"sessionId":"...","status":"submitted"}
            String sessionId = extractJsonStringField(submitResp.body(), "sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                return new ActionResult(false, "No sessionId in response: " + submitResp.body());
            }
            emit("Session ID: " + sessionId);

            // Step 2: poll status until completed
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);

                HttpRequest statusReq = HttpRequest.newBuilder()
                        .uri(URI.create(directBaseUrl + "/api/chat/status/" + sessionId))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> statusResp = client.send(statusReq,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                String status = extractJsonStringField(statusResp.body(), "status");
                if ("completed".equals(status)) {
                    break;
                }
                emit("Status: " + status);
            }

            // Step 3: retrieve result
            HttpRequest resultReq = HttpRequest.newBuilder()
                    .uri(URI.create(directBaseUrl + "/api/chat/result/" + sessionId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resultResp = client.send(resultReq,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String result = extractJsonStringField(resultResp.body(), "result");
            if (result == null) {
                String error = extractJsonStringField(resultResp.body(), "error");
                return new ActionResult(false, "No result: " + (error != null ? error : resultResp.body()));
            }

            emit("<<< submitDirect result (" + result.length() + " chars)");
            return new ActionResult(true, result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Interrupted while waiting for response");
        } catch (Exception e) {
            logger.log(Level.WARNING, "submitDirect failed", e);
            return new ActionResult(false, "submitDirect failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String extractJsonStringField(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int valueStart = json.indexOf('"', colon + 1);
        if (valueStart < 0) return null;
        valueStart++;
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) break;
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; continue; }
                    case 't' -> { sb.append('\t'); i++; continue; }
                    case '"' -> { sb.append('"'); i++; continue; }
                    case '\\' -> { sb.append('\\'); i++; continue; }
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                i += 5; continue;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unwrapJsonArray(String s) {
        String t = s.trim();
        if (t.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(t);
                if (arr.length() == 1 && arr.get(0) instanceof String) {
                    return ((String) arr.get(0)).trim();
                }
            } catch (Exception ignored) {}
        }
        if (t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
        return t;
    }

    private static String jsonEscape(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
