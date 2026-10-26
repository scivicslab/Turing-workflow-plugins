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
 * Combines multiple H2 log databases into a single target database, deduplicating sessions based on their workflow
 * name and start time.
 *
 * <p>The class provides the following operations:
 * <ul>
 * <li>{@link #scan(Path, List)} finds log databases in a directory, returning their paths without the {@code .mv.db}
 * suffix.</li>
 * <li>{@link #count(Path)} returns a {@link Count} describing the number of sessions, logs, and node results in a
 * source, or a problem message if the source cannot be read.</li>
 * <li>{@link #merge(Path, List)} copies sessions and their associated logs and node results from several sources into
 * a target, skipping sessions already present in the target.</li>
 * <li>{@link #exists(Path)} checks if a database file exists at the given path.</li>
 * </ul>
 *
 * <p>Every method is static and holds no state between calls. One merge's counters live in the {@link Report} it
 * returns.
 *
 * <p>A session is considered a duplicate if its workflow name and start time are identical.
 *
 * <p>The target database is created if it does not exist. If a source database is also listed as the target, it is
 * skipped.
 *
 * @author devteam@scivicslab.com
 * @since 4.1.0
 */
public final class LogMerger {

    private LogMerger() {
    }

    /**
     * Records the results of a single merge operation.
     *
     * @param sessionsMerged the number of sessions copied from sources to the target
     * @param sessionsSkipped the number of sessions already present in the target
     * @param logsMerged the number of log rows copied
     * @param nodeResultsMerged the number of node-result rows copied
     * @param problems a list of error messages, one per source that could not be read; empty if all sources were read
     * successfully
     */
    public record Report(int sessionsMerged, int sessionsSkipped, int logsMerged,
                         int nodeResultsMerged, List<String> problems) {}

    /**
     * Represents the contents of a single log database.
     *
     * @param database the path to the database, with the {@code .mv.db} extension removed
     * @param sessions the number of sessions it holds
     * @param logs the number of log rows it holds
     * @param nodeResults the number of node-result rows it holds
     * @param problem a description of what went wrong reading it, or an empty string if it was read successfully
     */
    public record Count(Path database, int sessions, int logs, int nodeResults, String problem) {}

    /**
     * Returns a list of paths to log databases found within a directory.
     *
     * @param directory the directory to walk
     * @param namePrefixes a list of prefixes to filter filenames; an empty list or null keeps every {@code .mv.db}
     * file found
     *
     * @return a list of paths to the databases, with the {@code .mv.db} suffix removed
     * @throws IOException if the directory cannot be walked
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
     * Returns true if the fileName starts with any of the provided prefixes.
     *
     * If the prefixes list is empty or null, the method returns true.
     *
     * @param fileName the name of the file to check, including its extension
     * @return true if the name matches one of the prefixes or if no prefixes were specified, false otherwise
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

    /** Returns a path to the database with the {@code .mv.db} extension removed. */
    private static Path withoutSuffix(Path databaseFile) {
        String s = databaseFile.toString();
        return Path.of(s.substring(0, s.length() - ".mv.db".length()));
    }

    /**
     * Returns a {@link Count} that describes the number of sessions, logs, and node results held in a database.
     *
     * @param source the source, as a path with no {@code .mv.db} extension
     * @return a {@link Count} containing the counts for each table, or a {@link Count} where all counts are 0 and the
     * problem field contains the error message if the source cannot be read
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
     * Copies all sessions and their associated logs and node results from the sources into the target.
     *
     * <p>A session is considered a duplicate and skipped if a session with the same workflow name and start time
     * already exists in the target. The target is skipped if it is included in the sources list.</p>
     *
     * <p>Returns a {@link Report} detailing the number of sessions, logs, and node results merged or skipped, along
     * with a list of error messages for any sources that could not be read.</p>
     *
     * @param target the database to write to, as a path with no {@code .mv.db} extension; created if it does not exist
     * @param sources the databases to read from, as paths with no {@code .mv.db} extension
     * @return a report containing the counts of merged and skipped items and any error messages
     * @throws SQLException if the target database cannot be opened or written to
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
     * Returns a connection to the specified database with auto-commit disabled.
     *
     * @param database the database, as a path with no {@code .mv.db} extension
     * @param compress if true, enables MVStore compression for the target database
     * @return a {@link Connection} to the database
     * @throws SQLException if the database cannot be opened
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

    /**
     * Sets up the target database tables and adds a column to the {@code sessions} table to record the name of the
     * source database from which a session was copied.
     */
    private static void initializeSchema(Connection conn) throws SQLException {
        H2LogStore.initSchema(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source_db VARCHAR(255)");
        }
        conn.commit();
    }

    /**
     * Returns a set of keys for sessions already present in the database.
     *
     * <p>Each key is a string combining the workflow name and the session start time, ensuring that a session is not
     * copied more than once during a merge.</p>
     *
     * @param conn the connection to the database to query
     * @return a set of strings, where each string is a unique identifier for a session in the database
     * @throws SQLException if a database access error occurs
     */
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
     * Returns a string that uniquely identifies a session by combining its name and start time.
     *
     * <p>The name alone is not sufficient for uniqueness, as many different sessions may share the same name; the
     * start time is included to the microsecond to ensure that no two sessions share a key.
     *
     * @param workflowName the name of the workflow, or null if the name is not set
     * @param startedAt the timestamp of the session start, or null if the start time is not set
     * @return a string consisting of the workflow name and the start time, separated by a pipe character
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

    /**
     * Copies the sessions, logs, and node results from a single source database into the target database, skipping any
     * sessions that are already present in the target.
     *
     * <p>A session is considered already present if its workflow name and start time match a session in the target.
     * For every new session found, it copies the session record, all associated log entries, and all associated node
     * results. It also records the source database name in the new session's {@code source_db} column.</p>
     *
     * @param source the source database connection
     * @param target the target database connection
     * @param existing a set of keys for sessions already present in the target to prevent duplicates
     * @param sourceName the name of the source database to be recorded in the {@code source_db} column
     * @return a {@link Report} containing the number of sessions, logs, and node results merged, and the number of
     * sessions skipped
     *
     * @throws SQLException if a database access error occurs during the copy process
     */
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

    /** Returns the set of column labels from the query results, converted to upper case. */
    private static Set<String> columnsOf(ResultSet rs) throws SQLException {
        Set<String> names = new HashSet<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnLabel(i).toUpperCase());
        }
        return names;
    }

    /**
     * Returns the value of the specified column, or {@code null} if the column is not present in the source.
     *
     * @param rs the {@link ResultSet} to read from
     * @param columns the set of column names available in the current result set
     * @param name the name of the column to retrieve
     * @return the string value of the column, or {@code null} if the name is not in the {@code columns} set
     */
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

    /**
     * Returns true if the H2 database file exists at the given path.
     *
     * @param database the path to the database, as a path with no {@code .mv.db} extension
     */
    public static boolean exists(Path database) {
        return new File(database.toAbsolutePath() + ".mv.db").isFile();
    }
}
