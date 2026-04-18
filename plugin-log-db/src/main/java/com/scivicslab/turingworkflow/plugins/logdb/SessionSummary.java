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
 * Summary of a workflow execution session.
 *
 * <p>Captures key metrics about a completed or in-progress workflow session,
 * including node success/failure counts, log statistics, timing information,
 * and execution context for reproducibility.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class SessionSummary {
    /** ISO 8601 formatter with timezone offset. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** System default timezone used for timestamp formatting. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The unique session identifier. */
    private final long sessionId;
    /** The name of the workflow that was executed. */
    private final String workflowName;
    /** The overlay name applied to the workflow, may be {@code null}. */
    private final String overlayName;
    /** The inventory file name used for the workflow, may be {@code null}. */
    private final String inventoryName;
    /** The timestamp when the session started. */
    private final LocalDateTime startedAt;
    /** The timestamp when the session ended, may be {@code null} if still running. */
    private final LocalDateTime endedAt;
    /** The total number of nodes participating in this session. */
    private final int nodeCount;
    /** The current lifecycle status of this session. */
    private final SessionStatus status;
    /** The number of nodes that completed successfully. */
    private final int successCount;
    /** The number of nodes that failed. */
    private final int failedCount;
    /** The list of node identifiers that failed, may be empty. */
    private final List<String> failedNodes;
    /** The total number of log entries recorded in this session. */
    private final int totalLogEntries;
    /** The number of ERROR-level log entries in this session. */
    private final int errorCount;

    // Execution context for reproducibility
    /** The current working directory at execution time, may be {@code null}. */
    private final String cwd;
    /** The git commit hash of the workflow directory, may be {@code null}. */
    private final String gitCommit;
    /** The git branch name at execution time, may be {@code null}. */
    private final String gitBranch;
    /** The command line used to invoke the workflow, may be {@code null}. */
    private final String commandLine;
    /** The plugin version used for this execution, may be {@code null}. */
    private final String pluginVersion;
    /** The plugin git commit hash, may be {@code null}. */
    private final String pluginCommit;

    /**
     * Legacy constructor for backward compatibility (without execution context).
     *
     * @param sessionId      the unique session identifier
     * @param workflowName   the name of the executed workflow
     * @param overlayName    the overlay name, may be {@code null}
     * @param inventoryName  the inventory file name, may be {@code null}
     * @param startedAt      the session start timestamp
     * @param endedAt        the session end timestamp, may be {@code null}
     * @param nodeCount      the total number of participating nodes
     * @param status         the session lifecycle status
     * @param successCount   the number of successful nodes
     * @param failedCount    the number of failed nodes
     * @param failedNodes    the list of failed node identifiers
     * @param totalLogEntries the total number of log entries
     * @param errorCount     the number of ERROR-level log entries
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
     * Full constructor with execution context for reproducibility.
     *
     * @param sessionId      the unique session identifier
     * @param workflowName   the name of the executed workflow
     * @param overlayName    the overlay name, may be {@code null}
     * @param inventoryName  the inventory file name, may be {@code null}
     * @param startedAt      the session start timestamp
     * @param endedAt        the session end timestamp, may be {@code null}
     * @param nodeCount      the total number of participating nodes
     * @param status         the session lifecycle status
     * @param successCount   the number of successful nodes
     * @param failedCount    the number of failed nodes
     * @param failedNodes    the list of failed node identifiers
     * @param totalLogEntries the total number of log entries
     * @param errorCount     the number of ERROR-level log entries
     * @param cwd            the current working directory at execution time, may be {@code null}
     * @param gitCommit      the git commit hash, may be {@code null}
     * @param gitBranch      the git branch name, may be {@code null}
     * @param commandLine    the command line used to invoke the workflow, may be {@code null}
     * @param pluginVersion  the plugin version, may be {@code null}
     * @param pluginCommit   the plugin git commit hash, may be {@code null}
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

    /** @return the unique session identifier */
    public long getSessionId() { return sessionId; }
    /** @return the name of the executed workflow */
    public String getWorkflowName() { return workflowName; }
    /** @return the overlay name, or {@code null} */
    public String getOverlayName() { return overlayName; }
    /** @return the inventory file name, or {@code null} */
    public String getInventoryName() { return inventoryName; }
    /** @return the session start timestamp */
    public LocalDateTime getStartedAt() { return startedAt; }
    /** @return the session end timestamp, or {@code null} if still running */
    public LocalDateTime getEndedAt() { return endedAt; }
    /** @return the total number of participating nodes */
    public int getNodeCount() { return nodeCount; }
    /** @return the session lifecycle status */
    public SessionStatus getStatus() { return status; }
    /** @return the number of nodes that completed successfully */
    public int getSuccessCount() { return successCount; }
    /** @return the number of nodes that failed */
    public int getFailedCount() { return failedCount; }
    /** @return the list of failed node identifiers */
    public List<String> getFailedNodes() { return failedNodes; }
    /** @return the total number of log entries in this session */
    public int getTotalLogEntries() { return totalLogEntries; }
    /** @return the number of ERROR-level log entries */
    public int getErrorCount() { return errorCount; }

    // Execution context getters
    /** @return the current working directory at execution time, or {@code null} */
    public String getCwd() { return cwd; }
    /** @return the git commit hash, or {@code null} */
    public String getGitCommit() { return gitCommit; }
    /** @return the git branch name, or {@code null} */
    public String getGitBranch() { return gitBranch; }
    /** @return the command line used to invoke the workflow, or {@code null} */
    public String getCommandLine() { return commandLine; }
    /** @return the plugin version, or {@code null} */
    public String getPluginVersion() { return pluginVersion; }
    /** @return the plugin git commit hash, or {@code null} */
    public String getPluginCommit() { return pluginCommit; }

    /**
     * Computes the duration of the session.
     *
     * @return the duration between start and end, or {@link Duration#ZERO} if either timestamp is {@code null}
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
