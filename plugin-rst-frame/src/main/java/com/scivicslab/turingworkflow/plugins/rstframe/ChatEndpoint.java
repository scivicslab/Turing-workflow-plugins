package com.scivicslab.turingworkflow.plugins.rstframe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.Function;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sends a single question to a chat-completions endpoint (the GPU broker) and returns the text of the reply.
 *
 * <p>The following actions are available:
 * <ul>
 * <li>{@code apply(String question)}: Returns the text of the model's response to the given question.</li>
 * </ul>
 *
 * <p>Every request is marked as background work so that the broker serves interactive callers first. If a request
 * fails, it is retried up to four times with increasing delays between attempts.
 *
 * <p>A request fails if the broker returns a non-200 status code or if the response body contains no text (for
 * example, an error or an unknown model name). In such cases, the action result is not a success and an exception is
 * thrown.
 *
 * <p>One instance may serve several workflows or threads at once.
 *
 * <p>Time limits, read from system properties on each call and applied to each attempt: {@value
 * #TOTAL_TIMEOUT_PROPERTY} (default {@value #DEFAULT_TOTAL_TIMEOUT_SECONDS}) bounds an attempt from sending the request
 * to the last byte of the body, queue wait and generation included; {@value #IDLE_TIMEOUT_PROPERTY} (default {@value
 * #DEFAULT_IDLE_TIMEOUT_SECONDS}) ends an attempt whose body stops for that long after it began. An attempt that hits
 * a limit is retried like any other failure, so a call that never gets an answer takes at most 4 total limits plus the
 * 50 s of sleeps between attempts.
 *
 * <pre>{@code
 * chat_endpoint.apply("What is the main concept of this section?")
 * }</pre>
 */
public final class ChatEndpoint implements Function<String, String> {

    /** System property: seconds one attempt may take in all, queue wait and reading the body included. */
    public static final String TOTAL_TIMEOUT_PROPERTY = "turingworkflow.rstframe.chatTotalTimeoutSeconds";

    /** System property: seconds the response body may stall once its first byte has arrived. */
    public static final String IDLE_TIMEOUT_PROPERTY = "turingworkflow.rstframe.chatIdleTimeoutSeconds";

    /** Default of {@value #TOTAL_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_TOTAL_TIMEOUT_SECONDS = 600;

    /** Default of {@value #IDLE_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 60;

    // The client's work runs on virtual threads: without an executor the JDK gives each HttpClient a
    // pool of real threads of its own. The JDK still keeps one internal selector thread per client,
    // which is the client's own I/O loop, not a pool we create.
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    private final String url;
    private final String model;
    private final int maxTokens;

    /**
     * Initializes a connection to a chat-completions endpoint at the given URL to send a single question and receive a
     * single text response.
     *
     * @param url the endpoint address, e.g. {@code http://192.168.5.21:30805/v1/chat/completions}
     * @param model the name of the model to use
     * @param maxTokens the maximum number of tokens to generate
     */
    public ChatEndpoint(String url, String model, int maxTokens) {
        this.url = url;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String apply(String question) {
        String body = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", question))).toString();
        // the limits hold per attempt: a request that hits one is retried like any other failed attempt
        long total = SystemPropertyLimit.read(TOTAL_TIMEOUT_PROPERTY, DEFAULT_TOTAL_TIMEOUT_SECONDS);
        long idle = SystemPropertyLimit.read(IDLE_TIMEOUT_PROPERTY, DEFAULT_IDLE_TIMEOUT_SECONDS);
        String what = "POST " + url + " (model " + model + ")";
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                LimitedExchange.Reply r = LimitedExchange.send(HTTP, HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json").header("X-Job-Priority", "background")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), total, idle, what);
                String text = r.status() == 200 ? text(r.body()) : null;
                if (text != null) return text;
                last = new IllegalStateException(what + ": HTTP " + r.status() + ": " + r.body().substring(0, Math.min(300, r.body().length())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(what + ": interrupted", e);
            } catch (Exception e) {
                last = new IllegalStateException(e.getMessage(), e);
            }
            try {
                Thread.sleep(5000L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw last;
            }
        }
        throw last;
    }

    /**
     * Extracts the text content from a JSON response body.
     *
     * @param body the JSON string from the chat-completions endpoint
     * @return the text of the first choice's message, or {@code null} if the body contains no choices, no message, no
     * content, or is not a valid JSON object
     */
    static String text(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null || message.isNull("content")) return null;
            return message.getString("content");
        } catch (Exception e) {
            return null;
        }
    }
}
