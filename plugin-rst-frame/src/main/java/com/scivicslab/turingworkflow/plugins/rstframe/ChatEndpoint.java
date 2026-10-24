package com.scivicslab.turingworkflow.plugins.rstframe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One question to a chat-completions endpoint (the GPU broker), one answer back. A reply that carries no text (an
 * error, a model name the broker does not know) is a failure, not an empty answer. A failed request is tried again a
 * few times before it fails. Every request is marked as background work, so the broker serves interactive callers first.
 */
public final class ChatEndpoint implements Function<String, String> {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private final String url;
    private final String model;
    private final int maxTokens;

    /** @param url e.g. {@code http://192.168.5.21:30805/v1/chat/completions} */
    public ChatEndpoint(String url, String model, int maxTokens) {
        this.url = url;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String apply(String question) {
        String body = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", question))).toString();
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json").header("X-Job-Priority", "background")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                String text = r.statusCode() == 200 ? text(r.body()) : null;
                if (text != null) return text;
                last = new IllegalStateException("HTTP " + r.statusCode() + ": " + r.body().substring(0, Math.min(300, r.body().length())));
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

    /** @return the reply's text, or {@code null} when the body carries none */
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
