package com.scivicslab.turingworkflow.plugins.logdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Checks that the write actions a workflow calls on purpose report a write the database refuses as false with the
 * database's reason, instead of true; a write that succeeds is still true.
 *
 * <p>The database refuses the write because session 999 does not exist (the logs and node results reference their
 * session).</p>
 */
@DisplayName("LogStoreActor - a refused write is false with the reason")
class LogStoreActorWriteFailureTest {

    private IIActorSystem system;
    private H2LogStore store;
    private ExecutorService dbExecutor;
    private LogStoreActor actor;

    @BeforeEach
    void setUp() throws Exception {
        system = new IIActorSystem("log-store-write-failure-test");
        store = new H2LogStore();
        dbExecutor = Executors.newSingleThreadExecutor();
        actor = new LogStoreActor("logStore", store, system, dbExecutor);
        system.addIIActor(actor);
    }

    @AfterEach
    void tearDown() throws Exception {
        system.terminate();
        dbExecutor.shutdownNow();
        store.close();
    }

    @Test
    void log_sessionMissing_isFalseWithTheReason() {
        ActionResult result = actor.callByActionName("log", new JSONObject().put("sessionId", 999).put("nodeId", "n1")
                .put("level", "INFO").put("message", "hello").toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("Referential integrity");
    }

    @Test
    void logAction_sessionMissing_isFalseWithTheReason() {
        ActionResult result = actor.callByActionName("logAction", new JSONObject().put("sessionId", 999).put("nodeId", "n1")
                .put("label", "deploy").put("actionName", "run").put("exitCode", 0).put("durationMs", 5).put("output", "ok").toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("Referential integrity");
    }

    @Test
    void markNodeSuccess_sessionMissing_isFalseWithTheReason() {
        ActionResult result = actor.callByActionName("markNodeSuccess", new JSONObject().put("sessionId", 999).put("nodeId", "n1").toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("Referential integrity");
    }

    @Test
    void markNodeFailed_sessionMissing_isFalseWithTheReason() {
        ActionResult result = actor.callByActionName("markNodeFailed", new JSONObject().put("sessionId", 999).put("nodeId", "n1")
                .put("reason", "timeout").toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("Referential integrity");
    }

    @Test
    void log_sessionExists_isTrue() {
        long session = store.startSession("wf", 1);

        ActionResult result = actor.callByActionName("log", new JSONObject().put("sessionId", session).put("nodeId", "n1")
                .put("level", "INFO").put("message", "hello").toString());

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(actor.callByActionName("markNodeSuccess", new JSONObject().put("sessionId", session).put("nodeId", "n1").toString())
                .isSuccess()).isTrue();
    }
}
