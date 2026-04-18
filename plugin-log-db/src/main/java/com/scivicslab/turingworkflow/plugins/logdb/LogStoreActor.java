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

package com.scivicslab.turingworkflow.plugins.logdb;

import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Actor wrapper for DistributedLogStore.
 *
 * <p>This actor centralizes all database writes for logging. It should be created
 * under ROOT and used by all accumulator actors in the system.</p>
 *
 * <h2>Actor Tree Position</h2>
 * <pre>
 * ROOT
 * ├── logStore              &lt;-- this actor
 * ├── accumulator           (system-level)
 * └── nodeGroup
 *     ├── accumulator       (workflow-level)
 *     └── node-*
 * </pre>
 *
 * <h2>Supported Actions</h2>
 * <ul>
 *   <li>{@code log} - Log a message with level</li>
 *   <li>{@code logAction} - Log an action result</li>
 *   <li>{@code startSession} - Start a new workflow session</li>
 *   <li>{@code endSession} - End a workflow session</li>
 *   <li>{@code markNodeSuccess} - Mark a node as succeeded</li>
 *   <li>{@code markNodeFailed} - Mark a node as failed</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class LogStoreActor extends IIActorRef<DistributedLogStore> {

    private static final Logger logger = Logger.getLogger(LogStoreActor.class.getName());

    /**
     * The dedicated executor service for DB writes.
     * Using a single-threaded pool ensures writes are serialized.
     */
    private final ExecutorService dbExecutor;

    /**
     * Constructs a new LogStoreActor.
     *
     * @param actorName the name of this actor (typically "logStore")
     * @param logStore the DistributedLogStore implementation
     * @param system the actor system
     * @param dbExecutor the dedicated executor service for DB writes (should be single-threaded)
     */
    public LogStoreActor(String actorName, DistributedLogStore logStore,
                         IIActorSystem system, ExecutorService dbExecutor) {
        super(actorName, logStore, system);
        this.dbExecutor = dbExecutor;
    }

    /**
     * Logs a message with level.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "nodeId": "node-01",
     *   "level": "INFO",
     *   "message": "Processing started"
     * }
     * }</pre>
     *
     * @param arg JSON string containing sessionId (long), nodeId (String),
     *            level (String matching {@link LogLevel}), and message (String)
     * @return {@link ActionResult} with success=true and message "Logged", or
     *         success=false with the error message on failure
     */
    @Action("log")
    public ActionResult log(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String levelStr = json.getString("level");
            String message = json.getString("message");

            LogLevel level = LogLevel.valueOf(levelStr);

            this.tell(store -> store.log(sessionId, nodeId, level, message), dbExecutor).get();

            return new ActionResult(true, "Logged");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in log", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Logs an action result with execution metadata.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "nodeId": "node-01",
     *   "label": "deploy",
     *   "actionName": "runScript",
     *   "exitCode": 0,
     *   "durationMs": 1234,
     *   "output": "Script completed successfully"
     * }
     * }</pre>
     *
     * @param arg JSON string containing sessionId (long), nodeId (String), label (String),
     *            actionName (String), exitCode (int), durationMs (long), and output (String)
     * @return {@link ActionResult} with success=true and message "Action logged", or
     *         success=false with the error message on failure
     */
    @Action("logAction")
    public ActionResult logAction(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String label = json.getString("label");
            String action = json.getString("actionName");
            int exitCode = json.getInt("exitCode");
            long durationMs = json.getLong("durationMs");
            String output = json.getString("output");

            this.tell(store -> store.logAction(sessionId, nodeId, label, action, exitCode, durationMs, output),
                      dbExecutor).get();

            return new ActionResult(true, "Action logged");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in logAction", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Starts a new workflow session.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "workflowName": "my-workflow",
     *   "overlayName": "production",
     *   "inventoryName": "hosts.yml",
     *   "nodeCount": 5
     * }
     * }</pre>
     *
     * @param arg JSON string containing workflowName (String), nodeCount (int),
     *            and optional overlayName (String) and inventoryName (String)
     * @return {@link ActionResult} with success=true and the session ID as the message,
     *         or success=false with the error message on failure
     */
    @Action("startSession")
    public ActionResult startSession(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            String workflowName = json.getString("workflowName");
            String overlayName = json.optString("overlayName", null);
            String inventoryName = json.optString("inventoryName", null);
            int nodeCount = json.getInt("nodeCount");

            long sessionId = this.ask(store ->
                store.startSession(workflowName, overlayName, inventoryName, nodeCount),
                dbExecutor).get();

            return new ActionResult(true, String.valueOf(sessionId));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in startSession", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Ends a workflow session with a terminal status.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "status": "COMPLETED"
     * }
     * }</pre>
     *
     * @param arg JSON string containing sessionId (long) and status (String matching {@link SessionStatus})
     * @return {@link ActionResult} with success=true and message "Session ended", or
     *         success=false with the error message on failure
     */
    @Action("endSession")
    public ActionResult endSession(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String statusStr = json.getString("status");

            SessionStatus status = SessionStatus.valueOf(statusStr);

            this.tell(store -> store.endSession(sessionId, status), dbExecutor).get();

            return new ActionResult(true, "Session ended");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in endSession", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Marks a node as succeeded in the current session.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "nodeId": "node-01"
     * }
     * }</pre>
     *
     * @param arg JSON string containing sessionId (long) and nodeId (String)
     * @return {@link ActionResult} with success=true and message "Node marked as success", or
     *         success=false with the error message on failure
     */
    @Action("markNodeSuccess")
    public ActionResult markNodeSuccess(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");

            this.tell(store -> store.markNodeSuccess(sessionId, nodeId), dbExecutor).get();

            return new ActionResult(true, "Node marked as success");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in markNodeSuccess", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Marks a node as failed in the current session.
     *
     * <p>Expected JSON format:</p>
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "nodeId": "node-01",
     *   "reason": "Connection timeout"
     * }
     * }</pre>
     *
     * @param arg JSON string containing sessionId (long), nodeId (String), and reason (String)
     * @return {@link ActionResult} with success=true and message "Node marked as failed", or
     *         success=false with the error message on failure
     */
    @Action("markNodeFailed")
    public ActionResult markNodeFailed(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String reason = json.getString("reason");

            this.tell(store -> store.markNodeFailed(sessionId, nodeId, reason), dbExecutor).get();

            return new ActionResult(true, "Node marked as failed");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in markNodeFailed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Gets the dedicated executor service for DB writes.
     *
     * @return the DB executor service
     */
    public ExecutorService getDbExecutor() {
        return dbExecutor;
    }
}
