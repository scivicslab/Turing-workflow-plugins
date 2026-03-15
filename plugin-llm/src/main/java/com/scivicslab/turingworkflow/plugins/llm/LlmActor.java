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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor that calls an LLM service via MCP Streamable HTTP transport.
 * Default target: quarkus-coder-agent-claude at localhost:8090.
 *
 * <p>Can be dynamically loaded via loader.loadMaven in workflow YAML.</p>
 */
public class LlmActor extends IIActorRef<LlmActor> {

    private static final Logger logger = Logger.getLogger(LlmActor.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private String mcpBaseUrl = "http://localhost:8090/mcp";
    private volatile Consumer<String> outputListener;
    private volatile String mcpSessionId = null;
    private final AtomicInteger requestId = new AtomicInteger(1);

    public LlmActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

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
     * Set the MCP server base URL.
     * Arguments: URL (e.g., "http://localhost:8090")
     */
    @Action("setUrl")
    public ActionResult setUrl(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.mcpBaseUrl = stripQuotes(url.trim());
        this.mcpSessionId = null;
        emit("LLM endpoint set to: " + this.mcpBaseUrl);
        return new ActionResult(true, "URL set to " + this.mcpBaseUrl);
    }

    /**
     * Send a prompt to the LLM via MCP tools/call.
     * Arguments: the prompt text
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
     * Get the LLM service status via MCP tools/call.
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
     * List available tools on the MCP server.
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
