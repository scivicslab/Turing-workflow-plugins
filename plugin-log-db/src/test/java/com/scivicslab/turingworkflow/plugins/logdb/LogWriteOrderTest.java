package com.scivicslab.turingworkflow.plugins.logdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The database receives the writes of {@link LogStoreActor} and {@link DatabaseAccumulator} in the order they were
 * sent, on a {@link ManagedThreadPool} that has more than one real thread: the actor's turn, not the size of the
 * pool, keeps the order.
 */
@DisplayName("LogStoreActor and DatabaseAccumulator - writes reach the database in the order sent")
class LogWriteOrderTest {

    private static final int N = 200;

    private IIActorSystem system;
    private H2LogStore store;
    private ManagedThreadPool dbPool;
    private LogStoreActor actor;

    @BeforeEach
    void setUp() throws Exception {
        system = new IIActorSystem("log-write-order-test");
        store = new H2LogStore();
        dbPool = new ManagedThreadPool(4);
        actor = new LogStoreActor("logStore", store, system, dbPool);
        system.addIIActor(actor);
    }

    @AfterEach
    void tearDown() throws Exception {
        system.terminate();
        dbPool.shutdownNow();
        store.close();
    }

    @Test
    @DisplayName("the pool of the actor is the ManagedThreadPool it was given")
    void getDbExecutor_isTheManagedThreadPoolGiven() {
        assertThat(actor.getDbExecutor()).isSameAs(dbPool);
    }

    @Test
    @DisplayName("log actions called one after another are stored in that order")
    void logActions_keepTheOrderCalled() {
        long session = store.startSession("wf", 1);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            expected.add("m" + i);
            actor.callByActionName("log", new JSONObject().put("sessionId", session).put("nodeId", "n1")
                    .put("level", "INFO").put("message", "m" + i).toString());
        }

        assertThat(store.getLogsByNode(session, "n1")).extracting(LogEntry::getMessage).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("DatabaseAccumulator.add (fire and forget) entries are stored in the order added")
    void accumulatorAdd_keepsTheOrderAdded() throws Exception {
        long session = store.startSession("wf", 1);
        ActorRef<DistributedLogStore> storeActor = new ActorRef<>("accStore", store);
        try {
            DatabaseAccumulator accumulator = new DatabaseAccumulator(storeActor, dbPool, session);
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                accumulator.add("n2", "output", "line" + i);
                expected.add("[n2] line" + i);
            }
            // queued behind every write above in the same actor and pool
            storeActor.ask(s -> null, dbPool).get(10, TimeUnit.SECONDS);

            assertThat(store.getLogsByNode(session, "n2")).extracting(LogEntry::getMessage)
                    .containsExactlyElementsOf(expected);
        } finally {
            storeActor.close();
        }
    }
}
