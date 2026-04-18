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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Actor reference for MultiplexerAccumulator.
 *
 * <p>Supported Actions:</p>
 * <ul>
 *   <li>{@code add} - Adds output to all registered accumulators</li>
 *   <li>{@code getSummary} - Returns formatted summary</li>
 *   <li>{@code getCount} - Returns the number of added entries</li>
 *   <li>{@code clear} - Clears all accumulated entries</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerAccumulatorActor extends IIActorRef<MultiplexerAccumulator> {

    /** Logger instance named after this actor. */
    private final Logger logger;

    /**
     * Constructs a new {@code MultiplexerAccumulatorActor} wrapping the given
     * {@link MultiplexerAccumulator} and registering it in the actor system.
     *
     * @param actorName the unique name of this actor within the system
     * @param object    the {@link MultiplexerAccumulator} instance to wrap
     * @param system    the actor system this actor belongs to
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
     * @param arg JSON string containing {@code source}, {@code type}, and {@code data} fields
     * @return an {@link ActionResult} with success status and message
     */
    @Action("add")
    public ActionResult add(String arg) {
        // Note: Do NOT log here - it causes infinite loop via MultiplexerLogHandler
        try {
            JSONObject json = new JSONObject(arg);
            String source = json.getString("source");
            String type = json.getString("type");
            String data = json.getString("data");

            this.tell(acc -> acc.add(source, type, data)).get();

            return new ActionResult(true, "Added");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in add", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns a formatted summary of all downstream accumulators.
     *
     * <p><b>Expected JSON args format:</b> any string (ignored).</p>
     *
     * @param arg ignored argument (required by the {@link Action} contract)
     * @return an {@link ActionResult} whose message is the multi-line summary text
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
     * Returns the total number of entries that have been added.
     *
     * <p><b>Expected JSON args format:</b> any string (ignored).</p>
     *
     * @param arg ignored argument (required by the {@link Action} contract)
     * @return an {@link ActionResult} whose message is the numeric count as a string
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
     * Clears all accumulated entries in this multiplexer and all downstream targets.
     *
     * <p><b>Expected JSON args format:</b> any string (ignored).</p>
     *
     * @param arg ignored argument (required by the {@link Action} contract)
     * @return an {@link ActionResult} indicating success or failure
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
