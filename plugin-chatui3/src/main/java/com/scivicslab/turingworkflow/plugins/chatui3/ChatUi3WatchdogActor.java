package com.scivicslab.turingworkflow.plugins.chatui3;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * A watchdog actor that terminates a blocked SSE stream in a paired {@link ChatUi3Actor}.
 *
 * <ul>
 * <li>{@code trip} stops the guarded actor's in-flight SSE read and returns a success result with a message.</li>
 * </ul>
 *
 * <p>Each {@link ChatUi3Actor} is paired with one watchdog. The watchdog runs on its own message-loop thread, allowing
 * it to interrupt a {@code ChatUi3Actor#chat()} action that is blocked waiting for an LLM response.</p>
 *
 * <p>The watchdog can be tripped by a parallel workflow branch or an external supervisor calling the {@code trip}
 * action, or it can be closed during an actor-system shutdown.</p>
 */
public class ChatUi3WatchdogActor extends IIActorRef<Object> {

    /** The {@link ChatUi3Actor} whose SSE stream this watchdog stops. */
    private final ChatUi3Actor guarded;

    /**
     * Initializes a watchdog that can abort a {@link ChatUi3Actor}'s blocked SSE stream read.
     *
     * @param name the name of this watchdog actor
     * @param guarded the chat actor whose SSE stream this watchdog can stop
     * @param system the actor system
     */
    public ChatUi3WatchdogActor(String name, ChatUi3Actor guarded, IIActorSystem system) {
        super(name, new Object(), system);
        this.guarded = guarded;
    }

    /**
     * Stops the guarded {@link ChatUi3Actor}'s in-flight SSE stream so that a blocked {@code chat()} action returns.
     *
     * @param args ignored
     * @return a success result with the message "watchdog tripped: guarded SSE stopped"
     */
    @Action("trip")
    public ActionResult trip(String args) {
        guarded.stopSse();
        return new ActionResult(true, "watchdog tripped: guarded SSE stopped");
    }

    /** Stops the guarded {@link ChatUi3Actor}'s SSE stream and performs the normal actor cleanup. */
    @Override
    public void close() {
        guarded.stopSse();
        super.close();
    }
}
