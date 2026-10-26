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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Represents the statistical and contextual snapshot of a workflow execution session, containing node success and
 * failure counts, log entry totals, timing, and execution environment details.
 *
 * <p>A caller uses this to inspect the outcome of a specific session, such as checking the number of failed nodes or
 * the total duration of the run.</p>
 *
 * <p>One instance is an immutable snapshot of a single session and cannot be modified.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class SessionSummary {
    /** ISO 8601 formatter with timezone offset used to format session timestamps. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** The system default timezone used for formatting timestamps into ISO 8601 strings. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The unique session identifier. */
    private final long sessionId;
    /** The name of the workflow that was executed. */
    private final String workflowName;
    /** The name of the overlay applied to the workflow, or {@code null} if none was used. */
    private final String overlayName;
    /** The name of the inventory file used for the workflow, or {@code null} if none was specified. */
    private final String inventoryName;
    /** The timestamp when the session started. */
    private final LocalDateTime startedAt;
    /** The timestamp when the session ended, or {@code null} if the session is still running. */
    private final LocalDateTime endedAt;
    /** The total number of nodes that participated in this session. */
    private final int nodeCount;
    /** The current lifecycle status of this session. */
    private final SessionStatus status;
    /** The number of nodes that completed successfully. */
    private final int successCount;
    /** The number of nodes that failed during the session. */
    private final int failedCount;
    /** The list of identifiers for nodes that failed during the session, which may be empty. */
    private final List<String> failedNodes;
    /** The total number of log entries recorded in this session. */
    private final int totalLogEntries;
    /** The number of log entries in this session with a level of {@code ERROR}. */
    private final int errorCount;

    // Execution context for reproducibility
    /** The current working directory at execution time, or {@code null}. */
    private final String cwd;
    /** The git commit hash of the workflow directory, or {@code null}. */
    private final String gitCommit;
    /** The git branch name at execution time, or {@code null} */
    private final String gitBranch;
    /** The command line used to invoke the workflow, or {@code null}. */
    private final String commandLine;
    /** The version of the plugin used for this execution, or {@code null} if not recorded. */
    private final String pluginVersion;
    /** The git commit hash of the plugin, or {@code null} if not recorded. */
    private final String pluginCommit;

    /**
     * Creates a session summary that excludes execution context such as the working directory, git information, and
     * plugin versions.
     *
     * @param sessionId the unique session identifier
     * @param workflowName the name of the executed workflow
     * @param overlayName the overlay name, or {@code null}
     * @param inventoryName the inventory file name, or {@code null}
     * @param startedAt the session start timestamp
     * @param endedAt the session end timestamp, or {@code null} if still running
     * @param nodeCount the total number of participating nodes
     * @param status the session lifecycle status
     * @param successCount the number of successful nodes
     * @param failedCount the number of failed nodes
     * @param failedNodes the list of failed node identifiers, or an empty list
     * @param totalLogEntries the total number of log entries recorded in this session
     * @param errorCount the number of ERROR-level log entries in this session
     */
    public SessionSummary(long sessionId, String workflowName, String overlayName,
                          String inventoryName, LocalDateTime startedAt,
                          LocalDateTime endedAt, int nodeCount, SessionStatus status,
                          int successCount, int failedCount, List<String> failedNodes,
                          int totalLogEntries, int errorCount) {
        this(sessionId, workflowName, overlayName, inventoryName, startedAt, endedAt,
             nodeCount, status, successCount, failedCount, failedNodes, totalLogEntries, errorCount,
             null, null, null, null, null, null);
    }

    /**
     * Initializes a session summary that includes both execution metrics and the environment context used for
     * reproducibility.
     *
     * @param sessionId the unique session identifier
     * @param workflowName the name of the executed workflow
     * @param overlayName the overlay name, or {@code null}
     * @param inventoryName the inventory file name, or {@code null}
     * @param startedAt the session start timestamp
     * @param endedAt the session end timestamp, or {@code null} if still running
     * @param nodeCount the total number of participating nodes
     * @param status the session lifecycle status
     * @param successCount the number of nodes that completed successfully
     * @param failedCount the number of nodes that failed
     * @param failedNodes the list of failed node identifiers, or an empty list
     * @param totalLogEntries the total number of log entries recorded in this session
     * @param errorCount the number of ERROR-level log entries in this session
     * @param cwd the current working directory at execution time, or {@code null}
     * @param gitCommit the git commit hash, or {@code null}
     * @param gitBranch the git branch name at execution time, or {@code null}
     * @param commandLine the command line used to invoke the workflow, or {@code null}
     * @param pluginVersion the plugin version used for this execution, or {@code null}
     * @param pluginCommit the plugin git commit hash, or {@code null}
     */
    public SessionSummary(long sessionId, String workflowName, String overlayName,
                          String inventoryName, LocalDateTime startedAt,
                          LocalDateTime endedAt, int nodeCount, SessionStatus status,
                          int successCount, int failedCount, List<String> failedNodes,
                          int totalLogEntries, int errorCount,
                          String cwd, String gitCommit, String gitBranch,
                          String commandLine, String pluginVersion, String pluginCommit) {
        this.sessionId = sessionId;
        this.workflowName = workflowName;
        this.overlayName = overlayName;
        this.inventoryName = inventoryName;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.nodeCount = nodeCount;
        this.status = status;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.failedNodes = failedNodes;
        this.totalLogEntries = totalLogEntries;
        this.errorCount = errorCount;
        this.cwd = cwd;
        this.gitCommit = gitCommit;
        this.gitBranch = gitBranch;
        this.commandLine = commandLine;
        this.pluginVersion = pluginVersion;
        this.pluginCommit = pluginCommit;
    }

    /**
     * Returns the unique session identifier.
     *
     * @return the unique session identifier
     */
    public long getSessionId() { return sessionId; }
    /** Returns the name of the workflow that was executed during this session. */
    public String getWorkflowName() { return workflowName; }
    /** Returns the name of the overlay applied to the workflow, or {@code null} if no overlay was used. */
    public String getOverlayName() { return overlayName; }
    /** Returns the name of the inventory file used for the workflow, or {@code null} if none was specified. */
    public String getInventoryName() { return inventoryName; }
    /**
     * Returns the timestamp when the session started.
     *
     * @return the session start timestamp
     */
    public LocalDateTime getStartedAt() { return startedAt; }
    /**
     * Returns the timestamp when the workflow session ended, or {@code null} if the session is still running.
     *
     * @return the session end timestamp or {@code null} if the session has not ended
     */
    public LocalDateTime getEndedAt() { return endedAt; }
    /**
     * Returns the total number of nodes that participated in the workflow session.
     *
     * @return the number of participating nodes
     */
    public int getNodeCount() { return nodeCount; }
    /**
     * Returns the current lifecycle status of the workflow session.
     *
     * @return the {@link SessionStatus} representing whether the session is {@code RUNNING}, {@code SUCCESS}, or
     * {@code FAILED}
     */
    public SessionStatus getStatus() { return status; }
    /**
     * Returns the number of nodes that completed successfully in this session.
     *
     * @return the count of nodes that reached a success status
     */
    public int getSuccessCount() { return successCount; }
    /**
     * Returns the number of nodes that failed during the session.
     *
     * @return the number of nodes that failed
     */
    public int getFailedCount() { return failedCount; }
    /**
     * Returns a list of the identifiers of the nodes that failed during the session.
     *
     * @return a list of failed node identifiers, which may be empty if no nodes failed
     */
    public List<String> getFailedNodes() { return failedNodes; }
    /** Returns the total number of log entries recorded in this session. */
    public int getTotalLogEntries() { return totalLogEntries; }
    /** Returns the number of log entries in this session that were recorded with the {@code ERROR} level. */
    public int getErrorCount() { return errorCount; }

    // Execution context getters
    /**
     * Returns the current working directory at the time the workflow was executed, or {@code null} if it was not
     * recorded.
     */
    public String getCwd() { return cwd; }
    /**
     * Returns the git commit hash of the workflow directory at execution time, or {@code null} if no commit hash was
     * recorded.
     */
    public String getGitCommit() { return gitCommit; }
    /**
     * Returns the name of the Git branch used during the workflow execution, or {@code null} if no branch information
     * was recorded.
     */
    public String getGitBranch() { return gitBranch; }
    /** Returns the command line used to invoke the workflow, or {@code null} if no command line was recorded. */
    public String getCommandLine() { return commandLine; }
    /** Returns the version of the plugin used during the workflow execution, or {@code null} if not recorded. */
    public String getPluginVersion() { return pluginVersion; }
    /**
     * Returns the git commit hash of the plugin used during the session, or {@code null} if it was not recorded.
     */
    public String getPluginCommit() { return pluginCommit; }

    /**
     * Returns a {@link Duration} representing the time elapsed between the session start and end.
     *
     * Returns {@link Duration#ZERO} if the session start time or end time is {@code null}.
     *
     * @return the duration between the start and end timestamps
     */
    public Duration getDuration() {
        if (startedAt == null || endedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, endedAt);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Session #").append(sessionId).append(": ").append(workflowName).append("\n");
        if (overlayName != null) {
            sb.append("  Overlay:  ").append(overlayName).append("\n");
        }
        if (inventoryName != null) {
            sb.append("  Inventory: ").append(inventoryName).append("\n");
        }
        sb.append("  Started:  ").append(formatTimestamp(startedAt)).append("\n");
        sb.append("  Ended:    ").append(formatTimestamp(endedAt)).append("\n");

        Duration d = getDuration();
        if (!d.isZero()) {
            long minutes = d.toMinutes();
            long seconds = d.toSecondsPart();
            sb.append("  Duration: ").append(minutes).append("m ").append(seconds).append("s\n");
        }

        sb.append("  Nodes:    ").append(nodeCount).append("\n");
        sb.append("  Status:   ").append(status).append("\n");
        sb.append("\n");
        sb.append("  Results:\n");
        sb.append("    SUCCESS: ").append(successCount).append(" nodes\n");
        sb.append("    FAILED:  ").append(failedCount).append(" nodes");
        if (failedNodes != null && !failedNodes.isEmpty()) {
            sb.append(" (").append(String.join(", ", failedNodes)).append(")");
        }
        sb.append("\n");
        sb.append("\n");
        sb.append("  Log lines: ").append(totalLogEntries).append(" (").append(errorCount).append(" errors)");
        return sb.toString();
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "N/A";
        }
        return timestamp.atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
    }
}
