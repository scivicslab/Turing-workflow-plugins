package com.scivicslab.turingworkflow.plugins.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Transition;

/**
 * The log store receives the transition logs of {@link NodeGroupInterpreter} in the order the transitions were entered,
 * and the pool it is given is a {@link ManagedThreadPool}, also when that pool has more than one real thread.
 */
@DisplayName("NodeGroupInterpreter - the log store sees the transition logs in the order sent")
class NodeGroupInterpreterWriteOrderTest {

    private static final int N = 200;

    private IIActorSystem system;
    private ManagedThreadPool dbPool;
    private ActorRef<DistributedLogStore> logStoreActor;
    private final List<String> labels = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("node-group-interpreter-write-order-test");
        dbPool = new ManagedThreadPool(4);
        DistributedLogStore recording = (DistributedLogStore) Proxy.newProxyInstance(
                DistributedLogStore.class.getClassLoader(), new Class<?>[] { DistributedLogStore.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("log") && args.length == 5) {
                        labels.add((String) args[2]);
                    }
                    return null;
                });
        logStoreActor = new ActorRef<>("logStore", recording);
    }

    @AfterEach
    void tearDown() {
        logStoreActor.close();
        dbPool.shutdownNow();
        system.terminate();
    }

    @Test
    @DisplayName("onEnterTransition: N logs sent in a row reach the store in that order")
    void onEnterTransition_logsKeepTheOrderSent() throws Exception {
        NodeGroupInterpreter interpreter = new NodeGroupInterpreter(new NodeGroup(), system);
        interpreter.setLogStore(null, logStoreActor, dbPool, 1L);
        assertThat(interpreter.getDbExecutor()).isSameAs(dbPool);

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Transition t = new Transition();
            t.setLabel("t" + i);
            t.setStates(List.of("s0", "s1"));
            expected.add("t" + i);
            interpreter.onEnterTransition(t);
        }
        // An ask given the same pool is queued behind every log above, so it returns when they have all run.
        logStoreActor.ask(store -> null, dbPool).get(10, TimeUnit.SECONDS);

        assertThat(labels).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("the pool type of setLogStore and getDbExecutor is ManagedThreadPool")
    void poolTypeIsManagedThreadPool() throws Exception {
        assertThat(NodeGroupInterpreter.class.getMethod("getDbExecutor").getReturnType()).isEqualTo(ManagedThreadPool.class);
        assertThat(NodeGroupInterpreter.class.getMethod("setLogStore", DistributedLogStore.class, ActorRef.class,
                ManagedThreadPool.class, long.class)).isNotNull();
    }
}
