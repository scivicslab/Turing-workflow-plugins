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

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Queries workflow execution logs from an H2 database.
 *
 * <p>Users can retrieve session summaries, log entries, and node information through several operations:
 * <ul>
 * <li>{@link #getLogsByNode(long, String)} returns a list of log entries for a specific node within a session.</li>
 * <li>{@link #getLogsByLevel(long, LogLevel)} returns a list of log entries at or above the specified level.</li>
 * <li>{@link #getSummary(long)} returns a {@link SessionSummary} containing execution metadata and statistics, or
 * {@code null} if the session ID is not found.</li>
 * <li>{@link #getLatestSessionId()} returns the ID of the most recent session, or -1 if no sessions exist.</li>
 * <li>{@link #listSessions(int)} returns a list of recent session summaries, ordered by start time descending.</li>
 * <li>{@link #listSessionsFiltered(String, String, String, java.time.LocalDateTime, java.time.LocalDateTime, int)}
 * returns a list of session summaries matching the specified criteria.</li>
 * <li>{@link #getNodesInSession(long)} returns a list of {@link NodeInfo} for all nodes in a session.</li>
 * </ul>
 *
 * <p>The reader holds a JDBC connection that remains open until {@link #close()} is called. If the reader was
 * initialized with an existing connection, calling {@link #close()} does not close that connection. One instance may
 * serve several threads at once.
 *
 * <p>Example of querying a session:
 * <pre>{@code
 * H2LogReader reader = new H2LogReader(Path.of("/path/to/db"));
 * SessionSummary summary = reader.getSummary(123L);
 * if (summary != null) {
 *     System.out.println(summary.status());
 * }
 * reader.close();
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class H2LogReader implements AutoCloseable {

    /** The JDBC connection used for read operations. */
    private final Connection connection;
    /** Indicates whether this reader is responsible for closing the underlying database connection. */
    private final boolean ownsConnection;

    /**
     * Connects to an H2 log database file for reading.
     *
     * @param dbPath the path to the database file, excluding the extension
     *
     * @throws SQLException if the connection to the database fails
     */
    public H2LogReader(Path dbPath) throws SQLException {
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString() + ";AUTO_SERVER=TRUE";
        this.connection = H2Connections.open(url);
        this.ownsConnection = true;
    }

    /**
     * Connects to a remote log database via an H2 TCP server.
     *
     * @param host the H2 server hostname (typically "localhost")
     * @param port the H2 server TCP port
     * @param dbPath the database path on the server
     * @throws SQLException if the database connection fails
     */
    public H2LogReader(String host, int port, String dbPath) throws SQLException {
        String url = "jdbc:h2:tcp://" + host + ":" + port + "/" + dbPath;
        this.connection = H2Connections.open(url);
        this.ownsConnection = true;
    }

    /**
     * Initializes a reader using an existing database connection.
     *
     * <p>The connection is used for all subsequent log queries but will not be closed when this reader is closed.</p>
     *
     * @param connection the database connection to use
     */
    public H2LogReader(Connection connection) {
        this.connection = connection;
        this.ownsConnection = false;
    }

    /**
     * Returns a list of log entries belonging to a specific session and node, ordered by timestamp.
     *
     * @param sessionId the session ID to filter by
     * @param nodeId the node ID to filter by
     * @return a list of {@link LogEntry} objects for the specified session and node, or an empty list if no entries
     * match
     */
    public List<LogEntry> getLogsByNode(long sessionId, String nodeId) {
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM logs WHERE session_id = ? AND node_id = ? ORDER BY timestamp")) {
            ps.setLong(1, sessionId);
            ps.setString(2, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query logs", e);
        }
        return entries;
    }

    /**
     * Returns a list of log entries from the specified session that have a log level at or above {@code minLevel}.
     *
     * @param sessionId the session ID to query
     * @param minLevel the minimum log level to include in the results
     */
    public List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel) {
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM logs WHERE session_id = ? AND level IN (?, ?, ?, ?) ORDER BY timestamp")) {
            ps.setLong(1, sessionId);
            int idx = 2;
            for (LogLevel level : LogLevel.values()) {
                if (level.isAtLeast(minLevel)) {
                    ps.setString(idx++, level.name());
                } else {
                    ps.setString(idx++, "NONE");
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query logs", e);
        }
        return entries;
    }

    /**
     * Returns a {@link SessionSummary} containing the execution metadata and statistics for the specified session.
     *
     * @param sessionId the session ID to look up
     * @return a {@link SessionSummary} containing the session's details, or {@code null} if no session exists with the
     * given ID
     */
    public SessionSummary getSummary(long sessionId) {
        try {
            String workflowName = null;
            String overlayName = null;
            String inventoryName = null;
            LocalDateTime startedAt = null;
            LocalDateTime endedAt = null;
            int nodeCount = 0;
            SessionStatus status = SessionStatus.RUNNING;

            // Execution context
            String cwd = null;
            String gitCommit = null;
            String gitBranch = null;
            String commandLine = null;
            String pluginVersion = null;
            String pluginCommit = null;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sessions WHERE id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        workflowName = rs.getString("workflow_name");
                        overlayName = rs.getString("overlay_name");
                        inventoryName = rs.getString("inventory_name");
                        Timestamp ts = rs.getTimestamp("started_at");
                        startedAt = ts != null ? ts.toLocalDateTime() : null;
                        ts = rs.getTimestamp("ended_at");
                        endedAt = ts != null ? ts.toLocalDateTime() : null;
                        nodeCount = rs.getInt("node_count");
                        String statusStr = rs.getString("status");
                        if (statusStr != null) {
                            status = SessionStatus.valueOf(statusStr);
                        }

                        // Read execution context (may be null for older sessions)
                        cwd = getStringOrNull(rs, "cwd");
                        gitCommit = getStringOrNull(rs, "git_commit");
                        gitBranch = getStringOrNull(rs, "git_branch");
                        commandLine = getStringOrNull(rs, "command_line");
                        pluginVersion = getStringOrNull(rs, "plugin_version");
                        pluginCommit = getStringOrNull(rs, "plugin_commit");
                    } else {
                        return null;
                    }
                }
            }

            int successCount = 0;
            int failedCount = 0;
            List<String> failedNodes = new ArrayList<>();

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT node_id, status FROM node_results WHERE session_id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String nodeStatus = rs.getString("status");
                        if ("SUCCESS".equals(nodeStatus)) {
                            successCount++;
                        } else {
                            failedCount++;
                            failedNodes.add(rs.getString("node_id"));
                        }
                    }
                }
            }

            // Use actual node count from node_results if available
            int actualNodeCount = successCount + failedCount;
            if (actualNodeCount > 0) {
                nodeCount = actualNodeCount;
            }

            int totalLogEntries = 0;
            int errorCount = 0;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) as total, SUM(CASE WHEN level = 'ERROR' THEN 1 ELSE 0 END) as errors " +
                    "FROM logs WHERE session_id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalLogEntries = rs.getInt("total");
                        errorCount = rs.getInt("errors");
                    }
                }
            }

            return new SessionSummary(sessionId, workflowName, overlayName, inventoryName,
                    startedAt, endedAt, nodeCount, status, successCount, failedCount,
                    failedNodes, totalLogEntries, errorCount,
                    cwd, gitCommit, gitBranch, commandLine, pluginVersion, pluginCommit);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to get session summary", e);
        }
    }

    private String getStringOrNull(ResultSet rs, String columnName) {
        try {
            return rs.getString(columnName);
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Returns the ID of the most recently started session.
     *
     * @return the latest session ID, or -1 if no sessions exist
     */
    public long getLatestSessionId() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(id) FROM sessions")) {
            if (rs.next()) {
                long id = rs.getLong(1);
                return rs.wasNull() ? -1 : id;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get latest session", e);
        }
        return -1;
    }

    /**
     * Retrieves a list of the most recent workflow execution sessions, ordered by start time descending.
     *
     * @param limit the maximum number of sessions to return
     * @return a list of session summaries for the most recent sessions up to the specified limit
     */
    public List<SessionSummary> listSessions(int limit) {
        List<SessionSummary> sessions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM sessions ORDER BY started_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(getSummary(rs.getLong("id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
        return sessions;
    }

    /**
     * Returns a list of session summaries that match the specified criteria, ordered by start time descending.
     *
     * @param workflowName the workflow name to filter by, or {@code null} to include all workflows
     * @param overlayName the overlay name to filter by, or {@code null} to include all overlays
     * @param inventoryName the inventory name to filter by, or {@code null} to include all inventories
     * @param startedAfter the earliest start time to include, or {@code null} to include all sessions
     * @param limit the maximum number of sessions to return
     */
    public List<SessionSummary> listSessionsFiltered(String workflowName, String overlayName,
                                                      String inventoryName, LocalDateTime startedAfter,
                                                      int limit) {
        return listSessionsFiltered(workflowName, overlayName, inventoryName, startedAfter, null, limit);
    }

    /**
     * Returns a list of session summaries matching the specified criteria, ordered by start time descending.
     *
     * @param workflowName the name of the workflow to filter by (null to skip)
     * @param overlayName the name of the overlay to filter by (null to skip)
     * @param inventoryName the name of the inventory to filter by (null to skip)
     * @param startedAfter the earliest start time to include (null to skip)
     * @param endedAfter the earliest end time to include (null to skip)
     * @param limit the maximum number of sessions to return
     * @return a list of session summaries matching the criteria
     */
    public List<SessionSummary> listSessionsFiltered(String workflowName, String overlayName,
                                                      String inventoryName, LocalDateTime startedAfter,
                                                      LocalDateTime endedAfter, int limit) {
        List<SessionSummary> sessions = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id FROM sessions WHERE 1=1");

        if (workflowName != null) sql.append(" AND workflow_name = ?");
        if (overlayName != null) sql.append(" AND overlay_name = ?");
        if (inventoryName != null) sql.append(" AND inventory_name = ?");
        if (startedAfter != null) sql.append(" AND started_at >= ?");
        if (endedAfter != null) sql.append(" AND ended_at >= ?");
        sql.append(" ORDER BY started_at DESC LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            if (workflowName != null) ps.setString(idx++, workflowName);
            if (overlayName != null) ps.setString(idx++, overlayName);
            if (inventoryName != null) ps.setString(idx++, inventoryName);
            if (startedAfter != null) ps.setTimestamp(idx++, Timestamp.valueOf(startedAfter));
            if (endedAfter != null) ps.setTimestamp(idx++, Timestamp.valueOf(endedAfter));
            ps.setInt(idx, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(getSummary(rs.getLong("id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
        return sessions;
    }

    /**
     * A summary of a single node's execution and logging activity within a session.
     *
     * @param nodeId the node identifier
     * @param status the node status (SUCCESS, FAILED, or null if not yet recorded)
     * @param logCount the number of log entries recorded for this node
     */
    public record NodeInfo(String nodeId, String status, int logCount) {}

    /**
     * Returns a list of {@link NodeInfo} for all nodes recorded in the specified session, ordered by node ID.
     *
     * @param sessionId the session ID to query
     * @return a list of node information containing the node ID, its status, and the number of log entries associated
     * with it
     */
    public List<NodeInfo> getNodesInSession(long sessionId) {
        List<NodeInfo> nodes = new ArrayList<>();
        String sql = """
            SELECT nr.node_id,
                   nr.status,
                   COUNT(l.id) as log_count
            FROM node_results nr
            LEFT JOIN logs l ON nr.session_id = l.session_id AND nr.node_id = l.node_id
            WHERE nr.session_id = ?
            GROUP BY nr.node_id, nr.status
            ORDER BY nr.node_id
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(new NodeInfo(
                            rs.getString("node_id"),
                            rs.getString("status"),
                            rs.getInt("log_count")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get nodes in session", e);
        }
        return nodes;
    }

    private LogEntry mapLogEntry(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("timestamp");
        Integer exitCode = rs.getObject("exit_code") != null ? rs.getInt("exit_code") : null;
        Long durationMs = rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null;
        String levelStr = rs.getString("level");
        LogLevel level = levelStr != null ? LogLevel.valueOf(levelStr) : LogLevel.INFO;

        return new LogEntry(
                rs.getLong("id"),
                rs.getLong("session_id"),
                ts != null ? ts.toLocalDateTime() : null,
                rs.getString("node_id"),
                rs.getString("label"),
                rs.getString("action_name"),
                level,
                rs.getString("message"),
                exitCode,
                durationMs
        );
    }

    /**
     * Releases the database connection used by this reader, provided the reader was responsible for opening it.
     *
     * <p>If the reader was created using an existing {@link Connection}, the connection remains open. A call to this
     * method has no effect if the connection is already closed.</p>
     *
     * @throws SQLException if a database error occurs while closing the connection.
     */
    @Override
    public void close() throws SQLException {
        if (ownsConnection) {
            connection.close();
        }
    }
}
