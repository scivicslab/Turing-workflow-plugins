/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.logoutput;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Distributes output messages to multiple downstream accumulators simultaneously, such as the console, files, or a
 * database.
 *
 * <ul>
 * <li>{@code add} broadcasts a message containing a source, a type, and the data to all registered targets.</li>
 * <li>{@code getSummary} returns a formatted text summary of all downstream accumulators.</li>
 * <li>{@code getCount} returns the total number of entries added across all targets as a string.</li>
 * <li>{@code clear} resets the counters and clears all accumulated entries in all downstream targets.</li>
 * </ul>
 *
 * <p>One instance serves a single actor name in the system, but its actions may be called by multiple workflows or
 * threads concurrently.</p>
 *
 * <p>Example workflow call:
 * <pre>{@code
 * actor: outputMultiplexer
 * method: add
 * arguments:
 *   source: node-web1
 *   type: stdout
 *   data: Deployment complete
 * }</pre>
 *
 * <p>The {@code add} action returns a success result once the message is enqueued, not necessarily when it has been
 * delivered to the targets.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerAccumulatorActor extends IIActorRef<MultiplexerAccumulator> {

    /** A logger instance used to record errors occurring within this actor. */
    private final Logger logger;

    /**
     * Registers a new {@code MultiplexerAccumulator} in the actor system under the specified name so that workflows
     * can call its actions.
     *
     * @param actorName the unique name of this actor within the system
     * @param object the {@link MultiplexerAccumulator} instance to be used by the actor
     * @param system the actor system this actor belongs to
     */
    public MultiplexerAccumulatorActor(String actorName, MultiplexerAccumulator object, IIActorSystem system) {
        super(actorName, object, system);
        this.logger = Logger.getLogger(actorName);
    }

    /**
     * Adds an output entry to all registered downstream accumulators.
     *
     * <p><b>Expected JSON args format:</b></p>
     * <pre>{@code
     * {
     *   "source": "node-name",
     *   "type":   "stdout" | "stderr" | "cowsay" | "log-*",
     *   "data":   "the output text"
     * }
     * }</pre>
     *
     * <p><b>Note:</b> Do NOT log inside this method — it would cause an
     * infinite loop via {@link MultiplexerLogHandler}.</p>
     *
     * <p>Fire-and-forget: enqueues the add onto this actor's own mailbox and returns immediately,
     * without waiting for it to run. Callers that need the delivery/target-count guarantee should
     * use {@code ask} against the wrapped {@link MultiplexerAccumulator} directly instead of this
     * action. Matches how callers of this action already treat log delivery as best-effort (e.g.
     * {@code chat-ui-with-audit-trail}'s per-turn log forwarding never checks this action's
     * {@link ActionResult}) — waiting here only serialized the caller's own thread behind this
     * actor's mailbox for no benefit.</p>
     *
     * @param arg JSON string containing {@code source}, {@code type}, and {@code data} fields
     * @return an {@link ActionResult}; {@code success=true} means the add was enqueued, not that it
     *         has run yet
     */
    /**
     * Arguments for the {@code add} action that distributes an output entry to all registered downstream accumulators.
     *
     * @param source the name of the entity that produced the entry
     * @param type the category of the entry, such as {@code stdout}, {@code stderr}, {@code cowsay}, or {@code log-*}
     * @param data the text content of the entry
     */
    public record AddArgs(@NotNull String source, @NotNull String type, @NotNull String data) {}

    @Action(value = "add", argsType = AddArgs.class)
    public ActionResult add(AddArgs args) {
        // Do NOT log through JUL anywhere on this path: MultiplexerLogHandler calls this action, so
        // a log call here re-enters it. Failures go to System.err. The entry is handed to the
        // multiplexer without waiting, so logging never blocks the caller.
        try {
            String source = args.source();
            String type = args.type();
            String data = args.data();

            this.tell(acc -> acc.add(source, type, data))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        writeToStderr(args, failure);
                    }
                });

            return new ActionResult(true, "Enqueued");
        } catch (Exception e) {
            writeToStderr(args, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /** Writes an entry the multiplexer could not take to System.err, so that it is not lost. */
    private static void writeToStderr(AddArgs args, Throwable failure) {
        System.err.println("MultiplexerAccumulator: could not write the entry (" + failure + "): "
                + args.source() + " " + args.type() + " " + args.data());
    }

    /**
     * Returns a multi-line string that is a formatted summary of all downstream accumulators.
     *
     * @param arg an ignored argument required by the action contract
     * @return an action result that is a success with a message containing the multi-line summary text, or a failure
     * with an error message if the summary could not be retrieved
     */
    @Action("getSummary")
    public ActionResult getSummary(String arg) {
        try {
            String summary = this.ask(MultiplexerAccumulator::getSummary).get();
            return new ActionResult(true, summary);
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in getSummary", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns a string representing the total number of entries that have been added to all downstream accumulators.
     *
     * <p>The result is the numeric count of entries as a string.</p>
     *
     * @param arg an ignored argument required by the action contract
     * @return an action result that is a success with the count as its message, or is not a success if the count could
     * not be retrieved
     */
    @Action("getCount")
    public ActionResult getCount(String arg) {
        try {
            int count = this.ask(MultiplexerAccumulator::getCount).get();
            return new ActionResult(true, String.valueOf(count));
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in getCount", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Resets the counters and clears all accumulated entries in this multiplexer and all its downstream targets.
     *
     * <p>The result is an {@link ActionResult} where a success indicates the clear operation was completed, and the
     * message holds the status or an error description.</p>
     *
     * <p>The argument is ignored.</p>
     *
     * @param arg an ignored argument required by the action contract
     */
    @Action("clear")
    public ActionResult clear(String arg) {
        try {
            this.tell(MultiplexerAccumulator::clear).get();
            return new ActionResult(true, "Cleared");
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in clear", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
