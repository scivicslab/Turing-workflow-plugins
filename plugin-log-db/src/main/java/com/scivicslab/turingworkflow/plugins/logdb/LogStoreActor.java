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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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
    /**
     * One log line to store.
     *
     * @param sessionId 
     * @param nodeId 
     * @param level 
     * @param message 
     */
    public record LogArgs(@NotNull Long sessionId, @NotNull String nodeId, @NotNull String level, @NotNull String message) {}

    /**
     * One action outcome to store.
     *
     * @param sessionId 
     * @param nodeId 
     * @param label 
     * @param actionName 
     * @param exitCode 
     * @param durationMs 
     * @param output 
     */
    public record LogActionArgs(@NotNull Long sessionId, @NotNull String nodeId, @NotNull String label, @NotNull String actionName, @NotNull Integer exitCode, @NotNull Long durationMs, @NotNull String output) {}

    /**
     * What identifies a run that is starting.
     *
     * @param workflowName 
     * @param overlayName 
     * @param inventoryName 
     * @param nodeCount 
     */
    public record StartSessionArgs(@NotNull String workflowName, String overlayName, String inventoryName, @NotNull Integer nodeCount) {}

    /**
     * Which run finished, and how.
     *
     * @param sessionId 
     * @param status 
     */
    public record EndSessionArgs(@NotNull Long sessionId, @NotNull String status) {}

    /**
     * Which node of which run succeeded.
     *
     * @param sessionId 
     * @param nodeId 
     */
    public record MarkNodeSuccessArgs(@NotNull Long sessionId, @NotNull String nodeId) {}

    /**
     * Which node of which run failed, and why.
     *
     * @param sessionId 
     * @param nodeId 
     * @param reason 
     */
    public record MarkNodeFailedArgs(@NotNull Long sessionId, @NotNull String nodeId, @NotNull String reason) {}

    @Action(value = "log", argsType = LogArgs.class)
    public ActionResult log(LogArgs args) {
        try {
            long sessionId = args.sessionId();
            String nodeId = args.nodeId();
            String levelStr = args.level();
            String message = args.message();

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
    @Action(value = "logAction", argsType = LogActionArgs.class)
    public ActionResult logAction(LogActionArgs args) {
        try {
            long sessionId = args.sessionId();
            String nodeId = args.nodeId();
            String label = args.label();
            String action = args.actionName();
            int exitCode = args.exitCode();
            long durationMs = args.durationMs();
            String output = args.output();

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
    @Action(value = "startSession", argsType = StartSessionArgs.class)
    public ActionResult startSession(StartSessionArgs args) {
        try {
            String workflowName = args.workflowName();
            String overlayName = args.overlayName();
            String inventoryName = args.inventoryName();
            int nodeCount = args.nodeCount();

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
    @Action(value = "endSession", argsType = EndSessionArgs.class)
    public ActionResult endSession(EndSessionArgs args) {
        try {
            long sessionId = args.sessionId();
            String statusStr = args.status();

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
    @Action(value = "markNodeSuccess", argsType = MarkNodeSuccessArgs.class)
    public ActionResult markNodeSuccess(MarkNodeSuccessArgs args) {
        try {
            long sessionId = args.sessionId();
            String nodeId = args.nodeId();

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
    @Action(value = "markNodeFailed", argsType = MarkNodeFailedArgs.class)
    public ActionResult markNodeFailed(MarkNodeFailedArgs args) {
        try {
            long sessionId = args.sessionId();
            String nodeId = args.nodeId();
            String reason = args.reason();

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
