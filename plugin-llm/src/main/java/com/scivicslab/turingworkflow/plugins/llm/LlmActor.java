package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor that calls an LLM service via MCP (Model Context Protocol) Streamable HTTP transport.
 *
 * <p>Default target: quarkus-chat-ui-claude at {@code localhost:8090}.
 * Can be dynamically loaded via {@code loader.loadMaven} in workflow YAML.</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code setUrl} - Configure the MCP server base URL</li>
 *   <li>{@code prompt} - Send a prompt to the LLM and receive a response</li>
 *   <li>{@code status} - Query the LLM service status</li>
 *   <li>{@code listTools} - List available tools on the MCP server</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class LlmActor extends IIActorRef<LlmActor> {

    private static final Logger logger = Logger.getLogger(LlmActor.class.getName());

    /** HTTP request timeout for MCP calls (5 minutes to allow for long LLM responses). */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** Base URL of the MCP server endpoint. */
    private String mcpBaseUrl = "http://localhost:8090/mcp";

    /** Optional listener that receives output messages (prompt results, status, errors). */
    private volatile Consumer<String> outputListener;

    /** MCP session identifier, obtained during the initialize handshake. */
    private volatile String mcpSessionId = null;

    /** Monotonically increasing JSON-RPC request ID counter. */
    private final AtomicInteger requestId = new AtomicInteger(1);

    /**
     * Creates a new {@code LlmActor} with the given name and actor system.
     *
     * @param name   the actor name used for identification within the workflow
     * @param system the actor system this actor belongs to
     */
    public LlmActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Sets an output listener that will be called with status messages,
     * LLM responses, and error notifications.
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
     * Sets the MCP server base URL. Resets any existing MCP session.
     *
     * <p>Expected argument: the base URL string (e.g., {@code "http://localhost:8090/mcp"}).</p>
     *
     * @param url the MCP server base URL; must not be {@code null} or blank
     * @return an {@link ActionResult} indicating success with the configured URL,
     *         or failure if the URL is missing
     */
    @Action("setUrl")
    public ActionResult setUrl(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.mcpBaseUrl = unwrapJsonArray(url.trim());
        this.mcpSessionId = null;
        emit("LLM endpoint set to: " + this.mcpBaseUrl);
        return new ActionResult(true, "URL set to " + this.mcpBaseUrl);
    }

    /**
     * Sends a prompt to the LLM via the MCP {@code tools/call} method.
     *
     * <p>Expected argument: the prompt text as a plain string. The prompt is sent
     * to the {@code sendPrompt} tool on the MCP server with an empty model selector
     * (server default).</p>
     *
     * @param promptText the prompt text to send; must not be {@code null} or blank
     * @return an {@link ActionResult} containing the LLM response text on success,
     *         or an error message on failure
     */
    @Action("prompt")
    public ActionResult prompt(String promptText) {
        if (promptText == null || promptText.isBlank()) {
            return new ActionResult(false, "Prompt text is required");
        }

        String text = stripQuotes(promptText);
        emit(">>> Sending prompt to LLM: " + truncate(text, 100));

        try {
            ensureInitialized();

            String response = callMcpTool("sendPrompt",
                    "{\"prompt\": " + jsonEscape(text) + ", \"model\": \"\"}");

            emit("<<< LLM response received (" + response.length() + " chars)");
            emit(response);
            return new ActionResult(true, response);

        } catch (Exception e) {
            logger.log(Level.WARNING, "MCP call failed", e);
            emit("!!! LLM error: " + e.getMessage());
            return new ActionResult(false, "MCP call failed: " + e.getMessage());
        }
    }

    /**
     * Retrieves the LLM service status via the MCP {@code tools/call} method.
     *
     * <p>Expected argument: ignored (may be {@code null} or empty).</p>
     *
     * @param args unused argument (required by the action framework signature)
     * @return an {@link ActionResult} containing the status information on success,
     *         or an error message on failure
     */
    @Action("status")
    public ActionResult status(String args) {
        try {
            ensureInitialized();
            String response = callMcpTool("getStatus", "{}");
            emit("LLM status: " + response);
            return new ActionResult(true, response);
        } catch (Exception e) {
            emit("!!! LLM error: " + e.getMessage());
            return new ActionResult(false, "MCP call failed: " + e.getMessage());
        }
    }

    /**
     * Lists available tools on the MCP server via the {@code tools/list} JSON-RPC method.
     *
     * <p>Expected argument: ignored (may be {@code null} or empty).</p>
     *
     * @param args unused argument (required by the action framework signature)
     * @return an {@link ActionResult} containing the JSON list of available tools on success,
     *         or an error message on failure
     */
    @Action("listTools")
    public ActionResult listTools(String args) {
        try {
            ensureInitialized();
            String response = sendJsonRpc("tools/list", "{}");
            emit("Available tools: " + response);
            return new ActionResult(true, response);
        } catch (Exception e) {
            emit("!!! LLM error: " + e.getMessage());
            return new ActionResult(false, "MCP call failed: " + e.getMessage());
        }
    }

    /**
     * Calls the {@code call_agent} tool on the MCP Gateway, which routes the prompt
     * to a named agent and blocks until the reply arrives (up to 5 minutes).
     *
     * <p>Expected argument formats:</p>
     * <ul>
     *   <li>JSON array: {@code ["agentName", "prompt text"]}</li>
     *   <li>JSON object: {@code {"agent": "agentName", "prompt": "text", "model": "sonnet"}}</li>
     * </ul>
     *
     * @param args JSON array or object containing agent name and prompt
     * @return an {@link ActionResult} containing the agent's reply on success
     */
    @Action("callAgent")
    public ActionResult callAgent(String args) {
        if (args == null || args.isBlank()) {
            return new ActionResult(false,
                    "Arguments required: [\"agentName\", \"promptText\"] or {\"agent\":\"...\",\"prompt\":\"...\"}");
        }

        String unwrapped = unwrapJsonArray(args.trim());
        String agentName;
        String promptText;
        String argumentsJson;

        if (unwrapped.startsWith("{")) {
            argumentsJson = unwrapped;
            agentName = extractJsonStringField(unwrapped, "agent");
            promptText = extractJsonStringField(unwrapped, "prompt");
        } else if (unwrapped.startsWith("[")) {
            String[] parts = parseJsonStringArray(unwrapped);
            if (parts.length < 2) {
                return new ActionResult(false, "Expected [\"agentName\", \"promptText\"]");
            }
            agentName = parts[0];
            promptText = parts[1];
            argumentsJson = "{\"agent\": " + jsonEscape(agentName)
                    + ", \"prompt\": " + jsonEscape(promptText) + "}";
        } else {
            return new ActionResult(false,
                    "Expected JSON object or array, got: " + truncate(unwrapped, 50));
        }

        String displayPrompt = promptText != null ? promptText : unwrapped;
        emit(">>> Calling agent '" + agentName + "': " + truncate(displayPrompt, 100));

        try {
            ensureInitialized();
            String response = callMcpTool("call_agent", argumentsJson);
            emit("<<< Response from '" + agentName + "': " + truncate(response, 200));
            emit(response);
            return new ActionResult(true, response);
        } catch (Exception e) {
            logger.log(Level.WARNING, "call_agent failed", e);
            emit("!!! call_agent error: " + e.getMessage());
            return new ActionResult(false, "call_agent failed: " + e.getMessage());
        }
    }

    private String extractJsonStringField(String json, String key) {
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
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String[] parseJsonStringArray(String json) {
        List<String> result = new ArrayList<>();
        int i = json.indexOf('[') + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ']') break;
            if (c == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < json.length()) {
                    char ch = json.charAt(i);
                    if (ch == '"' && (i == 0 || json.charAt(i - 1) != '\\')) { i++; break; }
                    if (ch == '\\' && i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        switch (next) {
                            case 'n' -> { sb.append('\n'); i += 2; continue; }
                            case 't' -> { sb.append('\t'); i += 2; continue; }
                            case '"' -> { sb.append('"'); i += 2; continue; }
                            case '\\' -> { sb.append('\\'); i += 2; continue; }
                        }
                    }
                    sb.append(ch);
                    i++;
                }
                result.add(sb.toString());
            } else {
                i++;
            }
        }
        return result.toArray(new String[0]);
    }

    private void ensureInitialized() throws Exception {
        if (mcpSessionId != null) return;

        String initRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": %d,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2025-03-26",
                        "capabilities": {},
                        "clientInfo": {
                            "name": "turing-workflow-llm-plugin",
                            "version": "1.0.0"
                        }
                    }
                }
                """.formatted(requestId.getAndIncrement());

        HttpResponse<String> response = postMcp(initRequest);
        logger.info("MCP initialize response: " + response.statusCode());

        var sessionHeader = response.headers().firstValue("Mcp-Session-Id");
        if (sessionHeader.isPresent()) {
            mcpSessionId = sessionHeader.get();
            logger.info("MCP session: " + mcpSessionId);
        }

        String notification = """
                {
                    "jsonrpc": "2.0",
                    "method": "notifications/initialized"
                }
                """;
        postMcp(notification);

        emit("MCP session established with " + mcpBaseUrl);
    }

    private String callMcpTool(String toolName, String argumentsJson) throws Exception {
        String params = """
                {"name": "%s", "arguments": %s}
                """.formatted(toolName, argumentsJson);

        String responseBody = sendJsonRpc("tools/call", params);
        return extractMcpResult(responseBody);
    }

    private String sendJsonRpc(String method, String params) throws Exception {
        String jsonRpc = """
                {
                    "jsonrpc": "2.0",
                    "id": %d,
                    "method": "%s",
                    "params": %s
                }
                """.formatted(requestId.getAndIncrement(), method, params);

        HttpResponse<String> response = postMcp(jsonRpc);

        if (response.statusCode() != 200) {
            throw new RuntimeException("MCP server returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return response.body();
    }

    private HttpResponse<String> postMcp(String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mcpBaseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (mcpSessionId != null) {
            requestBuilder.header("Mcp-Session-Id", mcpSessionId);
        }

        return client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String extractMcpResult(String jsonRpcResponse) {
        int textIdx = jsonRpcResponse.indexOf("\"text\":");
        if (textIdx < 0) {
            int resultIdx = jsonRpcResponse.indexOf("\"result\":");
            if (resultIdx >= 0) {
                return jsonRpcResponse.substring(resultIdx + 9).replaceAll("[\"{}\\[\\]]", "").trim();
            }
            return jsonRpcResponse;
        }

        int searchFrom = textIdx;
        String before = jsonRpcResponse.substring(Math.max(0, textIdx - 10), textIdx);
        if (before.contains("type")) {
            int nextText = jsonRpcResponse.indexOf("\"text\":", textIdx + 7);
            if (nextText >= 0) {
                searchFrom = nextText;
            }
        }

        int valueStart = jsonRpcResponse.indexOf('"', searchFrom + 7);
        if (valueStart < 0) return jsonRpcResponse;
        valueStart++;

        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < jsonRpcResponse.length(); i++) {
            char c = jsonRpcResponse.charAt(i);
            if (c == '"' && (i == 0 || jsonRpcResponse.charAt(i - 1) != '\\')) break;
            if (c == '\\' && i + 1 < jsonRpcResponse.length()) {
                char next = jsonRpcResponse.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; continue; }
                    case 't' -> { sb.append('\t'); i++; continue; }
                    case '"' -> { sb.append('"'); i++; continue; }
                    case '\\' -> { sb.append('\\'); i++; continue; }
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

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.startsWith("[\"") && t.endsWith("\"]")) return t.substring(2, t.length() - 2);
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
