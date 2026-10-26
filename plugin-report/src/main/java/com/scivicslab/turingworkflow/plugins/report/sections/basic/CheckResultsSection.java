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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * Aggregates and formats log messages prefixed with {@code %} into a structured report section.
 *
 * <ul><li>{@link #generate()} returns a string containing a {@code [Check Results]} header followed by the
 * deduplicated, sorted messages found in the logs.</li></ul>
 *
 * <p>The class holds a JDBC {@link Connection} and a session ID to query the log database.</p>
 *
 * <p>One instance may serve several workflows, but it must be configured with the correct connection and session ID
 * for each.</p>
 *
 * <p>If the connection is null, the session ID is negative, or no matching messages are found, {@link #generate()}
 * returns an empty string.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class CheckResultsSection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(CheckResultsSection.class.getName());

    /**
     * The prefix used to identify lines in the log database that should be collected and included in the report.
     */
    private static final String REPORT_PREFIX = "%";

    private Connection connection;
    private long sessionId = -1;

    /**
     * Assigns the JDBC connection to the H2 log database used to query check results.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID used to query the log database for check results.
     *
     * @param sessionId the session ID to filter logs by
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Returns a string containing all log messages from the current session that are prefixed with {@code %},
     * organized under a {@code [Check Results]} header.
     *
     * The result is a sorted, deduplicated list of messages extracted from the log database, where any {@code
     * [node-xxx]} prefix is stripped and the leading {@code %} is removed. If no messages matching the pattern are
     * found, or if the database connection or session ID is invalid, it returns an empty string.
     *
     * <pre>{@code
     * // Example of a log message that would be captured:
     * // [node-web1] % Verification successful
     * }</pre>
     */
    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("CheckResultsSection: connection or sessionId not set");
            return "";
        }

        try {
            List<String> messages = getReportMessages();
            if (messages.isEmpty()) {
                return "";  // No check results, skip this section
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[Check Results]\n");
            for (String msg : messages) {
                sb.append(msg).append("\n");
            }
            return sb.toString();

        } catch (SQLException e) {
            logger.warning("CheckResultsSection: SQL error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Returns a sorted list of unique messages from the log database that are marked with the {@code %} prefix for the
     * current session.
     *
     * The list contains the text of each matching line with the {@code %} prefix and any {@code [node-xxx]} prefix
     * removed.
     *
     * @return a list of the extracted and cleaned messages, or an empty list if no matching messages are found
     * @throws SQLException if a database access error occurs
     */
    private List<String> getReportMessages() throws SQLException {
        List<String> messages = new ArrayList<>();

        String sql = "SELECT message FROM logs WHERE session_id = ? ORDER BY timestamp";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String message = rs.getString("message");
                    if (message != null) {
                        // Extract lines starting with %
                        for (String line : message.split("\n")) {
                            String trimmed = line.trim();
                            // Handle prefixes like [node-xxx] %message
                            String cleaned = trimmed.replaceFirst("^\\[node-[^\\]]+\\]\\s*", "");
                            if (cleaned.startsWith(REPORT_PREFIX)) {
                                messages.add(cleaned.substring(1).trim());
                            }
                        }
                    }
                }
            }
        }

        // Remove duplicates while preserving order, then sort
        List<String> uniqueMessages = new ArrayList<>(new LinkedHashSet<>(messages));
        uniqueMessages.sort(null);
        return uniqueMessages;
    }

    /** Returns null. */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
