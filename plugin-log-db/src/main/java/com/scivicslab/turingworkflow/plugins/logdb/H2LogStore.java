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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * H2 Database implementation of DistributedLogStore.
 *
 * <p>Uses H2 embedded database for storing logs from distributed workflow
 * execution. Supports concurrent access from multiple threads and provides
 * efficient querying by node, level, and time.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Pure Java - no native dependencies</li>
 *   <li>Single file storage (.mv.db)</li>
 *   <li>Asynchronous batch writing for performance</li>
 *   <li>SQL-based querying</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class H2LogStore implements DistributedLogStore {

    private static final Logger LOG = Logger.getLogger(H2LogStore.class.getName());
    /** ISO 8601 formatter with timezone offset for text log output. */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** System default timezone used for timestamp formatting in text logs. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** Dedicated connection for read operations (delegated to {@link H2LogReader}). */
    private final Connection readConnection;
    /** Dedicated connection for write operations, used exclusively by the writer thread. */
    private final Connection writeConnection;
    /** Delegate reader for all query operations. */
    private final H2LogReader reader;
    /** Queue of pending write tasks to be processed by the writer thread. */
    private final BlockingQueue<LogTask> writeQueue;
    /** Background thread that drains the write queue in batches. */
    private final Thread writerThread;
    /** Flag controlling the writer thread lifecycle. */
    private final AtomicBoolean running;
    /** Maximum number of tasks processed per batch commit. */
    private static final int BATCH_SIZE = 100;

    /** Sentinel task used by {@link #flushWrites()} to wait for in-flight batch completion. */
    private final AtomicReference<CountDownLatch> flushLatch = new AtomicReference<>();

    /** Optional text log writer for parallel file-based logging. */
    private PrintWriter textLogWriter;

    /**
     * Creates an H2LogStore with the specified database path.
     *
     * @param dbPath path to the database file (without extension)
     * @throws SQLException if database connection fails
     */
    public H2LogStore(Path dbPath) throws SQLException {
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString() + ";AUTO_SERVER=TRUE";
        this.writeConnection = DriverManager.getConnection(url);
        this.readConnection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(readConnection);
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        initSchema();

        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Creates an in-memory H2LogStore (for testing).
     *
     * @throws SQLException if database connection fails
     */
    public H2LogStore() throws SQLException {
        String url = "jdbc:h2:mem:testdb_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.writeConnection = DriverManager.getConnection(url);
        this.readConnection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(readConnection);
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        initSchema();

        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /** {@inheritDoc} */
    @Override
    public Connection getConnection() {
        return this.readConnection;
    }

    /**
     * Sets the text log file for additional text-based logging.
     *
     * @param textLogPath path to the text log file
     * @throws IOException if the file cannot be opened for writing
     */
    public void setTextLogFile(Path textLogPath) throws IOException {
        if (this.textLogWriter != null) {
            this.textLogWriter.close();
        }
        this.textLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(textLogPath.toFile(), true)), true);
        LOG.info("Text logging enabled: " + textLogPath);
    }

    /**
     * Disables text file logging.
     */
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
     * Initializes the log database schema on the given connection.
     *
     * @param conn the database connection
     * @throws SQLException if schema initialization fails
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

    private void writerLoop() {
        List<LogTask> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get() || !writeQueue.isEmpty()) {
            try {
                LogTask task = writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (task != null) {
                    batch.add(task);
                    writeQueue.drainTo(batch, BATCH_SIZE - 1);
                    processBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
    }

    private void processBatch(List<LogTask> batch) {
        try {
            writeConnection.setAutoCommit(false);
            for (LogTask task : batch) {
                task.execute(writeConnection);
            }
            writeConnection.commit();
            writeConnection.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                writeConnection.rollback();
                writeConnection.setAutoCommit(true);
            } catch (SQLException ex) {
                // Ignore rollback errors
            }
            e.printStackTrace();
        } finally {
            // Count down flush sentinels after commit (or rollback)
            for (LogTask task : batch) {
                if (task instanceof FlushSentinel sentinel) {
                    sentinel.latch().countDown();
                }
            }
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
        AtomicReference<Long> sessionIdRef = new AtomicReference<>(-1L);
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        writeQueue.offer(new LogTask() {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (workflow_name, overlay_name, inventory_name, node_count, " +
                        "cwd, git_commit, git_branch, command_line, plugin_version, plugin_commit) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
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
                        if (rs.next()) {
                            sessionIdRef.set(rs.getLong(1));
                        }
                    }
                } catch (SQLException e) {
                    errorRef.set(new RuntimeException("Failed to start session", e));
                    throw e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting session", e);
        }

        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return sessionIdRef.get();
    }

    @Override
    public void log(long sessionId, String nodeId, LogLevel level, String message) {
        log(sessionId, nodeId, null, level, message);
    }

    @Override
    public void log(long sessionId, String nodeId, String label, LogLevel level, String message) {
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, label, null, level, message, null, null));
        writeToTextLog(nodeId, label, level, message);
    }

    @Override
    public void logAction(long sessionId, String nodeId, String label,
                          String actionName, int exitCode, long durationMs, String output) {
        LogLevel level = parseLogLevelFromLabel(label, exitCode);
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, label, actionName, level, output, exitCode, durationMs));
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
        writeQueue.offer(new LogTask.UpdateNodeResult(sessionId, nodeId, "SUCCESS", null));
    }

    @Override
    public void markNodeFailed(long sessionId, String nodeId, String reason) {
        writeQueue.offer(new LogTask.UpdateNodeResult(sessionId, nodeId, "FAILED", reason));
    }

    @Override
    public void endSession(long sessionId, SessionStatus status) {
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        writeQueue.offer(new LogTask() {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sessions SET ended_at = CURRENT_TIMESTAMP, status = ? WHERE id = ?")) {
                    ps.setString(1, status.name());
                    ps.setLong(2, sessionId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    errorRef.set(new RuntimeException("Failed to end session", e));
                    throw e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while ending session", e);
        }

        if (errorRef.get() != null) {
            throw errorRef.get();
        }
    }

    /**
     * Waits for all queued and in-flight writes to complete (including commit).
     * Uses a sentinel task whose latch is counted down after the batch commit.
     */
    private void flushWrites() {
        CountDownLatch latch = new CountDownLatch(1);
        writeQueue.offer(new FlushSentinel(latch));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sentinel task used to synchronize flush operations.
     *
     * <p>The latch is counted down by {@code processBatch()} after the batch
     * commit completes, not during task execution. This ensures that callers
     * of {@link #flushWrites()} block until all preceding writes are committed.</p>
     *
     * @param latch the countdown latch to signal completion
     */
    private record FlushSentinel(CountDownLatch latch) implements LogTask {
        @Override
        public void execute(Connection conn) {
            // No-op: latch is counted down after commit in processBatch
        }
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
     * Shuts down the log store, stopping the writer thread and closing all connections.
     *
     * <p>Waits up to 5 seconds for the writer thread to finish processing
     * remaining tasks before closing connections. Also closes the text log
     * writer if enabled.</p>
     *
     * @throws Exception if an error occurs while closing connections
     */
    @Override
    public void close() throws Exception {
        running.set(false);
        writerThread.interrupt();
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (textLogWriter != null) {
            textLogWriter.close();
            textLogWriter = null;
        }
        writeConnection.close();
        readConnection.close();
    }

    /**
     * Internal interface representing a unit of work to be executed
     * asynchronously on the write connection by the writer thread.
     */
    private interface LogTask {
        /**
         * Executes this task on the given database connection.
         *
         * @param conn the write connection
         * @throws SQLException if a database error occurs
         */
        void execute(Connection conn) throws SQLException;

        /**
         * Task that inserts a single log entry into the {@code logs} table.
         *
         * @param sessionId  the session ID
         * @param nodeId     the node identifier
         * @param label      the workflow label, may be {@code null}
         * @param actionName the action name, may be {@code null}
         * @param level      the log level
         * @param message    the log message
         * @param exitCode   the exit code, may be {@code null}
         * @param durationMs the duration in milliseconds, may be {@code null}
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
         * Task that inserts or updates (merges) a node result in the {@code node_results} table.
         *
         * @param sessionId the session ID
         * @param nodeId    the node identifier
         * @param status    the result status ("SUCCESS" or "FAILED")
         * @param reason    the failure reason, may be {@code null} for successful nodes
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
