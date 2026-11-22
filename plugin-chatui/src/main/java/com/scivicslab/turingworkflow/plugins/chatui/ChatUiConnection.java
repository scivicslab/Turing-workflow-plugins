package com.scivicslab.turingworkflow.plugins.chatui;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A connection to one conversation of a chat-ui-with-audit-trail server (to be named quarkus-chat-ui again), through
 * the REST API of its {@code ChatResource} ({@code /api/projects}) and {@code SessionsResource} ({@code /api/sessions}).
 * The conversation is named by a project id and a chat id: {@code project1} and {@code 01} at first.
 *
 * <h2>How {@code send} learns that its turn has ended</h2>
 * <p>{@code POST /api/projects/{p}/chats/{c}/chat} answers {@code {"type":"accepted"}} and nothing more: no turn id
 * and no answer. The answer is read from the conversation's history, {@code GET .../conversation}. The server records
 * every history entry written during a turn with that turn's {@code turnId}: the {@code user} entry when the turn
 * starts, and, when it ends, an {@code assistant} entry with the answer or an {@code error} entry with the failure. A
 * cancelled turn, or one that wrote nothing, records neither. So {@code send} notes the turn ids already in the
 * history, posts the prompt, and polls the history for a new {@code user} entry with the same text; that entry's
 * {@code turnId} is the turn. The turn's {@code assistant} entry is the answer and its {@code error} entry a failure.
 * When {@code GET .../status} says {@code busy: false} after the turn was seen, and the history read after it holds
 * no ending for it, the turn ended without an answer. The SSE stream ({@code .../chat/stream}) is not used: opening it
 * replaces the stream the browser's pane is reading.</p>
 *
 * <h2>Threads and time</h2>
 * <p>No real-thread pool is made here and the commonPool is not used: each request is sent with {@code sendAsync} on
 * an HTTP client whose executor makes a virtual thread per task, and the calling thread waits for it with a bound.
 * Every request is ended at the request limit, wherever it hangs, the body included; the whole {@code send} is ended
 * at the total limit. The HTTP client keeps one JDK-internal selector real thread while it is open; {@link #close()}
 * ends it.</p>
 */
public class ChatUiConnection implements AutoCloseable {

    /** How long a whole {@code send} may take by default: ten minutes. */
    public static final Duration DEFAULT_TOTAL_LIMIT = Duration.ofMinutes(10);

    /** How long one HTTP request may take by default: thirty seconds. */
    public static final Duration DEFAULT_REQUEST_LIMIT = Duration.ofSeconds(30);

    /** How long {@code send} waits between two polls by default. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    /** What {@code send} says it is, as the prompt's {@code source}; the screen shows it as a program. */
    static final String SOURCE = "plugin-chatui";

    private static final int MAX_BODY_IN_MESSAGE = 300;

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    // HTTP/1.1: the server (Vert.x) does not need HTTP/2, and an upgrade attempt only adds a round trip.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(virtualThreads)
            .build();

    private volatile String baseUrl = "http://localhost:28030";
    private volatile String projectId = "project1";
    private volatile String chatId = "01";
    private volatile Duration totalLimit = DEFAULT_TOTAL_LIMIT;
    private volatile Duration requestLimit = DEFAULT_REQUEST_LIMIT;
    private volatile Duration pollInterval = DEFAULT_POLL_INTERVAL;

    /** Raised by {@link #cancel()}, read by a {@code send} that is waiting. */
    private volatile boolean cancelled;

    // ── settings ──────────────────────────────────────────────────────────────

    /**
     * Sets the server's base URL, without a trailing slash.
     *
     * @param url for example {@code http://192.168.5.12:28030}
     * @return true with the URL kept; false when it is not an http or https URL
     */
    public ActionResult setBaseUrl(String url) {
        String u = url == null ? "" : url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return new ActionResult(false, "setBaseUrl: not an http or https URL: " + url);
        }
        this.baseUrl = u;
        return new ActionResult(true, u);
    }

    /**
     * Sets the conversation every action works on.
     *
     * @param project the project id, for example {@code project1}
     * @param chat    the chat id within the project, for example {@code 01}
     * @return true with {@code <project>/chat-<chat>}; false when either is blank or holds a slash
     */
    public ActionResult setChat(String project, String chat) {
        if (project == null || project.isBlank() || chat == null || chat.isBlank()
                || project.contains("/") || chat.contains("/")) {
            return new ActionResult(false, "setChat: give a project id and a chat id, neither blank nor with a slash");
        }
        this.projectId = project.strip();
        this.chatId = chat.strip();
        return new ActionResult(true, conversationName());
    }

    private String conversationName() {
        return projectId + "/chat-" + chatId;
    }

    /**
     * Sets the time limits.
     *
     * @param totalSeconds   how long a whole {@code send} may take, or null to keep it
     * @param requestSeconds how long one HTTP request may take, or null to keep it
     * @param pollMillis     how long {@code send} waits between two polls, or null to keep it
     * @return true with the limits now in force; false when one is not positive
     */
    public ActionResult setTimeLimits(Long totalSeconds, Long requestSeconds, Long pollMillis) {
        if ((totalSeconds != null && totalSeconds <= 0) || (requestSeconds != null && requestSeconds <= 0)
                || (pollMillis != null && pollMillis <= 0)) {
            return new ActionResult(false, "setTimeLimits: every limit must be positive");
        }
        if (totalSeconds != null) totalLimit = Duration.ofSeconds(totalSeconds);
        if (requestSeconds != null) requestLimit = Duration.ofSeconds(requestSeconds);
        if (pollMillis != null) pollInterval = Duration.ofMillis(pollMillis);
        return new ActionResult(true, "total limit " + totalLimit.toSeconds() + "s, request limit "
                + requestLimit.toSeconds() + "s, poll every " + pollInterval.toMillis() + "ms");
    }

    private String chatUrl() {
        return baseUrl + "/api/projects/" + enc(projectId) + "/chats/" + enc(chatId);
    }

    // ── send and cancel ───────────────────────────────────────────────────────

    /**
     * Sends a prompt to the conversation and waits for its turn's answer.
     *
     * @param text    the prompt
     * @param model   the model to answer with, or null for the conversation's own
     * @param noThink true to ask the model not to think aloud, where the conversation supports it
     * @return true with the answer; false naming the URL and what went wrong: the total limit, an error the turn
     * recorded, a turn that ended without an answer, or a cancel
     */
    public ActionResult send(String text, String model, boolean noThink) {
        if (text == null || text.isBlank()) {
            return new ActionResult(false, "send: the prompt is empty");
        }
        cancelled = false;
        long deadline = System.nanoTime() + totalLimit.toNanos();
        String conversationUrl = chatUrl() + "/conversation";
        String statusUrl = chatUrl() + "/status";

        // The turns already in the history are not this prompt's.
        Reply before = request("GET", conversationUrl, null, deadline);
        if (!before.ok()) return before.failure();
        JSONArray history = before.array();
        if (history == null) return unexpected(conversationUrl, before.body(), "a JSON array of history entries");
        Set<String> oldTurns = new HashSet<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject e = history.optJSONObject(i);
            if (e != null && !e.isNull("turnId")) oldTurns.add(e.optString("turnId"));
        }

        JSONObject body = new JSONObject().put("text", text).put("noThink", noThink).put("source", SOURCE);
        if (model != null && !model.isBlank()) body.put("model", model);
        String chatPostUrl = chatUrl() + "/chat";
        Reply posted = request("POST", chatPostUrl, body.toString(), deadline);
        if (!posted.ok()) return posted.failure();
        JSONObject ack = posted.object();
        if (ack == null || !"accepted".equals(ack.optString("type"))) {
            return unexpected(chatPostUrl, posted.body(), "{\"type\":\"accepted\"}");
        }

        String turn = null;
        while (true) {
            if (cancelled) {
                return new ActionResult(false, "send: cancelled while waiting for " + conversationUrl);
            }
            // Status first, then the history: a turn seen before a busy:false and with no ending in the history
            // read after it has ended without an answer.
            boolean idle = false;
            if (turn != null) {
                Reply st = request("GET", statusUrl, null, deadline);
                if (!st.ok()) return st.failure();
                JSONObject s = st.object();
                if (s == null || !s.has("busy")) return unexpected(statusUrl, st.body(), "a JSON object with busy");
                idle = !s.optBoolean("busy");
            }
            Reply now = request("GET", conversationUrl, null, deadline);
            if (!now.ok()) return now.failure();
            JSONArray entries = now.array();
            if (entries == null) return unexpected(conversationUrl, now.body(), "a JSON array of history entries");
            if (turn == null) {
                turn = findTurn(entries, text, oldTurns);
            }
            if (turn != null) {
                ActionResult ending = endingOf(entries, turn, conversationUrl);
                if (ending != null) return ending;
                if (idle) {
                    return new ActionResult(false, "turn " + turn + " of " + conversationName()
                            + " ended without an answer (" + statusUrl + " says busy: false)");
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) return totalLimitReached(conversationUrl);
            try {
                Thread.sleep(Math.min(pollInterval.toMillis(), TimeUnit.NANOSECONDS.toMillis(left) + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ActionResult(false, "send: interrupted while waiting for " + conversationUrl);
            }
        }
    }

    /** The turn id of the first new {@code user} entry whose text is {@code text}, or null when none is there yet. */
    private static String findTurn(JSONArray entries, String text, Set<String> oldTurns) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null || e.isNull("turnId")) continue;
            String id = e.optString("turnId");
            if ("user".equals(e.optString("role")) && text.equals(e.optString("content")) && !oldTurns.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /** The answer or the error the history holds for {@code turn}, or null when it holds neither yet. */
    private static ActionResult endingOf(JSONArray entries, String turn, String url) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null || !turn.equals(e.optString("turnId", null))) continue;
            String role = e.optString("role");
            if ("assistant".equals(role)) return new ActionResult(true, e.optString("content"));
            if ("error".equals(role)) {
                return new ActionResult(false, "turn " + turn + " failed (" + url + "): " + e.optString("content"));
            }
        }
        return null;
    }

    private ActionResult totalLimitReached(String url) {
        return new ActionResult(false, "no answer from " + url + " within the total limit of "
                + totalLimit.toSeconds() + "s");
    }

    /**
     * {@code POST .../chat/cancel}: cancels the conversation's running turn, and ends a {@code send} of this
     * connection that is waiting.
     *
     * @return true with the server's answer; false naming the URL and what went wrong
     */
    public ActionResult cancel() {
        cancelled = true;
        String url = chatUrl() + "/chat/cancel";
        Reply r = request("POST", url, "", System.nanoTime() + requestLimit.toNanos());
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null || !"cancelled".equals(o.optString("type"))) return unexpected(url, r.body(), "{\"type\":\"cancelled\"}");
        return new ActionResult(true, o.toString());
    }

    // ── the conversation ──────────────────────────────────────────────────────

    /** {@code GET .../status}: the JSON object of {@code busy}, {@code model}, {@code provider}, {@code tools}, ... */
    public ActionResult getStatus() {
        String url = chatUrl() + "/status";
        Reply r = once("GET", url, null);
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null || !o.has("busy")) return unexpected(url, r.body(), "a JSON object with busy");
        return new ActionResult(true, o.toString());
    }

    /** {@code GET .../conversation}: the JSON array of the history entries, up to the last 200. */
    public ActionResult getConversation() {
        return array(chatUrl() + "/conversation");
    }

    /** {@code DELETE .../conversation}: starts the conversation over; nothing is deleted from the I/O log. */
    public ActionResult clearConversation() {
        String url = chatUrl() + "/conversation";
        Reply r = once("DELETE", url, null);
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null || !"cleared".equals(o.optString("type"))) return unexpected(url, r.body(), "{\"type\":\"cleared\"}");
        return new ActionResult(true, o.toString());
    }

    /** {@code GET .../models}: the JSON array of the models the conversation's provider offers. */
    public ActionResult getModels() {
        return array(chatUrl() + "/models");
    }

    /**
     * {@code POST .../model}: puts the conversation on a model.
     *
     * @param model the model's name, as {@code getModels} lists it
     */
    public ActionResult setModel(String model) {
        if (model == null || model.isBlank()) return new ActionResult(false, "setModel: name a model");
        return accepted(chatUrl() + "/model", new JSONObject().put("model", model));
    }

    /**
     * {@code POST .../provider}: puts the conversation on a provider.
     *
     * @param provider for example {@code openai-compat}, {@code claude} or {@code codex}
     * @param tools    the tool set, {@code full} or {@code harness}, or null for the provider's default
     */
    public ActionResult setProvider(String provider, String tools) {
        if (provider == null || provider.isBlank()) return new ActionResult(false, "setProvider: name a provider");
        JSONObject body = new JSONObject().put("provider", provider);
        if (tools != null && !tools.isBlank()) body.put("tools", tools);
        return accepted(chatUrl() + "/provider", body);
    }

    /** {@code GET /api/projects/{p}/chats}: the JSON array of the project's chat ids. */
    public ActionResult listChats() {
        return array(baseUrl + "/api/projects/" + enc(projectId) + "/chats");
    }

    // ── the I/O log ───────────────────────────────────────────────────────────

    /**
     * {@code GET /api/sessions?conversationSquadId=}: the JSON array of this conversation's I/O-log sessions, most
     * recent first.
     *
     * @param all true to list every conversation's sessions instead
     */
    public ActionResult getSessions(boolean all) {
        String url = baseUrl + "/api/sessions" + (all ? "" : "?conversationSquadId=" + enc(conversationName()));
        return array(url);
    }

    /**
     * {@code GET /api/sessions/{id}/turns}: a window of a session's turns, newest first.
     *
     * @param sessionId the numeric id {@code getSessions} lists
     * @param before    only turns numbered below this, or null for the newest
     * @param limit     how many, or null for the server's default of 20
     */
    public ActionResult getTurns(long sessionId, Integer before, Integer limit) {
        StringBuilder url = new StringBuilder(baseUrl + "/api/sessions/" + sessionId + "/turns");
        String sep = "?";
        if (before != null) { url.append(sep).append("before=").append(before); sep = "&"; }
        if (limit != null) url.append(sep).append("limit=").append(limit);
        String u = url.toString();
        Reply r = once("GET", u, null);
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null || !o.has("turns")) return unexpected(u, r.body(), "a JSON object with turns");
        return new ActionResult(true, o.toString());
    }

    /**
     * {@code GET /api/sessions/{id}/trace/{turn}}: one turn's steps.
     *
     * @param sessionId the session
     * @param turn      the turn number, as {@code getTurns} lists it
     */
    public ActionResult getTrace(long sessionId, int turn) {
        String url = baseUrl + "/api/sessions/" + sessionId + "/trace/" + turn;
        Reply r = once("GET", url, null);
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null) return unexpected(url, r.body(), "a JSON object");
        return new ActionResult(true, o.toString());
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private ActionResult array(String url) {
        Reply r = once("GET", url, null);
        if (!r.ok()) return r.failure();
        JSONArray a = r.array();
        if (a == null) return unexpected(url, r.body(), "a JSON array");
        return new ActionResult(true, a.toString());
    }

    private ActionResult accepted(String url, JSONObject body) {
        Reply r = once("POST", url, body.toString());
        if (!r.ok()) return r.failure();
        JSONObject o = r.object();
        if (o == null || !"accepted".equals(o.optString("type"))) return unexpected(url, r.body(), "{\"type\":\"accepted\"}");
        return new ActionResult(true, o.toString());
    }

    private Reply once(String method, String url, String body) {
        return request(method, url, body, System.nanoTime() + requestLimit.toNanos());
    }

    private static ActionResult unexpected(String url, String body, String expected) {
        return new ActionResult(false, "unexpected response from " + url + ": expected " + expected + ", got: "
                + cut(body));
    }

    /**
     * One HTTP request, ended when it takes longer than the request limit or runs past {@code deadline}. A status
     * other than 2xx is a failure naming the URL, the status and the body.
     */
    private Reply request(String method, String url, String jsonBody, long deadline) {
        long leftNanos = Math.min(requestLimit.toNanos(), deadline - System.nanoTime());
        if (leftNanos <= 0) {
            return Reply.failed(totalLimitReached(url));
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(leftNanos))
                    .header("Accept", "application/json");
        } catch (IllegalArgumentException e) {
            return Reply.failed(new ActionResult(false, method + " " + url + ": not a valid URL"));
        }
        switch (method) {
            case "POST" -> b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        }
        CompletableFuture<HttpResponse<String>> pending =
                http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> resp;
        try {
            // The whole exchange, body included, is bounded here: HttpRequest.timeout alone ends a request only
            // while it waits for the response headers, not a body that stops coming.
            resp = pending.get(leftNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            boolean total = deadline - System.nanoTime() <= 0 && leftNanos < requestLimit.toNanos();
            return Reply.failed(total ? totalLimitReached(url)
                    : new ActionResult(false, method + " " + url + " did not answer within the request limit of "
                            + requestLimit.toSeconds() + "s"));
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return Reply.failed(new ActionResult(false, method + " " + url + ": interrupted"));
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof java.net.http.HttpTimeoutException) {
                return Reply.failed(new ActionResult(false, method + " " + url
                        + " did not answer within the request limit of " + requestLimit.toSeconds() + "s"));
            }
            return Reply.failed(new ActionResult(false, method + " " + url + " failed: " + c));
        }
        if (resp.statusCode() / 100 != 2) {
            return Reply.failed(new ActionResult(false, method + " " + url + " returned HTTP " + resp.statusCode()
                    + ": " + cut(resp.body())));
        }
        return new Reply(resp.body(), null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String cut(String s) {
        if (s == null) return "(no body)";
        return s.length() <= MAX_BODY_IN_MESSAGE ? s : s.substring(0, MAX_BODY_IN_MESSAGE) + "...";
    }

    /**
     * Ends the HTTP client, its selector real thread included, and the virtual-thread executor. Requests still in
     * flight are aborted rather than waited for, so a hung server cannot hold this up.
     */
    @Override
    public void close() {
        cancelled = true;
        http.shutdownNow();
        virtualThreads.shutdownNow();
    }

    /** A 2xx response body, or the failure that took its place. */
    private record Reply(String body, ActionResult failure) {
        static Reply failed(ActionResult failure) {
            return new Reply(null, failure);
        }

        boolean ok() {
            return failure == null;
        }

        Object json() {
            try {
                return body == null ? null : new JSONTokener(body).nextValue();
            } catch (JSONException e) {
                return null;
            }
        }

        JSONObject object() {
            return json() instanceof JSONObject o ? o : null;
        }

        JSONArray array() {
            return json() instanceof JSONArray a ? a : null;
        }
    }
}
