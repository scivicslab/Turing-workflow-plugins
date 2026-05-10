package com.scivicslab.turingworkflow.plugins.logdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class H2LogStoreTest {

    private H2LogStore store;

    @BeforeEach
    void setUp() throws SQLException {
        store = new H2LogStore(); // in-memory H2
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    void startSession_returnsPositiveId() {
        long sessionId = store.startSession("my-workflow", null, null, 3);
        assertThat(sessionId).isPositive();
    }

    @Test
    void startSession_idIsIncreasing() {
        long id1 = store.startSession("wf-a", null, null, 1);
        long id2 = store.startSession("wf-b", null, null, 2);
        assertThat(id2).isGreaterThan(id1);
    }

    @Test
    void log_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 1);
        store.log(sessionId, "node-01", LogLevel.INFO, "Started processing");
    }

    @Test
    void logAction_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 1);
        store.logAction(sessionId, "node-01", "deploy", "runScript", 0, 1234L, "OK");
    }

    @Test
    void markNodeSuccess_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 2);
        store.markNodeSuccess(sessionId, "node-01");
    }

    @Test
    void markNodeFailed_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 2);
        store.markNodeFailed(sessionId, "node-02", "Connection refused");
    }

    @Test
    void endSession_withCompleted_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 1);
        store.markNodeSuccess(sessionId, "node-01");
        store.endSession(sessionId, SessionStatus.COMPLETED);
    }

    @Test
    void endSession_withFailed_doesNotThrow() {
        long sessionId = store.startSession("wf", null, null, 1);
        store.markNodeFailed(sessionId, "node-01", "Timeout");
        store.endSession(sessionId, SessionStatus.FAILED);
    }

    @Test
    void startSession_withAllMetadata_doesNotThrow() {
        long sessionId = store.startSession("wf", "prod", "hosts.yml", 3);
        assertThat(sessionId).isPositive();
        store.log(sessionId, "node-01", LogLevel.INFO, "Starting");
        store.log(sessionId, "node-02", LogLevel.WARN, "Slow response");
        store.log(sessionId, "node-03", LogLevel.ERROR, "Connection failed");
        store.endSession(sessionId, SessionStatus.FAILED);
    }

    @Test
    void getLatestSessionId_returnsPositiveAfterSessionCreated() {
        store.startSession("wf", null, null, 1);
        long latest = store.getLatestSessionId();
        assertThat(latest).isPositive();
    }
}
