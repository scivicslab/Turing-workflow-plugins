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
 * Records and retrieves workflow execution logs in an H2 database.
 *
 * <ul>
 * <li>{@code startSession} starts a new workflow session and returns the session ID as a string in the result
 * message.</li>
 * <li>{@code endSession} ends a workflow session with a terminal status.</li>
 * <li>{@code log} records a message with a specific {@link LogLevel}.</li>
 * <li>{@code logAction} records an action's result, including exit code, duration, and output.</li>
 * <li>{@code markNodeSuccess} marks a specific node as having succeeded.</li>
 * <li>{@code markNodeFailed} marks a specific node as having failed and records a reason.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once, as actions are executed asynchronously via a dedicated executor
 * service.</p>
 *
 * <p>Example workflow step:
 * <pre>{@code
 * actor: logStore
 * method: startSession
 * arguments:
 *   workflowName: "deploy-app"
 *   nodeCount: 3
 * }</pre>
 * </p>
 *
 * <p>If a database error occurs or an invalid argument is provided, the action returns a failure result with the error
 * message.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class LogStoreActor extends IIActorRef<DistributedLogStore> {

    private static final Logger logger = Logger.getLogger(LogStoreActor.class.getName());

    /** The executor service used to serialize database write operations. */
    private final ExecutorService dbExecutor;

    /**
     * Initializes a new {@code LogStoreActor} with a dedicated executor for asynchronous database writes.
     *
     * @param actorName the name of this actor
     * @param logStore the {@link DistributedLogStore} implementation
     * @param system the actor system
     * @param dbExecutor the dedicated executor service for DB writes
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
     * Arguments for a single log entry.
     *
     * @param sessionId the unique identifier of the workflow session
     * @param nodeId the identifier of the node generating the log
     * @param level the severity level of the log (e.g., DEBUG, INFO, WARN, ERROR)
     * @param message the text content of the log entry
     */
    public record LogArgs(@NotNull Long sessionId, @NotNull String nodeId, @NotNull String level, @NotNull String message) {}

    /**
     * Arguments for recording an action result.
     *
     * @param sessionId the session ID to associate with the result
     * @param nodeId the identifier of the node where the action occurred
     * @param label the label describing the step or context of the action
     * @param actionName the name of the action that was executed
     * @param exitCode the exit code returned by the action
     * @param durationMs the execution time in milliseconds
     * @param output the text output or message produced by the action
     */
    public record LogActionArgs(@NotNull Long sessionId, @NotNull String nodeId, @NotNull String label, @NotNull String actionName, @NotNull Integer exitCode, @NotNull Long durationMs, @NotNull String output) {}

    /**
     * Arguments for starting a new workflow session.
     *
     * @param workflowName the name of the workflow
     * @param overlayName the name of the overlay, if any
     * @param inventoryName the name of the inventory, if any
     * @param nodeCount the number of nodes in the workflow
     */
    public record StartSessionArgs(@NotNull String workflowName, String overlayName, String inventoryName, @NotNull Integer nodeCount) {}

    /**
     * Arguments for ending a workflow session.
     *
     * @param sessionId the unique identifier of the session to end
     * @param status the terminal status of the session (e.g., "SUCCESS", "FAILED")
     */
    public record EndSessionArgs(@NotNull Long sessionId, @NotNull String status) {}

    /**
     * Records the identifiers for a successful node within a specific workflow session.
     *
     * @param sessionId the unique identifier of the session
     * @param nodeId the identifier of the node that succeeded
     */
    public record MarkNodeSuccessArgs(@NotNull Long sessionId, @NotNull String nodeId) {}

    /**
     * Arguments for marking a node as failed in a session.
     *
     * @param sessionId the session ID to associate the failure with
     * @param nodeId the identifier of the node that failed
     * @param reason the text describing why the node failed
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
     * Records an action's execution metadata, such as its exit code and duration, into the database.
     *
     * Returns an action result that is a success with the message "Action logged" if the metadata is recorded, or a
     * failure with an error message if the write fails.
     *
     * <pre>{@code
     * actor: logStore
     * method: logAction
     * arguments:
     *   sessionId: 1
     *   nodeId: "node-01"
     *   label: "deploy"
     *   actionName: "runScript"
     *   exitCode: 0
     *   durationMs: 1234
     *   output: "Script completed successfully"
     * }</pre>
     *
     * @param args a record containing the sessionId (long), nodeId (String), label (String), actionName (String),
     * exitCode (int), durationMs (long), and output (String) to be recorded
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
     * Creates a new workflow session and returns its unique identifier.
     *
     * <p>The result is an action result that succeeds with the session ID as its message, or fails with an error
     * message if the session could not be initialized.</p>
     *
     * <pre>{@code
     * actor: logStore
     * method: startSession
     * arguments:
     *   workflowName: "deploy-app"
     *   nodeCount: 3
     *   overlayName: "prod"
     * }</pre>
     *
     * @param args a record containing the workflowName (String), the nodeCount (int), and optional overlayName
     * (String) and inventoryName (String)
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
     * Returns an action result that is a success with the message "Session ended", or a failure with an error message
     * if the session could not be ended.
     *
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "status": "COMPLETED"
     * }
     * }</pre>
     *
     * @param args a record containing the sessionId (long) and the status (String matching {@link SessionStatus})
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
     * Records that a specific node in a session completed successfully.
     *
     * Returns an action result that is a success with the message "Node marked as success", or a failure with an error
     * message if the recording fails.
     *
     * @param args a record containing the sessionId (long) and the nodeId (String) of the successful node.
     * @return an action result indicating whether the node was successfully marked as a success in the database.
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
     * Records a node failure in a specific session.
     *
     * Returns an action result that is a success with the message "Node marked as failed" if the failure was recorded,
     * or a failure with an error message if the recording failed.
     *
     * <pre>{@code
     * {
     *   "sessionId": 1,
     *   "nodeId": "node-01",
     *   "reason": "Connection timeout"
     * }
     * }</pre>
     *
     * @param args a record containing the sessionId (long), nodeId (String), and the reason (String) for the failure
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
     * Returns the executor service used to serialize database writes.
     *
     * @return the executor service used for database operations
     */
    public ExecutorService getDbExecutor() {
        return dbExecutor;
    }
}
