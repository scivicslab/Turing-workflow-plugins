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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single log entry recorded during workflow execution.
 *
 * <p>Each entry captures the timestamp, originating node, log level, message,
 * and optional action metadata such as exit code and duration. Entries are
 * immutable once created.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class LogEntry {
    /** ISO 8601 formatter with timezone offset. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** System default timezone used for timestamp formatting. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The unique database ID of this log entry. */
    private final long id;
    /** The session ID this entry belongs to. */
    private final long sessionId;
    /** The timestamp when this entry was recorded. */
    private final LocalDateTime timestamp;
    /** The identifier of the node that generated this entry. */
    private final String nodeId;
    /** The workflow label (step/state) at the time of logging, may be {@code null}. */
    private final String label;
    /** The action or method name associated with this entry, may be {@code null}. */
    private final String actionName;
    /** The severity level of this log entry. */
    private final LogLevel level;
    /** The log message text. */
    private final String message;
    /** The process exit code, may be {@code null} if not applicable. */
    private final Integer exitCode;
    /** The execution duration in milliseconds, may be {@code null} if not applicable. */
    private final Long durationMs;

    /**
     * Constructs a new log entry with all fields.
     *
     * @param id         the unique database ID
     * @param sessionId  the session ID this entry belongs to
     * @param timestamp  the timestamp when this entry was recorded
     * @param nodeId     the identifier of the originating node
     * @param label      the workflow label/step, may be {@code null}
     * @param actionName the action or method name, may be {@code null}
     * @param level      the log severity level
     * @param message    the log message text
     * @param exitCode   the process exit code, may be {@code null}
     * @param durationMs the execution duration in milliseconds, may be {@code null}
     */
    public LogEntry(long id, long sessionId, LocalDateTime timestamp, String nodeId,
                    String label, String actionName, LogLevel level, String message,
                    Integer exitCode, Long durationMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.nodeId = nodeId;
        this.label = label;
        this.actionName = actionName;
        this.level = level;
        this.message = message;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
    }

    /** @return the unique database ID of this log entry */
    public long getId() { return id; }
    /** @return the session ID this entry belongs to */
    public long getSessionId() { return sessionId; }
    /** @return the timestamp when this entry was recorded */
    public LocalDateTime getTimestamp() { return timestamp; }
    /** @return the identifier of the node that generated this entry */
    public String getNodeId() { return nodeId; }
    /** @return the workflow label/step, or {@code null} */
    public String getLabel() { return label; }
    /** @return the action or method name, or {@code null} */
    public String getActionName() { return actionName; }
    /** @return the severity level of this log entry */
    public LogLevel getLevel() { return level; }
    /** @return the log message text */
    public String getMessage() { return message; }
    /** @return the process exit code, or {@code null} if not applicable */
    public Integer getExitCode() { return exitCode; }
    /** @return the execution duration in milliseconds, or {@code null} if not applicable */
    public Long getDurationMs() { return durationMs; }

    /**
     * Returns a human-readable string representation of this log entry.
     *
     * <p>The format includes the ISO 8601 timestamp, level, optional label/action,
     * message, non-zero exit code, and duration if present.</p>
     *
     * @return formatted log entry string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(formatTimestamp(timestamp)).append("] ");
        sb.append(String.format("%-5s ", level));
        if (label != null) {
            sb.append("[").append(label);
            if (actionName != null) {
                sb.append(" -> ").append(actionName);
            }
            sb.append("] ");
        }
        sb.append(message);
        if (exitCode != null && exitCode != 0) {
            sb.append(" (exit=").append(exitCode).append(")");
        }
        if (durationMs != null) {
            sb.append(" [").append(durationMs).append("ms]");
        }
        return sb.toString();
    }

    private String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return "N/A";
        }
        return ts.atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
    }
}
