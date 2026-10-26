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
 * Holds the data for a single log event recorded during a workflow session, including the timestamp, originating node,
 * severity, and optional action metadata.
 *
 * <p>The entry contains:
 * <ul>
 * <li>the unique database ID</li>
 * <li>the session ID</li>
 * <li>the timestamp of the event</li>
 * <li>the identifier of the node that generated the entry</li>
 * <li>the workflow label or step (may be {@code null})</li>
 * <li>the action or method name (may be {@code null})</li>
 * <li>the severity level</li>
 * <li>the log message text</li>
 * <li>the process exit code (may be {@code null})</li>
 * <li>the execution duration in milliseconds (may be {@code null})</li>
 * </ul>
 * </p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class LogEntry {
    /** Formats timestamps into ISO 8601 strings with a timezone offset. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** The system default timezone used to format timestamps into ISO 8601 strings. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The unique database ID of this log entry. */
    private final long id;
    /** The ID of the workflow execution session to which this entry belongs. */
    private final long sessionId;
    /** The timestamp when this entry was recorded. */
    private final LocalDateTime timestamp;
    /** The identifier of the node that generated this entry. */
    private final String nodeId;
    /**
     * The workflow label (step or state) at the time of logging, or {@code null} if no label is associated with the
     * entry.
     */
    private final String label;
    /** The name of the action or method associated with this log entry, or {@code null} if none. */
    private final String actionName;
    /** The severity level of this log entry. */
    private final LogLevel level;
    /** The log message text. */
    private final String message;
    /** The process exit code, or {@code null} if not applicable. */
    private final Integer exitCode;
    /** The execution duration in milliseconds, or {@code null} if not applicable. */
    private final Long durationMs;

    /**
     * Creates a new log entry containing the recorded metadata for a specific workflow event.
     *
     * @param id the unique database ID
     * @param sessionId the session ID this entry belongs to
     * @param timestamp the timestamp when this entry was recorded
     * @param nodeId the identifier of the originating node
     * @param label the workflow label/step, or {@code null}
     * @param actionName the action or method name, or {@code null}
     * @param level the log severity level
     * @param message the log message text
     * @param exitCode the process exit code, or {@code null} if not applicable
     * @param durationMs the execution duration in milliseconds, or {@code null} if not applicable
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

    /** Returns the unique database ID of this log entry. */
    public long getId() { return id; }
    /** Returns the session ID this entry belongs to. */
    public long getSessionId() { return sessionId; }
    /**
     * Returns the timestamp when this log entry was recorded.
     *
     * @return the timestamp of the recording
     */
    public LocalDateTime getTimestamp() { return timestamp; }
    /** Returns the identifier of the node that generated this log entry. */
    public String getNodeId() { return nodeId; }
    /**
     * Returns the workflow label or step name associated with this log entry, or {@code null} if no label was
     * provided.
     */
    public String getLabel() { return label; }
    /**
     * Returns the name of the action or method associated with this log entry, or {@code null} if none is specified.
     */
    public String getActionName() { return actionName; }
    /** Returns the severity level of this log entry. */
    public LogLevel getLevel() { return level; }
    /** Returns the log message text. */
    public String getMessage() { return message; }
    /**
     * Returns the process exit code of the action that generated this log entry.
     *
     * @return the process exit code, or {@code null} if the entry is not associated with a process execution
     */
    public Integer getExitCode() { return exitCode; }
    /** Returns the execution duration in milliseconds, or {@code null} if the entry does not record a duration. */
    public Long getDurationMs() { return durationMs; }

    /**
     * Returns a string that represents the log entry as a single line of text.
     *
     * The string begins with the timestamp in ISO 8601 format, followed by the log level, the optional workflow label
     * and action name, the message, and finally the non-zero exit code and duration in milliseconds if they are
     * present.
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
