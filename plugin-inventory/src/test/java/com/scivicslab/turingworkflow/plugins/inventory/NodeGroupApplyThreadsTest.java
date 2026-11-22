package com.scivicslab.turingworkflow.plugins.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Which thread runs {@code NodeGroupActor}'s {@code apply} children, which thread waits for them,
 * and that apply does not hang a ManagedThreadPool.
 *
 * <p>Each child gives work to the ManagedThreadPool and waits for it, as a node's
 * {@code runWorkflow} does through its sub-workflow's POOL actions. Each test runs under a time
 * limit, so a hang fails the test instead of the build.</p>
 */
@DisplayName("NodeGroupActor.apply: threads and no hang")
class NodeGroupApplyThreadsTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);
    private static final String APPLY_ALL = "{\"actor\":\"*\",\"method\":\"work\"}";

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            // Fails any pool task still queued, which also releases a hung apply.
            system.terminate();
        }
    }

    /** What one child saw when it ran. */
    record ChildRun(String child, Thread thread, boolean onPoolRealThread) {}

    /** A node group registered as an actor, with children that record where they ran. */
    final class Group {
        final NodeGroupActor ref;
        final List<ChildRun> runs = new CopyOnWriteArrayList<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        Group(String name, int children, CyclicBarrier together) {
            ref = new NodeGroupActor(name, new NodeGroupInterpreter(new NodeGroup(), system), system);
            system.addIIActor(ref);
            for (int i = 0; i < children; i++) {
                addChild(name + "-node-" + i, together);
            }
        }

        private void addChild(String childName, CyclicBarrier together) {
            IIActorRef<Object> child = new IIActorRef<Object>(childName, new Object(), system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    ManagedThreadPool pool = (ManagedThreadPool) system.getManagedThreadPool();
                    runs.add(new ChildRun(childName, Thread.currentThread(), pool.ownsCurrentThread()));
                    mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        if (together != null) {
                            together.await(10, TimeUnit.SECONDS);
                        }
                        String answer = this.ask(o -> "pool work done", pool).get();
                        return new ActionResult(true, childName + ": " + answer);
                    } catch (Exception e) {
                        return ActionResult.error(e, childName + "." + actionName);
                    } finally {
                        running.decrementAndGet();
                    }
                }
            };
            child.setParentName(ref.getName());
            ref.getNamesOfChildren().add(childName);
            system.addIIActor(child);
        }

        ActionResult apply() {
            return ref.callByActionName("apply", APPLY_ALL);
        }
    }

    @Test
    @DisplayName("one ManagedThreadPool real thread, apply on it: it completes")
    void oneRealThreadOneApply() {
        system = new IIActorSystem("group-apply-one-thread", 1);
        Group group = new Group("group", 2, null);

        ActionResult result = assertTimeoutPreemptively(LIMIT,
            () -> group.ref.ask(o -> group.apply(), system.getManagedThreadPool()).get());

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(group.runs).hasSize(2);
    }

    @Test
    @DisplayName("on the mailbox virtual thread: children run in parallel on pool real threads, the virtual thread waits")
    void fromMailboxVirtualThread() {
        system = new IIActorSystem("group-apply-from-mailbox", 2);
        Group group = new Group("group", 2, new CyclicBarrier(2));
        AtomicReference<Thread> waiter = new AtomicReference<>();

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> group.ref.ask(o -> {
            waiter.set(Thread.currentThread());
            return group.apply();
        }).get());

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(result.getResult()).isEqualTo("Applied to 2 actors");
        assertThat(waiter.get().isVirtual()).as("apply waited on %s", waiter.get()).isTrue();
        Set<Thread> threads = new HashSet<>();
        for (ChildRun run : group.runs) {
            assertThat(run.onPoolRealThread()).as("%s ran on %s", run.child(), run.thread()).isTrue();
            assertThat(run.thread().isVirtual()).isFalse();
            threads.add(run.thread());
        }
        assertThat(threads).hasSize(2);
        assertThat(group.mostAtOnce.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("on a pool real thread: children run on that thread, one after another")
    void fromPoolRealThread() {
        system = new IIActorSystem("group-apply-from-pool", 2);
        Group group = new Group("group", 3, null);
        AtomicReference<Thread> applying = new AtomicReference<>();

        ActionResult result = assertTimeoutPreemptively(LIMIT, () -> group.ref.ask(o -> {
            applying.set(Thread.currentThread());
            return group.apply();
        }, system.getManagedThreadPool()).get());

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(result.getResult()).isEqualTo("Applied to 3 actors");
        assertThat(group.runs).hasSize(3);
        for (ChildRun run : group.runs) {
            assertThat(run.thread()).as(run.child()).isSameAs(applying.get());
            assertThat(run.onPoolRealThread()).isTrue();
        }
        assertThat(group.mostAtOnce.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("as many applies at once as the pool has real threads: none hangs")
    void groupsAsManyAsPoolThreads() {
        int n = 4;
        system = new IIActorSystem("group-apply-n-groups", n);
        List<Group> groups = new ArrayList<>();
        for (int g = 0; g < n; g++) {
            groups.add(new Group("group-" + g, 2, null));
        }
        CountDownLatch allHeld = new CountDownLatch(n);

        List<ActionResult> results = assertTimeoutPreemptively(LIMIT, () -> {
            List<CompletableFuture<ActionResult>> futures = new ArrayList<>();
            for (Group group : groups) {
                futures.add(group.ref.ask(o -> {
                    allHeld.countDown();
                    try {
                        allHeld.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return group.apply();
                }, system.getManagedThreadPool()));
            }
            List<ActionResult> done = new ArrayList<>();
            for (CompletableFuture<ActionResult> f : futures) {
                done.add(f.get());
            }
            return done;
        });

        for (ActionResult result : results) {
            assertThat(result.isSuccess()).as(result.getResult()).isTrue();
            assertThat(result.getResult()).isEqualTo("Applied to 2 actors");
        }
    }
}
