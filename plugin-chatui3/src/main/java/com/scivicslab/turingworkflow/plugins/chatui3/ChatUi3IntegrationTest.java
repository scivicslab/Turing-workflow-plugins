package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Integration test for {@link ChatUi3Actor}, run against a live quarkus-chat-ui3 process
 * and a live vLLM server. Implemented as a main() program (not JUnit) because the steps
 * form a stateful, order-dependent flow.
 *
 * <p>Specification: PluginChatUi3IntegrationTest_260617_oo01 (doc_SCIVICS003).</p>
 *
 * <p>Preconditions:
 * <ul>
 *   <li>quarkus-chat-ui3 running on localhost:18090</li>
 *   <li>vLLM running on the node pointed to by chatui3.vllm-base-url</li>
 * </ul>
 * Exits 0 on success, non-zero (via thrown exception) on any failure.</p>
 */
public class ChatUi3IntegrationTest {

    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:18090";

        IIActorSystem system = new IIActorSystem("chatui3-it");
        ChatUi3Actor actor = new ChatUi3Actor("chatui3", system);
        actor.setBaseUrl(baseUrl);

        System.out.println("=== plugin-chatui3 integration test ===");
        System.out.println("Target: " + baseUrl);

        // Step 1: getModels — model list must not be empty
        System.out.println("\n[Step 1] getModels");
        ActionResult models = actor.getModels("");
        require(models.isSuccess(), "getModels failed: " + models.getResult());
        JSONArray modelArr = new JSONObject(models.getResult()).getJSONArray("models");
        require(modelArr.length() > 0, "model list is empty");
        System.out.println("  models = " + modelArr);

        // Step 2: clearHistory — reset session
        System.out.println("\n[Step 2] clearHistory");
        ActionResult cleared = actor.clearHistory("");
        require(cleared.isSuccess(), "clearHistory failed: " + cleared.getResult());
        System.out.println("  history cleared");

        // Step 3: chat — response text must not be empty
        System.out.println("\n[Step 3] chat");
        ActionResult chat = actor.chat("Reply with exactly 3 words.");
        require(chat.isSuccess(), "chat failed: " + chat.getResult());
        require(chat.getResult() != null && !chat.getResult().isBlank(),
                "chat response is empty");
        System.out.println("  response = " + chat.getResult());

        // Step 4: getTrace — at least 1 turn, promptTokens > 0
        System.out.println("\n[Step 4] getTrace");
        ActionResult trace = actor.getTrace("");
        require(trace.isSuccess(), "getTrace failed: " + trace.getResult());
        JSONArray turns = new JSONArray(trace.getResult());
        require(turns.length() >= 1, "trace has no turns");
        int promptTokens = turns.getJSONObject(0).getInt("promptTokens");
        require(promptTokens > 0, "promptTokens is not greater than 0");
        System.out.println("  turns = " + turns.length() + ", promptTokens = " + promptTokens);

        // Step 5: updateConfig — set temperature to 0.2
        System.out.println("\n[Step 5] updateConfig temperature=0.2");
        ActionResult upd = actor.updateConfig("{\"temperature\":0.2}");
        require(upd.isSuccess(), "updateConfig failed: " + upd.getResult());
        System.out.println("  config updated");

        // Step 6: getConfig — temperature must be 0.2
        System.out.println("\n[Step 6] getConfig");
        ActionResult cfg = actor.getConfig("");
        require(cfg.isSuccess(), "getConfig failed: " + cfg.getResult());
        double temp = new JSONObject(cfg.getResult()).getDouble("temperature");
        require(Math.abs(temp - 0.2) < 0.001, "temperature was not updated to 0.2, got " + temp);
        System.out.println("  temperature = " + temp);

        System.out.println("\n=== ALL STEPS PASSED ===");
        system.terminate();
        System.exit(0);
    }

    private static void require(boolean condition, String failureMessage) {
        if (!condition) {
            throw new AssertionError(failureMessage);
        }
    }
}
