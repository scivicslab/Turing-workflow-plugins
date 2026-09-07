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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies several log databases into one.
 *
 * <p>Separate from {@link MergeLogsCLI} because the merge has two callers with nothing else in
 * common: the {@code log-merge} subcommand, which prints to a terminal, and a long-running program
 * that keeps a merged database up to date and reports through its own screen. Neither can use the
 * other's output, so what they share is this: the copying, and a result they each present their own
 * way.</p>
 *
 * <p>Every method here is static and holds no state between calls. One merge's counters live in the
 * {@link Report} it returns.</p>
 *
 * @author devteam@scivicslab.com
 * @since 4.1.0
 */
public final class LogMerger {

    private LogMerger() {
    }

    /**
     * What one merge did.
     *
     * @param sessionsMerged  how many sessions were copied
     * @param sessionsSkipped how many were already in the target
     * @param logsMerged      how many log rows were copied
     * @param nodeResultsMerged how many node-result rows were copied
     * @param problems        one line per source that could not be read; empty when all were read
     */
    public record Report(int sessionsMerged, int sessionsSkipped, int logsMerged,
                         int nodeResultsMerged, List<String> problems) {}

    /**
     * How much one source holds, without copying anything.
     *
     * @param database the source, as a path with no {@code .mv.db} extension
     * @param sessions how many sessions it holds
     * @param logs     how many log rows it holds
     * @param nodeResults how many node-result rows it holds
     * @param problem  what went wrong reading it, or {@code ""} when nothing did
     */
    public record Count(Path database, int sessions, int logs, int nodeResults, String problem) {}

    /**
     * Finds the log databases under a directory.
     *
     * @param directory    the directory to walk
     * @param namePrefixes keep only files whose name starts with one of these; empty keeps every
     *                     {@code .mv.db} file found, including databases other programs wrote
     * @return the databases, as paths with no {@code .mv.db} extension
     * @throws IOException when the directory cannot be walked
     */
    public static List<Path> scan(Path directory, List<String> namePrefixes) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".mv.db"))
                 .filter(p -> nameMatches(p.getFileName().toString(), namePrefixes))
                 .map(LogMerger::withoutSuffix)
                 .forEach(found::add);
        }
        return found;
    }

    /**
     * Answers whether a scanned database is one the caller asked for.
     *
     * @param fileName the file name, extension included
     * @param prefixes the accepted prefixes; empty means every name is accepted
     * @return true when the name starts with one of the prefixes, or when there are no prefixes
     */
    public static boolean nameMatches(String fileName, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** @return the path with the {@code .mv.db} extension removed */
    private static Path withoutSuffix(Path databaseFile) {
        String s = databaseFile.toString();
        return Path.of(s.substring(0, s.length() - ".mv.db".length()));
    }

    /**
     * Counts what a source holds, without copying anything.
     *
     * @param source the source, as a path with no {@code .mv.db} extension
     * @return the counts, or a {@link Count} carrying the problem when the source cannot be read
     */
    public static Count count(Path source) {
        try (Connection conn = open(source, false)) {
            if (!tableExists(conn, "sessions")) {
                return new Count(source, 0, 0, 0, "");
            }
            return new Count(source,
                    countRows(conn, "sessions"),
                    tableExists(conn, "logs") ? countRows(conn, "logs") : 0,
                    tableExists(conn, "node_results") ? countRows(conn, "node_results") : 0,
                    "");
        } catch (SQLException e) {
            return new Count(source, 0, 0, 0, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Copies every source into {@code target}, skipping sessions the target already holds.
     *
     * <p>A session is the same session when its name and start time are the same, so running this
     * again over the same sources adds nothing.</p>
     *
     * @param target  the database to write, as a path with no {@code .mv.db} extension. Created
     *                when absent
     * @param sources the databases to read, as paths with no {@code .mv.db} extension. The target
     *                is skipped when it appears among them
     * @return what was copied
     * @throws SQLException when the target cannot be opened or written
     */
    public static Report merge(Path target, List<Path> sources) throws SQLException {
        int sessionsMerged = 0;
        int sessionsSkipped = 0;
        int logsMerged = 0;
        int nodeResultsMerged = 0;
        List<String> problems = new ArrayList<>();

        String targetPath = target.toAbsolutePath().toString();
        try (Connection targetConn = open(target, true)) {
            initializeSchema(targetConn);
            Set<String> existing = loadExistingSessions(targetConn);

            for (Path source : sources) {
                if (source.toAbsolutePath().toString().equals(targetPath)) {
                    continue;
                }
                try (Connection sourceConn = open(source, false)) {
                    Report one = mergeOne(sourceConn, targetConn, existing,
                            source.getFileName() + ".mv.db");
                    sessionsMerged += one.sessionsMerged();
                    sessionsSkipped += one.sessionsSkipped();
                    logsMerged += one.logsMerged();
                    nodeResultsMerged += one.nodeResultsMerged();
                } catch (SQLException e) {
                    problems.add(source + ": " + e.getMessage());
                }
            }
            targetConn.commit();
        }
        return new Report(sessionsMerged, sessionsSkipped, logsMerged, nodeResultsMerged,
                List.copyOf(problems));
    }

    /**
     * Opens one database.
     *
     * @param database the database, as a path with no {@code .mv.db} extension
     * @param compress true for the target, which is the only database written here. The sources
     *                 were written with MVStore compression on, so a target without it comes out
     *                 larger than every source added together
     * @return the open connection, with auto-commit off
     * @throws SQLException when the database cannot be opened
     */
    static Connection open(Path database, boolean compress) throws SQLException {
        // AUTO_SERVER=TRUE is required, not optional: H2LogStore opens every log database with it,
        // and H2 refuses a second connection that does not ask for the same mode. Without it, every
        // database a running program holds open fails with "Database may be already in use".
        String url = "jdbc:h2:" + database.toAbsolutePath() + ";AUTO_SERVER=TRUE"
                + (compress ? ";COMPRESS=TRUE" : "");
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(false);
        return conn;
    }

    /** Creates the tables the target needs, plus the column that records where a session came from. */
    private static void initializeSchema(Connection conn) throws SQLException {
        H2LogStore.initSchema(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source_db VARCHAR(255)");
        }
        conn.commit();
    }

    /** @return a key per session already in the target, so the same session is not copied twice */
    private static Set<String> loadExistingSessions(Connection conn) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT workflow_name, started_at FROM sessions")) {
            while (rs.next()) {
                existing.add(sessionKey(rs.getString("workflow_name"), rs.getTimestamp("started_at")));
            }
        }
        return existing;
    }

    /**
     * Identifies a session across databases.
     *
     * <p>The name alone does not: {@code quarkus-chat-ui} reuses one name for every conversation it
     * records. The start time is written to the microsecond, so two conversations never share one.</p>
     */
    private static String sessionKey(String workflowName, Timestamp startedAt) {
        return (workflowName == null ? "" : workflowName) + "|"
                + (startedAt == null ? "" : startedAt.toString());
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT 1 FROM " + tableName + " WHERE 1=0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static int countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Copies one source's sessions, adding each session's key to {@code existing} as it goes. */
    private static Report mergeOne(Connection source, Connection target, Set<String> existing,
                                   String sourceName) throws SQLException {
        if (!tableExists(source, "sessions")) {
            return new Report(0, 0, 0, 0, List.of());
        }
        int merged = 0;
        int skipped = 0;
        int logs = 0;
        int nodeResults = 0;
        try (Statement stmt = source.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sessions ORDER BY id")) {
            Set<String> columns = columnsOf(rs);
            while (rs.next()) {
                long oldSessionId = rs.getLong("id");
                String key = sessionKey(rs.getString("workflow_name"), rs.getTimestamp("started_at"));
                if (existing.contains(key)) {
                    skipped++;
                    continue;
                }
                long newSessionId = insertSession(target, rs, columns, sourceName);
                existing.add(key);
                merged++;
                logs += copyLogs(source, target, oldSessionId, newSessionId);
                nodeResults += copyNodeResults(source, target, oldSessionId, newSessionId);
            }
        }
        return new Report(merged, skipped, logs, nodeResults, List.of());
    }

    /**
     * Returns the column names one source's {@code sessions} query produced, upper-cased.
     *
     * <p>A database written by an older schema does not have every column this copies, and asking
     * a {@link ResultSet} for a column it does not have throws rather than answering null.</p>
     */
    private static Set<String> columnsOf(ResultSet rs) throws SQLException {
        Set<String> names = new HashSet<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnLabel(i).toUpperCase());
        }
        return names;
    }

    /** @return the column's value, or null when this source has no such column */
    private static String stringOrNull(ResultSet rs, Set<String> columns, String name)
            throws SQLException {
        return columns.contains(name.toUpperCase()) ? rs.getString(name) : null;
    }

    private static long insertSession(Connection target, ResultSet rs, Set<String> columns,
                                      String sourceName) throws SQLException {
        // The six columns after status carry which program wrote the conversation and how it was
        // started. Dropping them here would make the merged database unable to answer "which tool
        // was this", which is the whole reason the source records them.
        String sql = """
            INSERT INTO sessions (started_at, ended_at, workflow_name, overlay_name,
                                  inventory_name, node_count, status,
                                  cwd, git_commit, git_branch, command_line,
                                  plugin_version, plugin_commit, source_db)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = target.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, rs.getTimestamp("started_at"));
            ps.setTimestamp(2, rs.getTimestamp("ended_at"));
            ps.setString(3, rs.getString("workflow_name"));
            ps.setString(4, rs.getString("overlay_name"));
            ps.setString(5, rs.getString("inventory_name"));
            ps.setInt(6, rs.getInt("node_count"));
            ps.setString(7, rs.getString("status"));
            ps.setString(8, stringOrNull(rs, columns, "cwd"));
            ps.setString(9, stringOrNull(rs, columns, "git_commit"));
            ps.setString(10, stringOrNull(rs, columns, "git_branch"));
            ps.setString(11, stringOrNull(rs, columns, "command_line"));
            ps.setString(12, stringOrNull(rs, columns, "plugin_version"));
            ps.setString(13, stringOrNull(rs, columns, "plugin_commit"));
            ps.setString(14, sourceName);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to get generated session ID");
    }

    private static int copyLogs(Connection source, Connection target,
                                long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String insertSql = """
            INSERT INTO logs (session_id, timestamp, node_id, label, action_name,
                             level, message, exit_code, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement selectPs =
                     source.prepareStatement("SELECT * FROM logs WHERE session_id = ?");
             PreparedStatement insertPs = target.prepareStatement(insertSql)) {
            selectPs.setLong(1, oldSessionId);
            try (ResultSet rs = selectPs.executeQuery()) {
                while (rs.next()) {
                    insertPs.setLong(1, newSessionId);
                    insertPs.setTimestamp(2, rs.getTimestamp("timestamp"));
                    insertPs.setString(3, rs.getString("node_id"));
                    insertPs.setString(4, rs.getString("label"));
                    insertPs.setString(5, rs.getString("action_name"));
                    insertPs.setString(6, rs.getString("level"));
                    insertPs.setString(7, rs.getString("message"));

                    int exitCode = rs.getInt("exit_code");
                    if (rs.wasNull()) {
                        insertPs.setNull(8, Types.INTEGER);
                    } else {
                        insertPs.setInt(8, exitCode);
                    }

                    long durationMs = rs.getLong("duration_ms");
                    if (rs.wasNull()) {
                        insertPs.setNull(9, Types.BIGINT);
                    } else {
                        insertPs.setLong(9, durationMs);
                    }

                    insertPs.executeUpdate();
                    count++;
                }
            }
        }
        return count;
    }

    private static int copyNodeResults(Connection source, Connection target,
                                       long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String insertSql = """
            INSERT INTO node_results (session_id, node_id, status, reason)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement selectPs =
                     source.prepareStatement("SELECT * FROM node_results WHERE session_id = ?");
             PreparedStatement insertPs = target.prepareStatement(insertSql)) {
            selectPs.setLong(1, oldSessionId);
            try (ResultSet rs = selectPs.executeQuery()) {
                while (rs.next()) {
                    insertPs.setLong(1, newSessionId);
                    insertPs.setString(2, rs.getString("node_id"));
                    insertPs.setString(3, rs.getString("status"));
                    insertPs.setString(4, rs.getString("reason"));
                    insertPs.executeUpdate();
                    count++;
                }
            }
        }
        return count;
    }

    /** @return whether a database file exists at this path */
    public static boolean exists(Path database) {
        return new File(database.toAbsolutePath() + ".mv.db").isFile();
    }
}
