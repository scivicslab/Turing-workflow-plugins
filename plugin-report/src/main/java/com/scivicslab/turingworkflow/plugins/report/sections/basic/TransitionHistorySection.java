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

package com.scivicslab.turingworkflow.plugins.report.sections.basic;

import com.scivicslab.turingworkflow.plugins.report.SectionBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates a text block of workflow state transitions by querying the log database.
 *
 * <p>The main operations are:
 * <ul>
 * <li>{@link #generate()} returns a formatted string containing a {@code [Transition History]} block with timestamps,
 * labels, success/failure indicators, and a summary of succeeded and failed transitions.</li>
 * <li>{@link #getTitle()} returns {@code null} because the title is embedded within the generated content.</li>
 * </ul>
 *
 * <p>It holds a JDBC {@link Connection} and a session ID that must be set before calling {@code generate}.
 *
 * <p>One instance may serve several workflows if the connection and session ID are updated accordingly.
 *
 * <p>If the connection is {@code null} or the session ID is negative, {@code generate} returns an empty string.
 *
 * <p>If {@code includeChildren} is {@code true} and the target actor is {@code "nodeGroup"}, it aggregates transitions
 * for all child nodes under their respective headers.
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class TransitionHistorySection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(TransitionHistorySection.class.getName());
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Connection connection;
    private long sessionId = -1;
    private String targetActorName;
    private boolean includeChildren = false;

    /**
     * Assigns the JDBC connection used to query the H2 log database for transition records.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID used to query logs for the transition history.
     *
     * @param sessionId the unique identifier of the workflow session
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Sets the name of the actor whose transitions are displayed in the report.
     *
     * @param targetActorName the name of the actor to query, such as "node-server1" or "nodeGroup"
     */
    public void setTargetActorName(String targetActorName) {
        this.targetActorName = targetActorName;
    }

    /**
     * Sets whether the report aggregates transitions from all child nodes when the target actor is {@code
     * "nodeGroup"}.
     *
     * @param includeChildren true to include child nodes in the output, false to only show the target actor.
     */
    public void setIncludeChildren(boolean includeChildren) {
        this.includeChildren = includeChildren;
    }

    /**
     * Returns a string containing a formatted history of state transitions found in the log database.
     *
     * The string contains a {@code [Transition History]} block for the target actor, where each entry shows a success
     * or failure indicator ({@code o} or {@code x}), a timestamp, the transition label, an optional note, and an error
     * message if the transition failed. A summary line with the total number of transitions and the counts of
     * succeeded and failed transitions is appended at the end.
     *
     * If {@code includeChildren} is {@code true} and the target actor is {@code "nodeGroup"}, the result aggregates
     * transitions from all child nodes under their respective headers.
     *
     * If the connection is not set, the session ID is negative, or no transition records are found for the target
     * actor, the method returns an empty string.
     *
     * @return the formatted transition history content, or an empty string if no transitions are found or if the
     * database cannot be queried.
     */
    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("TransitionHistorySection: connection or sessionId not set");
            return "";
        }

        if (targetActorName == null || targetActorName.isEmpty()) {
            targetActorName = "nodeGroup";  // Default target
        }

        try {
            if (includeChildren && "nodeGroup".equals(targetActorName)) {
                return buildAggregatedOutput();
            } else {
                return buildSingleActorOutput();
            }
        } catch (Exception e) {
            logger.warning("TransitionHistorySection: error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Returns a formatted string containing the transition history for a single actor.
     *
     * <p>The result is a text block starting with a header {@code [Transition History: <targetActorName>]} followed by
     * a list of transitions. Each line shows a success or failure indicator ({@code o} or {@code x}), the timestamp,
     * the transition label, an optional note in brackets, and an optional error message for failed transitions. The
     * block ends with a summary line stating the total number of transitions and the counts of succeeded and failed
     * ones.</p>
     *
     * <p>If no transitions are found for the specified actor, the method returns an empty string.</p>
     *
     * @throws Exception if a database error occurs during the query.
     */
    private String buildSingleActorOutput() throws Exception {
        List<TransitionEntry> entries = queryTransitions(targetActorName);
        if (entries.isEmpty()) {
            return "";  // No transitions, skip this section
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Transition History: ").append(targetActorName).append("]\n");

        int succeeded = 0;
        int failed = 0;

        for (TransitionEntry entry : entries) {
            sb.append(entry.success ? "o " : "x ");
            sb.append("[").append(entry.timestamp).append("] ");
            sb.append(entry.label);
            if (entry.note != null && !entry.note.isEmpty()) {
                sb.append(" [").append(entry.note).append("]");
            }
            if (!entry.success && entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                sb.append(" ").append(entry.errorMessage);
            }
            sb.append("\n");

            if (entry.success) {
                succeeded++;
            } else {
                failed++;
            }
        }

        sb.append("\nSummary: ").append(entries.size()).append(" transitions, ");
        sb.append(succeeded).append(" succeeded, ").append(failed).append(" failed\n");

        return sb.toString();
    }

    /**
     * Returns a string that aggregates the transition histories of the {@code nodeGroup} and all its child nodes.
     *
     * <p>The result is a formatted block where each child node's transitions are grouped under its own header,
     * followed by a total summary of all transitions across all nodes. If no nodes with transition logs are found, it
     * returns an empty string.</p>
     */
    private String buildAggregatedOutput() throws Exception {
        List<String> sources = queryDistinctSources();
        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Transition History: nodeGroup (with children)]\n");

        int totalTransitions = 0;
        int totalSucceeded = 0;
        int totalFailed = 0;

        for (String source : sources) {
            List<TransitionEntry> entries = queryTransitions(source);
            if (entries.isEmpty()) continue;

            sb.append("\n  [").append(source).append("]\n");

            for (TransitionEntry entry : entries) {
                sb.append("  ").append(entry.success ? "o " : "x ");
                sb.append("[").append(entry.timestamp).append("] ");
                sb.append(entry.label);
                if (entry.note != null && !entry.note.isEmpty()) {
                    sb.append(" [").append(entry.note).append("]");
                }
                if (!entry.success && entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                    sb.append(" ").append(entry.errorMessage);
                }
                sb.append("\n");

                totalTransitions++;
                if (entry.success) {
                    totalSucceeded++;
                } else {
                    totalFailed++;
                }
            }
        }

        sb.append("\nSummary: ").append(totalTransitions).append(" transitions, ");
        sb.append(totalSucceeded).append(" succeeded, ").append(totalFailed).append(" failed\n");

        return sb.toString();
    }

    /**
     * Returns a list of transition entries found in the log database for the specified actor.
     *
     * @param source the name of the actor (the {@code node_id} in the logs) to query
     * @return a list of {@code TransitionEntry} objects containing the timestamp, label, note, success status, and
     * error message for each transition found; an empty list if no transitions are found for the given source or
     * session
     */
    private List<TransitionEntry> queryTransitions(String source) throws Exception {
        List<TransitionEntry> entries = new ArrayList<>();

        String sql = "SELECT timestamp, label, level, message FROM logs " +
                     "WHERE session_id = ? AND node_id = ? AND message LIKE '%Transition %' " +
                     "ORDER BY timestamp";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.setString(2, source);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("timestamp");
                    String label = rs.getString("label");
                    String message = rs.getString("message");

                    boolean success = message.contains("SUCCESS");
                    String errorMessage = null;
                    if (!success) {
                        int dashIdx = message.indexOf(" - ");
                        if (dashIdx > 0) {
                            errorMessage = message.substring(dashIdx + 3);
                        }
                    }

                    String[] transitionAndNote = extractTransitionAndNote(message);
                    String transition = transitionAndNote[0];
                    String note = transitionAndNote[1];

                    String displayLabel = (label != null && label.contains("->")) ? label : transition;
                    String timeStr = ts.toLocalDateTime().format(TIME_FORMAT);

                    entries.add(new TransitionEntry(timeStr, displayLabel, note, success, errorMessage));
                }
            }
        }

        return entries;
    }

    /**
     * Returns a list of actor names that have recorded state transitions in the log database for the current session.
     *
     * @return a list of {@code node_id} strings that have entries in the {@code logs} table matching the current
     * {@code sessionId} and containing a transition message.
     *
     * @throws Exception if a database access error occurs.
     */
    private List<String> queryDistinctSources() throws Exception {
        List<String> sources = new ArrayList<>();

        String sql = "SELECT DISTINCT node_id FROM logs " +
                     "WHERE session_id = ? AND message LIKE '%Transition %' " +
                     "ORDER BY node_id";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sources.add(rs.getString("node_id"));
                }
            }
        }

        return sources;
    }

    /**
     * Splits a log message into a transition description and an optional note.
     *
     * Returns a two-element array where the first element is the transition (e.g., {@code "S1 -> S2"}) and the second
     * element is the text found within square brackets (e.g., {@code "my note"}).
     *
     * If the message does not contain the string {@code "Transition "} or does not follow the {@code "Transition ...:
     * ..."} format, returns an array containing {@code "unknown"} and an empty string.
     *
     * @param message the log message to parse
     */
    private String[] extractTransitionAndNote(String message) {
        if (message == null) return new String[]{"unknown", ""};

        int idx = message.indexOf("Transition ");
        if (idx < 0) return new String[]{"unknown", ""};

        String afterTransition = message.substring(idx + "Transition ".length());
        int colonIdx = afterTransition.indexOf(": ");
        if (colonIdx < 0) return new String[]{"unknown", ""};

        String statesPart = afterTransition.substring(colonIdx + 2);

        // Extract note [xxx]
        String note = "";
        int bracketStart = statesPart.indexOf(" [");
        int bracketEnd = statesPart.indexOf("]");
        if (bracketStart > 0 && bracketEnd > bracketStart) {
            note = statesPart.substring(bracketStart + 2, bracketEnd);
            statesPart = statesPart.substring(0, bracketStart);
        }

        // Remove " - error message" if present
        int dashIdx = statesPart.indexOf(" - ");
        if (dashIdx > 0) {
            statesPart = statesPart.substring(0, dashIdx);
        }

        return new String[]{statesPart.trim(), note};
    }

    /**
     * Returns {@code null}.
     *
     * <p>The section title is embedded directly within the string returned by {@link #generate()} rather than being
     * returned separately.</p>
     */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    /**
     * Holds the details of a single state transition extracted from the log database.
     *
     * <p>It contains the timestamp, the transition label (such as {@code "S1 -> S2"}), an optional annotation note,
     * the success status, and an optional error message for failed transitions.
     */
    private static class TransitionEntry {
        /** The formatted timestamp of the transition. */
        final String timestamp;
        /** The transition label (e.g., {@code "S1 -> S2"}). */
        final String label;
        /** An optional annotation note extracted from the log message. */
        final String note;
        /** Indicates if the state transition was a success. */
        final boolean success;
        /** The error message for failed transitions, or {@code null} if the transition succeeded. */
        final String errorMessage;

        /**
         * Initializes a record of a single state transition.
         *
         * @param timestamp the formatted time of the transition
         * @param label the transition description (e.g., {@code "S1 -> S2"})
         * @param note an optional annotation extracted from the log
         * @param success whether the transition succeeded
         * @param errorMessage the error message if the transition failed, or {@code null} if it succeeded
         */
        TransitionEntry(String timestamp, String label, String note, boolean success, String errorMessage) {
            this.timestamp = timestamp;
            this.label = label;
            this.note = note;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
