package com.scivicslab.turingworkflow.plugins.chatui;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Runs every action of {@link ChatUiIIAR} once against a real chat-ui-with-audit-trail server, on its
 * {@code project1/chat-01}, put on the {@code openai-compat} provider and a scripted model that answers
 * {@code "scripted: <prompt>"} and sleeps first when the prompt contains {@code slow}. Not a unit test: it needs a
 * running server, so the build does not run it.
 *
 * <pre>{@code java -cp <test classpath> com.scivicslab.turingworkflow.plugins.chatui.ChatUiLiveCheck http://127.0.0.1:18030 scripted-model}</pre>
 *
 * <p>Prints one line per action and exits 0 when every check held, 1 otherwise.</p>
 */
public final class ChatUiLiveCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:18030";
        String model = args.length > 1 ? args[1] : "scripted-model";
        IIActorSystem system = new IIActorSystem("chatui-live", 2);
        ChatUiIIAR chat = new ChatUiIIAR("chat", system);
        system.addIIActor(chat);
        try {
            check("setBaseUrl", call(chat, "setBaseUrl", new JSONObject().put("baseUrl", base)), true, base);
            check("setChat", call(chat, "setChat", new JSONObject().put("projectId", "project1").put("chatId", "01")),
                    true, "project1/chat-01");
            check("listChats", call(chat, "listChats", null), true, "\"01\"");
            check("setProvider", call(chat, "setProvider", new JSONObject().put("provider", "openai-compat")), true, "accepted");
            check("setModel", call(chat, "setModel", new JSONObject().put("model", model)), true, "accepted");
            check("getStatus", call(chat, "getStatus", null), true, "\"provider\":\"openai-compat\"");
            check("getModels", call(chat, "getModels", null), true, model);
            check("setTimeLimits", call(chat, "setTimeLimits",
                    new JSONObject().put("totalSeconds", 120).put("requestSeconds", 10).put("pollMillis", 200)), true, "total limit 120s");
            check("send", call(chat, "send", new JSONObject().put("text", "ping")), true, "scripted: ping");
            check("getConversation", call(chat, "getConversation", null), true, "scripted: ping");
            ActionResult sessions = call(chat, "getSessions", null);
            check("getSessions", sessions, true, "chat-ui-conversation-project1/chat-01");
            JSONArray list = sessions.isSuccess() ? new JSONArray(sessions.getResult()) : new JSONArray();
            if (list.length() > 0) {
                long id = list.getJSONObject(0).getLong("sessionId");
                ActionResult turns = call(chat, "getTurns", new JSONObject().put("sessionId", id));
                check("getTurns", turns, true, "\"turns\"");
                int last = turns.isSuccess() ? new JSONObject(turns.getResult()).optInt("lastTurn", 1) : 1;
                check("getTrace", call(chat, "getTrace", new JSONObject().put("sessionId", id).put("turn", last)), true, "{");
            } else {
                failures++;
                System.out.println("[live] FAIL getTurns, getTrace: the server lists no session for project1/chat-01");
            }
            check("clearConversation", call(chat, "clearConversation", null), true, "cleared");
            call(chat, "setTimeLimits", new JSONObject().put("totalSeconds", 3));
            check("send (total limit)", call(chat, "send", new JSONObject().put("text", "slow please")), false,
                    "within the total limit of 3s");
            check("cancel", call(chat, "cancel", null), true, "cancelled");
        } finally {
            system.terminate();
        }
        System.out.println("[live] " + (failures == 0 ? "all checks held" : failures + " check(s) failed"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ActionResult call(ChatUiIIAR chat, String action, JSONObject args) {
        return chat.callByActionName(action, args == null ? "{}" : args.toString());
    }

    private static void check(String what, ActionResult r, boolean success, String contains) {
        boolean held = r.isSuccess() == success && r.getResult().contains(contains);
        if (!held) failures++;
        String text = r.getResult().length() > 240 ? r.getResult().substring(0, 240) + "..." : r.getResult();
        System.out.println("[live] " + (held ? "ok  " : "FAIL") + " " + what + ": " + r.isSuccess() + " " + text);
    }
}
