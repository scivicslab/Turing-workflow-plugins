package com.scivicslab.turingworkflow.plugins.chatui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Every action of {@link ChatUiIIAR} against a stub of chat-ui-with-audit-trail's REST API made with the JDK's
 * {@code HttpServer}: the request each one sends, the answer it makes of the reply, and the failures — the total
 * limit running out, a read that hangs, an error status, a reply of the wrong shape, a turn that fails or ends
 * without an answer, a cancel while waiting.
 */
@DisplayName("plugin-chatui: ChatUiIIAR against a stub chat-ui-with-audit-trail")
class ChatUiIIARTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);
    private static final String CHAT = "/api/projects/project1/chats/01";

    /** What the stub received: method, path with query, body. */
    record Seen(String method, String path, String body) {}

    interface Handler {
        void handle(HttpExchange ex, String body) throws Exception;
    }

    private HttpServer server;
    /** Keyed by "METHOD path", the path without its query. */
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private IIActorSystem system;
    private ChatUiIIAR chat;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Virtual threads, so a handler that hangs does not hold up the others.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            seen.add(new Seen(ex.getRequestMethod(), query == null ? path : path + "?" + query, body));
            Handler h = routes.get(ex.getRequestMethod() + " " + path);
            try {
                if (h == null) {
                    reply(ex, 404, "{\"type\":\"error\",\"message\":\"no route\"}");
                } else {
                    h.handle(ex, body);
                }
            } catch (Exception e) {
                // the client went away
            } finally {
                ex.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        system = new IIActorSystem("chatui-test", 2);
        chat = new ChatUiIIAR("chat", system);
        system.addIIActor(chat);
        assertTrue(call("setBaseUrl", new JSONObject().put("baseUrl", base + "/")).isSuccess());
    }

    @AfterEach
    void stop() {
        system.terminate();
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private ActionResult call(String action, Object args) {
        return chat.callByActionName(action, args == null ? "{}" : args.toString());
    }

    private void fastPolling(long totalSeconds, long requestSeconds) {
        assertTrue(call("setTimeLimits", new JSONObject().put("totalSeconds", totalSeconds)
                .put("requestSeconds", requestSeconds).put("pollMillis", 20)).isSuccess());
    }

    private static JSONObject entry(String role, String content, String turnId) {
        return new JSONObject().put("role", role).put("content", content).put("origin", JSONObject.NULL)
                .put("at", 1L).put("model", JSONObject.NULL).put("durationMs", JSONObject.NULL)
                .put("turnId", turnId == null ? JSONObject.NULL : turnId).put("requestId", JSONObject.NULL);
    }

    /**
     * A conversation as the server keeps one. It already holds an earlier turn {@code t0} with the same prompt, so a
     * send must not take that turn for its own. A posted prompt becomes turn {@code t1} at once and the server is busy;
     * after {@code pollsBeforeEnd} more history reads the turn ends with {@code ending} (an entry, or null for none)
     * and the server is idle again, unless {@code staysBusy}.
     */
    private final class Conversation {
        final List<JSONObject> history = new CopyOnWriteArrayList<>();
        final AtomicBoolean busy = new AtomicBoolean();
        final AtomicInteger readsAfterPost = new AtomicInteger(-1);

        Conversation(String prompt, JSONObject ending, int pollsBeforeEnd, boolean staysBusy) {
            history.add(entry("user", prompt, "t0"));
            history.add(entry("assistant", "the earlier answer", "t0"));
            routes.put("POST " + CHAT + "/chat", (ex, body) -> {
                history.add(entry("user", new JSONObject(body).getString("text"), "t1"));
                busy.set(true);
                readsAfterPost.set(0);
                reply(ex, 200, "{\"type\":\"accepted\"}");
            });
            routes.put("GET " + CHAT + "/conversation", (ex, body) -> {
                if (readsAfterPost.get() >= 0 && readsAfterPost.incrementAndGet() == pollsBeforeEnd + 1) {
                    if (ending != null) history.add(ending);
                    if (!staysBusy) busy.set(false);
                }
                reply(ex, 200, new JSONArray(history).toString());
            });
            routes.put("GET " + CHAT + "/status", (ex, body) -> reply(ex, 200,
                    new JSONObject().put("busy", busy.get()).put("model", "m1").put("provider", "openai-compat")
                            .put("tools", "full").put("noThink", false).toString()));
        }
    }

    // ── send ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send: posts the prompt, finds its turn in the history, answers with the turn's assistant entry")
    void sendReadsTheAnswerOfItsOwnTurn() {
        new Conversation("Say hello.", entry("assistant", "hello there", "t1"), 2, false);
        fastPolling(10, 5);

        ActionResult r = assertTimeoutPreemptively(LIMIT,
                () -> call("send", new JSONObject().put("text", "Say hello.").put("model", "m1")));

        assertTrue(r.isSuccess(), r.getResult());
        assertEquals("hello there", r.getResult());
        assertEquals("GET " + CHAT + "/conversation", seen.get(0).method() + " " + seen.get(0).path());
        assertEquals("POST " + CHAT + "/chat", seen.get(1).method() + " " + seen.get(1).path());
        JSONObject posted = new JSONObject(seen.get(1).body());
        assertEquals("Say hello.", posted.getString("text"));
        assertEquals("m1", posted.getString("model"));
        assertEquals("plugin-chatui", posted.getString("source"));
        assertFalse(posted.getBoolean("noThink"));
    }

    @Test
    @DisplayName("send: a turn that records an error is false with it")
    void turnError() {
        new Conversation("x", entry("error", "Error: the model server refused", "t1"), 1, false);
        fastPolling(10, 5);

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> call("send", new JSONObject().put("text", "x")));

        assertFalse(r.isSuccess(), r.getResult());
        assertEquals("turn t1 failed (" + base + CHAT + "/conversation): Error: the model server refused", r.getResult());
    }

    @Test
    @DisplayName("send: a turn that ends without an answer is false once the server is idle")
    void turnWithoutAnswer() {
        new Conversation("x", null, 1, false);
        fastPolling(10, 5);

        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> call("send", new JSONObject().put("text", "x")));

        assertFalse(r.isSuccess(), r.getResult());
        assertEquals("turn t1 of project1/chat-01 ended without an answer (" + base + CHAT
                + "/status says busy: false)", r.getResult());
    }

    @Test
    @DisplayName("send: the total limit runs out while the turn runs; false names the URL and the limit")
    void sendTotalLimitRunsOut() {
        new Conversation("x", null, Integer.MAX_VALUE - 1, true);
        fastPolling(1, 5);

        long t0 = System.nanoTime();
        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> call("send", new JSONObject().put("text", "x")));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertFalse(r.isSuccess(), r.getResult());
        assertTrue(r.getResult().matches("no answer from " + java.util.regex.Pattern.quote(base + CHAT)
                + "/(conversation|status) within the total limit of 1s"), r.getResult());
        assertTrue(ms >= 900 && ms < 5000, "ended after " + ms + " ms");
    }

    @Test
    @DisplayName("send: a history read that hangs after the first bytes is ended at the request limit")
    void hungReadIsEnded() {
        Conversation c = new Conversation("x", entry("assistant", "unused", "t1"), 5, false);
        CountDownLatch release = new CountDownLatch(1);
        routes.put("GET " + CHAT + "/conversation", (ex, body) -> {
            if (c.readsAfterPost.get() < 0) {
                reply(ex, 200, new JSONArray(c.history).toString());
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 1000);
            OutputStream o = ex.getResponseBody();
            o.write("[{\"role\":".getBytes(StandardCharsets.UTF_8));
            o.flush();
            release.await(30, TimeUnit.SECONDS);   // the rest of the body never comes
        });
        fastPolling(30, 1);

        long t0 = System.nanoTime();
        ActionResult r = assertTimeoutPreemptively(LIMIT, () -> call("send", new JSONObject().put("text", "x")));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        release.countDown();

        assertFalse(r.isSuccess(), r.getResult());
        assertEquals("GET " + base + CHAT + "/conversation did not answer within the request limit of 1s", r.getResult());
        assertTrue(ms < 5000, "ended after " + ms + " ms");
    }

    @Test
    @DisplayName("send: an error status from the server is false with the URL, the status and the body")
    void errorStatus() {
        new Conversation("x", null, 0, false);
        routes.put("POST " + CHAT + "/chat", (ex, body) -> reply(ex, 404,
                "{\"type\":\"error\",\"message\":\"no such conversation: project1/chat-01\"}"));

        ActionResult r = call("send", new JSONObject().put("text", "x"));

        assertFalse(r.isSuccess(), r.getResult());
        assertEquals("POST " + base + CHAT + "/chat returned HTTP 404: "
                + "{\"type\":\"error\",\"message\":\"no such conversation: project1/chat-01\"}", r.getResult());
    }

    @Test
    @DisplayName("send: a reply of an unexpected shape is false and quotes it")
    void unexpectedShape() {
        new Conversation("x", null, 0, false);
        routes.put("GET " + CHAT + "/conversation", (ex, body) -> reply(ex, 200, "<html>proxy error</html>"));

        ActionResult r = call("send", new JSONObject().put("text", "x"));

        assertFalse(r.isSuccess(), r.getResult());
        assertEquals("unexpected response from " + base + CHAT + "/conversation: expected a JSON array of history"
                + " entries, got: <html>proxy error</html>", r.getResult());
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel: posts .../chat/cancel and ends a send that is waiting, without queuing behind it")
    void cancelEndsAWaitingSend() throws Exception {
        new Conversation("x", null, Integer.MAX_VALUE - 1, true);
        routes.put("POST " + CHAT + "/chat/cancel", (ex, body) -> reply(ex, 200, "{\"type\":\"cancelled\"}"));
        fastPolling(30, 5);

        CompletableFuture<ActionResult> sending = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> sending.complete(call("send", new JSONObject().put("text", "x"))));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (seen.stream().noneMatch(s -> s.path().equals(CHAT + "/status")) && System.nanoTime() < until) {
            Thread.sleep(10);
        }

        ActionResult cancelled = assertTimeoutPreemptively(LIMIT, () -> call("cancel", null));
        ActionResult sent = sending.get(10, TimeUnit.SECONDS);

        assertTrue(cancelled.isSuccess(), cancelled.getResult());
        assertEquals("{\"type\":\"cancelled\"}", cancelled.getResult());
        assertFalse(sent.isSuccess(), sent.getResult());
        assertEquals("send: cancelled while waiting for " + base + CHAT + "/conversation", sent.getResult());
    }

    // ── the other actions ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getStatus and getConversation: GET .../status and .../conversation")
    void statusAndConversation() {
        new Conversation("x", null, 0, false);
        ActionResult status = call("getStatus", null);
        assertTrue(status.isSuccess(), status.getResult());
        assertEquals("openai-compat", new JSONObject(status.getResult()).getString("provider"));

        ActionResult conversation = call("getConversation", null);
        assertTrue(conversation.isSuccess(), conversation.getResult());
        assertEquals("t0", new JSONArray(conversation.getResult()).getJSONObject(0).getString("turnId"));
    }

    @Test
    @DisplayName("clearConversation: DELETE .../conversation")
    void clearConversation() {
        routes.put("DELETE " + CHAT + "/conversation", (ex, body) -> reply(ex, 200, "{\"type\":\"cleared\"}"));
        ActionResult r = call("clearConversation", null);
        assertTrue(r.isSuccess(), r.getResult());
        assertEquals("DELETE " + CHAT + "/conversation", seen.get(seen.size() - 1).method() + " " + seen.get(seen.size() - 1).path());
    }

    @Test
    @DisplayName("getModels: GET .../models, the JSON array; another shape is false")
    void getModels() {
        routes.put("GET " + CHAT + "/models", (ex, body) -> reply(ex, 200, "[{\"name\":\"m1\",\"type\":\"local\",\"server\":\"s\"}]"));
        ActionResult r = call("getModels", null);
        assertTrue(r.isSuccess(), r.getResult());
        assertEquals("m1", new JSONArray(r.getResult()).getJSONObject(0).getString("name"));

        routes.put("GET " + CHAT + "/models", (ex, body) -> reply(ex, 200, "{\"models\":[]}"));
        ActionResult wrong = call("getModels", null);
        assertFalse(wrong.isSuccess());
        assertTrue(wrong.getResult().startsWith("unexpected response from " + base + CHAT + "/models: expected a JSON array"),
                wrong.getResult());
    }

    @Test
    @DisplayName("setModel and setProvider: POST .../model and .../provider; a 400 is false with the server's reason")
    void modelAndProvider() {
        routes.put("POST " + CHAT + "/model", (ex, body) -> reply(ex, 200, "{\"type\":\"accepted\"}"));
        routes.put("POST " + CHAT + "/provider", (ex, body) -> {
            String tools = new JSONObject(body).optString("tools", "");
            if (tools.equals("nonsense")) {
                reply(ex, 400, "{\"type\":\"error\",\"message\":\"no tool set nonsense\"}");
            } else {
                reply(ex, 200, "{\"type\":\"accepted\",\"provider\":\"openai-compat\",\"tools\":\"full\"}");
            }
        });

        assertTrue(call("setModel", new JSONObject().put("model", "m2")).isSuccess());
        assertEquals("m2", new JSONObject(seen.get(seen.size() - 1).body()).getString("model"));

        ActionResult p = call("setProvider", new JSONObject().put("provider", "openai-compat"));
        assertTrue(p.isSuccess(), p.getResult());
        assertFalse(new JSONObject(seen.get(seen.size() - 1).body()).has("tools"));

        ActionResult refused = call("setProvider", new JSONObject().put("provider", "openai-compat").put("tools", "nonsense"));
        assertFalse(refused.isSuccess());
        assertEquals("POST " + base + CHAT + "/provider returned HTTP 400: {\"type\":\"error\",\"message\":\"no tool set nonsense\"}",
                refused.getResult());
    }

    @Test
    @DisplayName("setChat names the conversation every action works on; listChats reads the project's chats")
    void setChatAndListChats() {
        routes.put("GET /api/projects/project2/chats/05/status", (ex, body) -> reply(ex, 200, "{\"busy\":false}"));
        routes.put("GET /api/projects/project2/chats", (ex, body) -> reply(ex, 200, "[\"01\",\"05\"]"));

        ActionResult set = call("setChat", new JSONObject().put("projectId", "project2").put("chatId", "05"));
        assertTrue(set.isSuccess(), set.getResult());
        assertEquals("project2/chat-05", set.getResult());
        assertTrue(call("getStatus", null).isSuccess());
        ActionResult chats = call("listChats", null);
        assertTrue(chats.isSuccess(), chats.getResult());
        assertEquals("[\"01\",\"05\"]", chats.getResult());

        assertFalse(call("setChat", new JSONObject().put("projectId", "project2").put("chatId", "a/b")).isSuccess());
    }

    @Test
    @DisplayName("getSessions, getTurns and getTrace: /api/sessions, /{id}/turns, /{id}/trace/{turn}")
    void sessionsTurnsAndTrace() {
        routes.put("GET /api/sessions", (ex, body) -> reply(ex, 200, "[{\"sessionId\":7,\"workflowName\":\"chat-ui-conversation-project1/chat-01\"}]"));
        routes.put("GET /api/sessions/7/turns", (ex, body) -> reply(ex, 200, "{\"turns\":[{\"turn\":3,\"question\":\"x\"}],\"lastTurn\":3}"));
        routes.put("GET /api/sessions/7/trace/3", (ex, body) -> reply(ex, 200, "{\"turn\":3,\"steps\":[]}"));

        ActionResult mine = call("getSessions", null);
        assertTrue(mine.isSuccess(), mine.getResult());
        assertEquals("/api/sessions?conversationSquadId=project1%2Fchat-01", seen.get(seen.size() - 1).path());
        assertTrue(call("getSessions", new JSONObject().put("all", true)).isSuccess());
        assertEquals("/api/sessions", seen.get(seen.size() - 1).path());

        ActionResult turns = call("getTurns", new JSONObject().put("sessionId", 7).put("limit", 5));
        assertTrue(turns.isSuccess(), turns.getResult());
        assertEquals("/api/sessions/7/turns?limit=5", seen.get(seen.size() - 1).path());

        ActionResult trace = call("getTrace", new JSONObject().put("sessionId", 7).put("turn", 3));
        assertTrue(trace.isSuccess(), trace.getResult());
        assertEquals(3, new JSONObject(trace.getResult()).getInt("turn"));
    }

    @Test
    @DisplayName("setBaseUrl and setTimeLimits refuse what they cannot use")
    void settingsAreChecked() {
        assertFalse(call("setBaseUrl", new JSONObject().put("baseUrl", "localhost:28030")).isSuccess());
        assertFalse(call("setTimeLimits", new JSONObject().put("totalSeconds", 0)).isSuccess());
        ActionResult kept = call("setTimeLimits", new JSONObject().put("requestSeconds", 7));
        assertTrue(kept.isSuccess(), kept.getResult());
        assertTrue(kept.getResult().contains("request limit 7s"), kept.getResult());
    }

    // ── threads ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no real-thread pool of its own: the only real thread it adds is the HTTP client's selector, and close ends it")
    void noRealThreadPoolOfItsOwn() throws Exception {
        system.terminate();
        Set<Thread> before = realThreads();
        system = new IIActorSystem("chatui-threads", 2);
        chat = new ChatUiIIAR("chat", system);
        system.addIIActor(chat);
        call("setBaseUrl", new JSONObject().put("baseUrl", base));
        new Conversation("x", entry("assistant", "ok", "t1"), 3, false);
        fastPolling(10, 5);

        assertTrue(call("send", new JSONObject().put("text", "x")).isSuccess());
        Set<String> added = realThreads().stream().filter(t -> !before.contains(t))
                .map(Thread::getName).collect(Collectors.toSet());

        // The ManagedThreadPool of the test's own actor system makes no real thread here, since no action is given
        // to it; the HTTP client's selector is the one real thread the connection holds.
        assertTrue(added.stream().allMatch(n -> n.startsWith("HttpClient-") && n.endsWith("-SelectorManager")),
                "real threads added: " + added);

        chat.close();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (realThreads().stream().anyMatch(t -> !before.contains(t) && t.getName().startsWith("HttpClient-"))
                && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertTrue(realThreads().stream().noneMatch(t -> !before.contains(t) && t.getName().startsWith("HttpClient-")),
                "the selector real thread outlived close");
    }

    private static Set<Thread> realThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual()).collect(Collectors.toSet());
    }
}
