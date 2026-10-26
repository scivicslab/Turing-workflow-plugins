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

import java.sql.Connection;
import java.util.List;

/**
 * Records and queries workflow execution logs across multiple nodes into a persistent database.
 *
 * <ul>
 * <li>{@code startSession} creates a new session and returns a unique session ID.</li>
 * <li>{@code log} records a log entry with a specific level and message.</li>
 * <li>{@code logAction} records the result of an action, including its exit code, duration, and output.</li>
 * <li>{@code markNodeSuccess} and {@code markNodeFailed} record the final status of a specific node.</li>
 * <li>{@code endSession} closes a session with a final status.</li>
 * <li>{@code getLogsByNode} and {@code getLogsByLevel} return lists of log entries filtered by node or severity.</li>
 * <li>{@code getSummary} returns a summary of a session's execution.</li>
 * <li>{@code listSessions} returns a list of recent session summaries.</li>
 * <li>{@code getLatestSessionId} returns the ID of the most recent session.</li>
 * <li>{@code getConnection} returns a JDBC connection for direct SQL queries.</li>
 * <li>{@code flushWrites} ensures all previously queued log entries are committed to the database.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once. A single global instance is typically set at workflow startup
 * and cleared after execution.</p>
 *
 * <p>The user must call {@code close} to shut down the store and release resources. If {@code getConnection} is used,
 * the caller must not close the returned connection; it is owned by the store.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public interface DistributedLogStore extends AutoCloseable {

    // ========================================================================
    // Singleton management
    // ========================================================================

    /**
     * Holds the single global instance of {@link DistributedLogStore} used by the workflow.
     *
     * <p>The instance is set at workflow startup and cleared after execution. It is used by components like {@code
     * WorkflowReporter} to access the shared log storage via a singleton. One instance serves all components and
     * threads during a single workflow execution.</p>
     */
    class InstanceHolder {
        /** The global singleton instance, set at workflow startup. */
        private static DistributedLogStore instance;
    }

    /**
     * Assigns the global singleton instance of a {@link DistributedLogStore} for use by the system.
     *
     * @param store the {@link DistributedLogStore} instance to be used for all subsequent log storage and retrieval
     * operations
     */
    static void setInstance(DistributedLogStore store) {
        InstanceHolder.instance = store;
    }

    /**
     * Returns the global {@code DistributedLogStore} instance used by the workflow.
     *
     * @return the global {@code DistributedLogStore} instance, or {@code null} if no instance has been set via {@link
     * #setInstance(DistributedLogStore)}
     */
    static DistributedLogStore getInstance() {
        return InstanceHolder.instance;
    }

    // ========================================================================
    // Connection access
    // ========================================================================

    /**
     * Returns a JDBC connection that allows performing SQL queries against the log database.
     *
     * <p>The caller must not close this connection; the store owns it and will close it when the execution ends.
     *
     * @return the JDBC connection used for read-only operations
     */
    Connection getConnection();

    /**
     * Creates a new workflow execution session in the database.
     *
     * @param workflowName the name of the workflow being executed
     * @param nodeCount the number of nodes participating in this session
     * @return a positive session ID used for subsequent log entries
     */
    long startSession(String workflowName, int nodeCount);

    /**
     * Initiates a new workflow execution session and returns a unique identifier for it.
     *
     * @param workflowName the name of the workflow being executed
     * @param overlayName the name of the overlay being used, or {@code null} if none
     * @param inventoryName the name of the inventory file being used, or {@code null} if none
     * @param nodeCount the number of nodes participating in this session
     * @return a positive session ID used for subsequent log entries
     */
    long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount);

    /**
     * Starts a new workflow execution session with full execution context.
     *
     * Returns a session ID for subsequent log entries.
     *
     * @param workflowName the name of the workflow being executed
     * @param overlayName the name of the overlay being used, or null
     * @param inventoryName the name of the inventory file being used, or null
     * @param nodeCount the number of nodes participating in this session
     * @param cwd the current working directory
     * @param gitCommit the git commit hash of the workflow directory, or null
     * @param gitBranch the git branch name, or null
     * @param commandLine the command line used to invoke the workflow
     * @param pluginVersion the plugin version
     * @param pluginCommit the plugin git commit hash, or null
     */
    default long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount,
                              String cwd, String gitCommit, String gitBranch,
                              String commandLine, String pluginVersion, String pluginCommit) {
        // Default implementation for backward compatibility
        return startSession(workflowName, overlayName, inventoryName, nodeCount);
    }

    /**
     * Records a log entry for a specific node within a session.
     *
     * @param sessionId the session ID returned by {@link #startSession(String, int)} or {@link #startSession(String,
     * String, String, int)}
     *
     * @param nodeId the identifier of the node generating this log
     * @param level the {@link LogLevel} of the entry
     * @param message the text of the log message
     */
    void log(long sessionId, String nodeId, LogLevel level, String message);

    /**
     * Records a log entry that includes the current workflow transition label.
     *
     * @param sessionId the session ID from {@link #startSession(String, int)} or {@link #startSession(String, String,
     * String, int)}
     *
     * @param nodeId the identifier of the node generating the log
     * @param label the current label in the workflow transition
     * @param level the severity of the log
     * @param message the text of the log message
     */
    void log(long sessionId, String nodeId, String label, LogLevel level, String message);

    /**
     * Records the outcome of an action performed on a node.
     *
     * @param sessionId the session ID for the current workflow execution
     * @param nodeId the identifier of the node performing the action
     * @param label the current label in the workflow
     * @param actionName the name of the action or method executed
     * @param exitCode the command exit code, where 0 indicates success
     * @param durationMs the execution duration in milliseconds
     * @param output the command output or result message
     */
    void logAction(long sessionId, String nodeId, String label,
                   String actionName, int exitCode, long durationMs, String output);

    /**
     * Records the successful completion of a node within a session.
     *
     * @param sessionId the session ID for the execution
     * @param nodeId the identifier of the node that succeeded
     */
    void markNodeSuccess(long sessionId, String nodeId);

    /**
     * Records a failure for a specific node in a session.
     *
     * @param sessionId the session ID for the execution
     * @param nodeId the identifier of the node that failed
     * @param reason a text describing why the node failed
     */
    void markNodeFailed(long sessionId, String nodeId, String reason);

    /**
     * Ends a workflow execution session with a final status.
     *
     * @param sessionId the ID of the session to close
     * @param status the final {@code SessionStatus} to record for the session
     */
    void endSession(long sessionId, SessionStatus status);

    /**
     * Returns a list of log entries recorded by a specific node during a session.
     *
     * @param sessionId the unique identifier of the session
     * @param nodeId the identifier of the node whose logs are being retrieved
     * @return a list of log entries belonging to the specified node and session
     */
    List<LogEntry> getLogsByNode(long sessionId, String nodeId);

    /**
     * Returns a list of log entries from the specified session that have a level equal to or greater than the
     * specified minimum level.
     *
     * @param sessionId the ID of the session to search
     * @param minLevel the threshold level; entries with this level or higher are included
     * @return a list of log entries matching the criteria
     */
    List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel);

    /**
     * Waits until all previously submitted log entries and action results have been committed to the database,
     * ensuring they are visible to readers using separate connections.
     *
     * <p>For stores that use asynchronous batch writing, this provides a synchronization point so that a subsequent
     * read operation sees all data written before this call. For synchronous stores like {@link H2LogStore}, this
     * method has no effect.</p>
     */
    default void flushWrites() {
    }

    /**
     * Returns a list of log entries for the given session that have a level of {@code ERROR}.
     *
     * @param sessionId the session ID to query
     * @return a list of log entries with {@code ERROR} level, or an empty list if no such entries exist
     */
    default List<LogEntry> getErrors(long sessionId) {
        return getLogsByLevel(sessionId, LogLevel.ERROR);
    }

    /**
     * Returns a {@link SessionSummary} containing the execution statistics and status for the specified session.
     *
     * @param sessionId the unique identifier of the session to summarize
     */
    SessionSummary getSummary(long sessionId);

    /**
     * Returns the ID of the most recently started session.
     *
     * @return the most recent session ID, or -1 if no sessions exist in the database
     */
    long getLatestSessionId();

    /**
     * Returns a list of session summaries containing the most recent workflow executions.
     *
     * @param limit the maximum number of sessions to return
     */
    List<SessionSummary> listSessions(int limit);
}
