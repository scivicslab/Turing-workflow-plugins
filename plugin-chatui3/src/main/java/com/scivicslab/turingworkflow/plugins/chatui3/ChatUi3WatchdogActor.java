package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Watchdog actor whose sole job is to stop a guarded {@link ChatUi3Actor}'s in-flight SSE
 * stream.
 *
 * <p>{@code ChatUi3Actor.chat()} blocks reading the SSE stream until a {@code result} event
 * arrives. If the LLM / quarkus-chat-ui3 never sends one, that read blocks forever and the
 * workflow that issued the {@code chat} action never finishes. The blocked thread cannot stop
 * itself, so the stop must come from a different thread.</p>
 *
 * <p>This actor provides that other thread. Every {@link ChatUi3Actor} is paired with one
 * watchdog (created automatically in the {@code ChatUi3Actor} constructor). The watchdog runs
 * on its own message-loop thread, so even while the guarded actor's {@code chat()} is blocked
 * on a worker-pool thread, the watchdog can react to a {@code trip} message — or be closed —
 * and call {@link ChatUi3Actor#stopSse()} to force the blocked read to abort.</p>
 *
 * <p>Two ways to trip it:</p>
 * <ul>
 *   <li>{@code trip} action — a parallel workflow branch (a second Interpreter) or an external
 *       supervisor sends this message when it decides the chat must stop;</li>
 *   <li>{@link #close()} — called during actor-system shutdown, ensuring a stuck chat is
 *       released so the process can tear down.</li>
 * </ul>
 *
 * <p>This is a deliberate alternative to a timeout: the decision to stop is made explicitly by
 * a controller, not by elapsed time.</p>
 */
public class ChatUi3WatchdogActor extends IIActorRef<Object> {

    /** The chat actor this watchdog guards. */
    private final ChatUi3Actor guarded;

    /**
     * Creates a watchdog for the given chat actor.
     *
     * @param name    the watchdog actor name (by convention {@code <guarded>-watchdog})
     * @param guarded the chat actor whose SSE stream this watchdog can stop
     * @param system  the actor system
     */
    public ChatUi3WatchdogActor(String name, ChatUi3Actor guarded, IIActorSystem system) {
        super(name, new Object(), system);
        this.guarded = guarded;
    }

    /**
     * Trips the watchdog: stops the guarded actor's in-flight SSE read so a stuck
     * {@code chat()} returns.
     *
     * @param args ignored
     * @return success result
     */
    @Action("trip")
    public ActionResult trip(String args) {
        guarded.stopSse();
        return new ActionResult(true, "watchdog tripped: guarded SSE stopped");
    }

    /**
     * Stops the guarded actor's SSE stream as part of shutdown, then performs the normal
     * actor cleanup.
     */
    @Override
    public void close() {
        guarded.stopSse();
        super.close();
    }
}
