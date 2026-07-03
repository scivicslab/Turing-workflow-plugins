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
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;

/**
 * CLI subcommand to merge scattered log databases into a single database.
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class MergeLogsCLI {

    /** Statistics for reporting */
    private int totalSessions = 0;
    private int totalLogs = 0;
    private int totalNodeResults = 0;
    private int skippedSessions = 0;

    /**
     * Registers the "log-merge" command with the given repository.
     *
     * @param repo the command repository
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder()
                .longOpt("target")
                .hasArg(true).argName("path")
                .desc("Target database file path (without .mv.db extension)")
                .required(true)
                .build());
        opts.addOption(Option.builder()
                .longOpt("scan")
                .hasArg(true).argName("dir")
                .desc("Directory to scan for .mv.db files (recursive)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("dry-run")
                .desc("Show what would be merged without actually merging")
                .build());
        opts.addOption(Option.builder("v")
                .longOpt("verbose")
                .desc("Enable verbose output")
                .build());
        opts.addOption(Option.builder()
                .longOpt("skip-duplicates")
                .desc("Skip sessions that already exist in target (default: true)")
                .build());

        repo.addCommand("Log", "log-merge", opts, "Merge scattered log databases into a single database.",
                cl -> new MergeLogsCLI().execute(cl));
    }

    /**
     * Executes the log-merge command.
     *
     * @param cl the parsed command line
     */
    public void execute(CommandLine cl) {
        File targetDb = new File(cl.getOptionValue("target"));
        String scanPath = cl.getOptionValue("scan");
        File scanDir = scanPath != null ? new File(scanPath) : null;
        boolean dryRun = cl.hasOption("dry-run");
        boolean verbose = cl.hasOption("v");
        boolean skipDuplicates = !cl.hasOption("skip-duplicates") || true; // default true

        // Positional arguments as source databases
        List<File> sourceDbs = new ArrayList<>();
        for (String arg : cl.getArgs()) {
            sourceDbs.add(new File(arg));
        }

        // Collect source databases
        List<File> allSources = collectSourceDatabases(sourceDbs, scanDir);
        if (allSources.isEmpty()) {
            System.err.println("No source databases found.");
            System.err.println("Use --scan <dir> to scan for databases, or specify source files directly.");
            System.exit(1);
        }

        // Filter out target from sources if present
        String targetPath = targetDb.getAbsolutePath();
        allSources.removeIf(f -> f.getAbsolutePath().equals(targetPath));

        if (allSources.isEmpty()) {
            System.err.println("No source databases to merge (target was the only database found).");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("Log Database Merge");
        System.out.println("=".repeat(60));
        System.out.println("Target: " + targetDb.getAbsolutePath() + ".mv.db");
        System.out.println("Sources: " + allSources.size() + " database(s)");
        if (verbose) {
            for (File source : allSources) {
                System.out.println("  - " + source.getAbsolutePath() + ".mv.db");
            }
        }
        System.out.println("-".repeat(60));

        if (dryRun) {
            System.out.println("[DRY-RUN MODE - No changes will be made]");
            System.out.println();
            int exitCode = dryRunAnalysis(allSources);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }

        try {
            int exitCode = performMerge(allSources, targetDb, verbose, skipDuplicates);
            if (exitCode != 0) System.exit(exitCode);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private List<File> collectSourceDatabases(List<File> sourceDbs, File scanDir) {
        List<File> sources = new ArrayList<>();

        for (File source : sourceDbs) {
            File dbFile = new File(source.getAbsolutePath() + ".mv.db");
            if (dbFile.exists()) {
                sources.add(source);
            } else {
                System.err.println("Warning: Database not found: " + dbFile.getAbsolutePath());
            }
        }

        if (scanDir != null) {
            if (!scanDir.isDirectory()) {
                System.err.println("Warning: Not a directory: " + scanDir);
            } else {
                try (Stream<Path> paths = Files.walk(scanDir.toPath())) {
                    paths.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".mv.db"))
                         .map(p -> {
                             String path = p.toString();
                             return new File(path.substring(0, path.length() - 6));
                         })
                         .filter(f -> !sources.contains(f))
                         .forEach(sources::add);
                } catch (IOException e) {
                    System.err.println("Warning: Failed to scan directory: " + e.getMessage());
                }
            }
        }

        return sources;
    }

    private int dryRunAnalysis(List<File> sources) {
        int totalSes = 0;
        int totalLog = 0;
        int totalNr = 0;

        for (File source : sources) {
            try (Connection conn = openDatabase(source)) {
                if (!tableExists(conn, "sessions")) {
                    System.out.printf("%-50s (empty - no sessions table)%n",
                        truncate(source.getName(), 50));
                    continue;
                }

                int sessions = countRows(conn, "sessions");
                int logs = tableExists(conn, "logs") ? countRows(conn, "logs") : 0;
                int nodeResults = tableExists(conn, "node_results") ? countRows(conn, "node_results") : 0;

                totalSes += sessions;
                totalLog += logs;
                totalNr += nodeResults;

                System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
                    truncate(source.getName(), 50), sessions, logs, nodeResults);

            } catch (SQLException e) {
                System.err.println("Error reading " + source + ": " + e.getMessage());
            }
        }

        System.out.println("-".repeat(60));
        System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
            "TOTAL", totalSes, totalLog, totalNr);
        System.out.println("=".repeat(60));

        return 0;
    }

    private int performMerge(List<File> sources, File targetDb, boolean verbose,
                             boolean skipDuplicates) throws SQLException {
        try (Connection targetConn = openDatabase(targetDb)) {
            initializeSchema(targetConn);

            Set<String> existingSessions = skipDuplicates ? loadExistingSessions(targetConn) : Set.of();

            for (File source : sources) {
                System.out.println("Merging: " + source.getName() + ".mv.db");
                try (Connection sourceConn = openDatabase(source)) {
                    mergeDatabase(sourceConn, targetConn, existingSessions, source.getName(), verbose);
                } catch (SQLException e) {
                    System.err.println("  Error: " + e.getMessage());
                    if (verbose) {
                        e.printStackTrace();
                    }
                }
            }

            targetConn.commit();
        }

        // Print summary
        System.out.println("-".repeat(60));
        System.out.println("Merge completed:");
        System.out.println("  Sessions merged:     " + totalSessions);
        System.out.println("  Sessions skipped:    " + skippedSessions + " (duplicates)");
        System.out.println("  Log entries merged:  " + totalLogs);
        System.out.println("  Node results merged: " + totalNodeResults);
        System.out.println("=".repeat(60));

        return 0;
    }

    private Connection openDatabase(File dbPath) throws SQLException {
        String url = "jdbc:h2:" + dbPath.getAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(false);
        return conn;
    }

    private void initializeSchema(Connection conn) throws SQLException {
        H2LogStore.initSchema(conn);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source_db VARCHAR(255)");
        } catch (SQLException e) {
            // Column might already exist
        }

        conn.commit();
    }

    private Set<String> loadExistingSessions(Connection conn) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT workflow_name, started_at FROM sessions")) {
            while (rs.next()) {
                String key = makeSessionKey(rs.getString("workflow_name"), rs.getTimestamp("started_at"));
                existing.add(key);
            }
        }
        return existing;
    }

    private String makeSessionKey(String workflowName, Timestamp startedAt) {
        return (workflowName != null ? workflowName : "") + "|" +
               (startedAt != null ? startedAt.toString() : "");
    }

    private boolean tableExists(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT 1 FROM " + tableName + " WHERE 1=0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void mergeDatabase(Connection source, Connection target,
                               Set<String> existingSessions, String sourceName,
                               boolean verbose) throws SQLException {
        if (!tableExists(source, "sessions")) {
            if (verbose) {
                System.out.println("  Skipping (no sessions table)");
            }
            return;
        }

        try (Statement stmt = source.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sessions ORDER BY id")) {

            while (rs.next()) {
                long oldSessionId = rs.getLong("id");
                String workflowName = rs.getString("workflow_name");
                Timestamp startedAt = rs.getTimestamp("started_at");

                String sessionKey = makeSessionKey(workflowName, startedAt);
                if (existingSessions.contains(sessionKey)) {
                    if (verbose) {
                        System.out.println("  Skipping duplicate session: " + workflowName + " at " + startedAt);
                    }
                    skippedSessions++;
                    continue;
                }

                long newSessionId = insertSession(target, rs, sourceName);
                existingSessions.add(sessionKey);
                totalSessions++;

                int logCount = copyLogs(source, target, oldSessionId, newSessionId);
                totalLogs += logCount;

                int nrCount = copyNodeResults(source, target, oldSessionId, newSessionId);
                totalNodeResults += nrCount;

                if (verbose) {
                    System.out.printf("  Session %d -> %d: %s (%d logs, %d node_results)%n",
                        oldSessionId, newSessionId, workflowName, logCount, nrCount);
                }
            }
        }
    }

    private long insertSession(Connection target, ResultSet rs, String sourceName) throws SQLException {
        String sql = """
            INSERT INTO sessions (started_at, ended_at, workflow_name, overlay_name,
                                  inventory_name, node_count, status, source_db)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = target.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, rs.getTimestamp("started_at"));
            ps.setTimestamp(2, rs.getTimestamp("ended_at"));
            ps.setString(3, rs.getString("workflow_name"));
            ps.setString(4, rs.getString("overlay_name"));
            ps.setString(5, rs.getString("inventory_name"));
            ps.setInt(6, rs.getInt("node_count"));
            ps.setString(7, rs.getString("status"));
            ps.setString(8, sourceName);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to get generated session ID");
    }

    private int copyLogs(Connection source, Connection target,
                         long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String selectSql = "SELECT * FROM logs WHERE session_id = ?";
        String insertSql = """
            INSERT INTO logs (session_id, timestamp, node_id, label, action_name,
                             level, message, exit_code, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement selectPs = source.prepareStatement(selectSql);
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

    private int copyNodeResults(Connection source, Connection target,
                                long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String selectSql = "SELECT * FROM node_results WHERE session_id = ?";
        String insertSql = """
            INSERT INTO node_results (session_id, node_id, status, reason)
            VALUES (?, ?, ?, ?)
            """;

        try (PreparedStatement selectPs = source.prepareStatement(selectSql);
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

    private int countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }
}
