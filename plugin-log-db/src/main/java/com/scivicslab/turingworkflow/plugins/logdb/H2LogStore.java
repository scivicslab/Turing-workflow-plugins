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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores and retrieves workflow execution logs using an embedded H2 database.
 *
 * <ul>
 * <li><code>startSession</code> creates a new session and returns its unique ID.</li>
 * <li><code>log</code> records a log entry for a specific node.</li>
 * <li><code>logAction</code> records an action's result, including its exit code and duration.</li>
 * <li><code>markNodeSuccess</code> and <code>markNodeFailed</code> record the final status of a node.</li>
 * <li><code>endSession</code> marks a session as finished and sets its final status.</li>
 * <li><code>getLogsByNode</code>, <code>getLogsByLevel</code>, and <code>listSessions</code> provide various ways to
 * query the stored data.</li>
 * <li><code>getSummary</code> returns a summary of a session's execution.</li>
 * </ul>
 *
 * <p>Every write operation is performed synchronously on the calling thread in its own transaction and is committed
 * when the method returns. A reader using a separate connection will see the changes immediately after the call
 * returns. This class does not maintain an internal queue or background threads; for asynchronous or batched
 * execution, use an actor such as {@link LogStoreActor}.</p>
 *
 * <p>One instance may serve several workflows or threads at once, as it uses separate connections for reading and
 * writing.</p>
 *
 * <p>To use this in a workflow, an actor is typically created to wrap the store:</p>
 * <pre>{@code
 * actor: log_store
 * method: log
 * arguments:
 *   sessionId: ${session_id}
 *   nodeId: "web-server"
 *   level: "INFO"
 *   message: "Starting service"
 * }</pre>
 *
 * <p>If a database error occurs during a write, the transaction is rolled back and the error is logged to the system
 * logger, but the caller of a non-returning action (like <code>log</code>) will not receive the failure.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class H2LogStore implements DistributedLogStore {

    private static final Logger LOG = Logger.getLogger(H2LogStore.class.getName());
    /** ISO 8601 formatter with timezone offset used for timestamping entries in the text log file. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** The system default timezone used for formatting timestamps in text logs. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The connection used for all read operations, which is delegated to {@link H2LogReader}. */
    private final Connection readConnection;
    /**
     * The connection used for all write operations, including starting sessions, logging entries, and marking node
     * results.
     */
    private final Connection writeConnection;
    /** The reader used for all query operations. */
    private final H2LogReader reader;

    /** Optional writer for a parallel text-based log file. */
    private PrintWriter textLogWriter;

    /**
     * Initializes an {@code H2LogStore} that stores workflow logs in an H2 database at the specified path.
     *
     * @param dbPath the path to the database file (without extension)
     * @throws SQLException if the database connection fails
     */
    public H2LogStore(Path dbPath) throws SQLException {
        this(dbPath, false);
    }

    /**
     * Initializes an H2LogStore with the specified database path and an option to enable lossless MVStore compression.
     *
     * @param dbPath path to the database file (without extension)
     * @param compress if true, enables compression to reduce the size of the {@code .mv.db} file at the cost of higher
     * CPU usage
     *
     * @throws SQLException if database connection fails
     */
    public H2LogStore(Path dbPath, boolean compress) throws SQLException {
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString() + ";AUTO_SERVER=TRUE"
                + (compress ? ";COMPRESS=TRUE" : "");
        this.writeConnection = DriverManager.getConnection(url);
        this.readConnection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(readConnection);
        initSchema();
    }

    /**
     * Initializes an in-memory H2 database for storing and querying workflow logs.
     *
     * @throws SQLException if the database connection fails
     */
    public H2LogStore() throws SQLException {
        String url = "jdbc:h2:mem:testdb_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.writeConnection = DriverManager.getConnection(url);
        this.readConnection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(readConnection);
        initSchema();
    }

    /** Returns a JDBC {@code Connection} used for read operations. */
    @Override
    public Connection getConnection() {
        return this.readConnection;
    }

    /**
     * Opens a text file for parallel logging of all log and action entries.
     *
     * If a text log file is already open, it is closed before the new one is opened.
     *
     * An {@code IOException} is thrown if the file cannot be opened for writing.
     *
     * @param textLogPath the path to the text log file
     */
    public void setTextLogFile(Path textLogPath) throws IOException {
        if (this.textLogWriter != null) {
            this.textLogWriter.close();
        }
        this.textLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(textLogPath.toFile(), true)), true);
        LOG.info("Text logging enabled: " + textLogPath);
    }

    /** Stops writing log entries to the text file. */
    public void disableTextLog() {
        if (this.textLogWriter != null) {
            this.textLogWriter.close();
            this.textLogWriter = null;
        }
    }

    private void initSchema() throws SQLException {
        initSchema(writeConnection);
    }

    /**
     * Creates the required tables and indexes for the log database on the given connection.
     *
     * @param conn the database connection to use for schema creation
     * @throws SQLException if the table creation or index creation fails
     */
    public static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id IDENTITY PRIMARY KEY,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    ended_at TIMESTAMP,
                    workflow_name VARCHAR(255),
                    overlay_name VARCHAR(255),
                    inventory_name VARCHAR(255),
                    node_count INT,
                    status VARCHAR(20) DEFAULT 'RUNNING',
                    cwd VARCHAR(1000),
                    git_commit VARCHAR(50),
                    git_branch VARCHAR(255),
                    command_line VARCHAR(2000),
                    plugin_version VARCHAR(50),
                    plugin_commit VARCHAR(50)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    node_id VARCHAR(255) NOT NULL,
                    label CLOB,
                    action_name CLOB,
                    level VARCHAR(10) NOT NULL,
                    message CLOB,
                    exit_code INT,
                    duration_ms BIGINT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS node_results (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    node_id VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    reason VARCHAR(1000),
                    FOREIGN KEY (session_id) REFERENCES sessions(id),
                    UNIQUE (session_id, node_id)
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_session ON logs(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_node ON logs(node_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_workflow ON sessions(workflow_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_overlay ON sessions(overlay_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_inventory ON sessions(inventory_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)");
        }
    }

    /**
     * Executes a single database task in a transaction of its own on the calling thread.
     *
     * @param task the unit of work to execute on the write connection
     * @throws SQLException if the database refuses the task; the transaction is rolled back before the exception is
     * thrown
     */
    private void run(LogTask task) throws SQLException {
        try {
            task.execute(writeConnection);
        } catch (SQLException e) {
            try {
                if (!writeConnection.getAutoCommit()) writeConnection.rollback();
            } catch (SQLException ignored) {
                // the original failure is the one to report
            }
            throw e;
        }
    }

    /**
     * Executes a database write and suppresses any resulting errors.
     *
     * <p>If the task fails due to a {@link SQLException}, the error is logged to the system logger, but the caller is
     * not notified and no exception is thrown. This is used for fire-and-forget operations like logging entries or
     * marking node results where a failure to record the event should not interrupt the workflow.</p>
     */
    private void write(LogTask task) {
        try {
            run(task);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Log write failed: " + task, e);
        }
    }

    @Override
    public long startSession(String workflowName, int nodeCount) {
        return startSession(workflowName, null, null, nodeCount);
    }

    @Override
    public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount) {
        return startSession(workflowName, overlayName, inventoryName, nodeCount,
                            null, null, null, null, null, null);
    }

    @Override
    public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount,
                             String cwd, String gitCommit, String gitBranch,
                             String commandLine, String pluginVersion, String pluginCommit) {
        String sql = "INSERT INTO sessions (workflow_name, overlay_name, inventory_name, node_count, " +
                "cwd, git_commit, git_branch, command_line, plugin_version, plugin_commit) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = writeConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workflowName);
            ps.setString(2, overlayName);
            ps.setString(3, inventoryName);
            ps.setInt(4, nodeCount);
            ps.setString(5, cwd);
            ps.setString(6, gitCommit);
            ps.setString(7, gitBranch);
            ps.setString(8, commandLine);
            ps.setString(9, pluginVersion);
            ps.setString(10, pluginCommit);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to start session", e);
        }
    }

    @Override
    public void log(long sessionId, String nodeId, LogLevel level, String message) {
        log(sessionId, nodeId, null, level, message);
    }

    @Override
    public void log(long sessionId, String nodeId, String label, LogLevel level, String message) {
        write(new LogTask.InsertLog(sessionId, nodeId, label, null, level, message, null, null));
        writeToTextLog(nodeId, label, level, message);
    }

    @Override
    public void logAction(long sessionId, String nodeId, String label,
                          String actionName, int exitCode, long durationMs, String output) {
        LogLevel level = parseLogLevelFromLabel(label, exitCode);
        write(new LogTask.InsertLog(sessionId, nodeId, label, actionName, level, output, exitCode, durationMs));
        writeToTextLog(nodeId, label, level, output);
    }

    private LogLevel parseLogLevelFromLabel(String label, int exitCode) {
        if (label != null && label.startsWith("log-")) {
            String levelName = label.substring(4).toUpperCase();
            return switch (levelName) {
                case "SEVERE" -> LogLevel.ERROR;
                case "WARNING" -> LogLevel.WARN;
                case "INFO" -> LogLevel.INFO;
                case "CONFIG", "FINE", "FINER", "FINEST" -> LogLevel.DEBUG;
                default -> exitCode == 0 ? LogLevel.INFO : LogLevel.ERROR;
            };
        }
        return exitCode == 0 ? LogLevel.INFO : LogLevel.ERROR;
    }

    private void writeToTextLog(String nodeId, String label, LogLevel level, String message) {
        if (textLogWriter == null) {
            return;
        }
        String timestamp = LocalDateTime.now().atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
        String labelPart = label != null ? " [" + label + "]" : "";
        textLogWriter.printf("%s %s %s%s %s%n", timestamp, level, nodeId, labelPart, message);
    }

    @Override
    public void markNodeSuccess(long sessionId, String nodeId) {
        write(new LogTask.UpdateNodeResult(sessionId, nodeId, "SUCCESS", null));
    }

    @Override
    public void markNodeFailed(long sessionId, String nodeId, String reason) {
        write(new LogTask.UpdateNodeResult(sessionId, nodeId, "FAILED", reason));
    }

    @Override
    public void endSession(long sessionId, SessionStatus status) {
        try (PreparedStatement ps = writeConnection.prepareStatement(
                "UPDATE sessions SET ended_at = CURRENT_TIMESTAMP, status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to end session", e);
        }
    }

    /**
     * Performs no action.
     *
     * <p>Every write operation in this class is executed synchronously on the calling thread and is committed before
     * the method returns, so there is no pending data to flush.</p>
     */
    @Override
    public void flushWrites() {
    }

    @Override
    public List<LogEntry> getLogsByNode(long sessionId, String nodeId) {
        return reader.getLogsByNode(sessionId, nodeId);
    }

    @Override
    public List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel) {
        return reader.getLogsByLevel(sessionId, minLevel);
    }

    @Override
    public SessionSummary getSummary(long sessionId) {
        return reader.getSummary(sessionId);
    }

    @Override
    public long getLatestSessionId() {
        return reader.getLatestSessionId();
    }

    @Override
    public List<SessionSummary> listSessions(int limit) {
        return reader.listSessions(limit);
    }

    /**
     * Closes the text log writer, if enabled, and both the read and write database connections.
     *
     * @throws Exception if an error occurs while closing the connections or the text log writer.
     */
    @Override
    public void close() throws Exception {
        if (textLogWriter != null) {
            textLogWriter.close();
            textLogWriter = null;
        }
        writeConnection.close();
        readConnection.close();
    }

    /**
     * Represents a single database transaction to be executed on the write connection.
     *
     * <p><ul><li>{@link InsertLog} inserts a single log entry into the {@code logs} table.</li><li>{@link
     * UpdateNodeResult} inserts or updates a node's final status in the {@code node_results} table.</li></ul>
     */
    private interface LogTask {
        /**
         * Executes the database operation on the provided connection.
         *
         * @param conn the connection to use for the operation
         * @throws SQLException if a database error occurs
         */
        void execute(Connection conn) throws SQLException;

        /**
         * Records a single log entry into the {@code logs} table.
         *
         * @param sessionId the session ID to associate with the log
         * @param nodeId the identifier of the node generating the log
         * @param label the workflow label, may be {@code null}
         * @param actionName the name of the action, may be {@code null}
         * @param level the severity level of the log
         * @param message the text content of the log
         * @param exitCode the process exit code, may be {@code null}
         * @param durationMs the execution duration in milliseconds, may be {@code null}
         */
        record InsertLog(long sessionId, String nodeId, String label, String actionName,
                         LogLevel level, String message, Integer exitCode, Long durationMs) implements LogTask {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO logs (session_id, node_id, label, action_name, level, message, exit_code, duration_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, sessionId);
                    ps.setString(2, nodeId);
                    ps.setString(3, label);
                    ps.setString(4, actionName);
                    ps.setString(5, level.name());
                    ps.setString(6, message);
                    if (exitCode != null) {
                        ps.setInt(7, exitCode);
                    } else {
                        ps.setNull(7, Types.INTEGER);
                    }
                    if (durationMs != null) {
                        ps.setLong(8, durationMs);
                    } else {
                        ps.setNull(8, Types.BIGINT);
                    }
                    ps.executeUpdate();
                }
            }
        }

        /**
         * Records or updates the final outcome of a specific node within a session in the {@code node_results} table.
         *
         * @param sessionId the session ID
         * @param nodeId the node identifier
         * @param status the result status, either "SUCCESS" or "FAILED"
         * @param reason the reason for failure, or {@code null} if the node succeeded
         */
        record UpdateNodeResult(long sessionId, String nodeId, String status, String reason) implements LogTask {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "MERGE INTO node_results (session_id, node_id, status, reason) KEY (session_id, node_id) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, sessionId);
                    ps.setString(2, nodeId);
                    ps.setString(3, status);
                    ps.setString(4, reason);
                    ps.executeUpdate();
                }
            }
        }
    }
}
