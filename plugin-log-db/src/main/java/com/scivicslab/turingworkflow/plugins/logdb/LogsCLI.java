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
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;

/**
 * Definition of the {@code log-search} subcommand: searches workflow execution logs stored
 * in the plugin's H2 database.
 *
 * <p>{@link #registerCommand(CommandRepository)} builds the Commons CLI options and registers
 * the command into the shared {@link CommandRepository}. It is invoked by
 * {@link LogDbCliPlugin#registerCommands(CommandRepository)}. The execution logic reads from
 * the plugin's own {@link H2LogReader}.</p>
 *
 * <p>Ported from the legacy actor-IaC {@code LogsCLI}, retargeted onto this plugin's store
 * classes.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.5.0
 */
public class LogsCLI {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /**
     * Registers the "log-search" command with the given repository.
     *
     * @param repo the command repository
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder()
                .longOpt("db")
                .hasArg(true).argName("path")
                .desc("H2 database path (without .mv.db extension)")
                .required(true)
                .build());
        opts.addOption(Option.builder()
                .longOpt("server")
                .hasArg(true).argName("host:port")
                .desc("H2 log server address (host:port)")
                .build());
        opts.addOption(Option.builder("s")
                .longOpt("session")
                .hasArg(true).argName("id")
                .desc("Session ID to query (default: latest session)")
                .build());
        opts.addOption(Option.builder("n")
                .longOpt("node")
                .hasArg(true).argName("id")
                .desc("Filter logs by node ID")
                .build());
        opts.addOption(Option.builder()
                .longOpt("level")
                .hasArg(true).argName("level")
                .desc("Minimum log level to show (DEBUG, INFO, WARN, ERROR; default: DEBUG)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("summary")
                .desc("Show session summary only")
                .build());
        opts.addOption(Option.builder()
                .longOpt("list")
                .desc("List recent sessions")
                .build());
        opts.addOption(Option.builder("w")
                .longOpt("workflow")
                .hasArg(true).argName("name")
                .desc("Filter sessions by workflow name")
                .build());
        opts.addOption(Option.builder("o")
                .longOpt("overlay")
                .hasArg(true).argName("name")
                .desc("Filter sessions by overlay name")
                .build());
        opts.addOption(Option.builder("i")
                .longOpt("inventory")
                .hasArg(true).argName("name")
                .desc("Filter sessions by inventory name")
                .build());
        opts.addOption(Option.builder()
                .longOpt("after")
                .hasArg(true).argName("datetime")
                .desc("Filter sessions started after this time (ISO format: YYYY-MM-DDTHH:mm:ss)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("since")
                .hasArg(true).argName("duration")
                .desc("Filter sessions started within the specified duration (e.g., 12h, 1d, 3d, 1w)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("ended-since")
                .hasArg(true).argName("duration")
                .desc("Filter sessions ended within the specified duration (e.g., 1h, 12h, 1d)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("limit")
                .hasArg(true).argName("n")
                .desc("Maximum number of lines to show (default: unlimited)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("list-nodes")
                .desc("List all nodes in the specified session")
                .build());

        repo.addCommand("Log", "log-search", opts, "Search workflow execution logs from H2 database.",
                cl -> new LogsCLI().execute(cl));
    }

    /**
     * Executes the log-search command.
     *
     * @param cl the parsed command line
     */
    public void execute(CommandLine cl) {
        File dbPath = new File(cl.getOptionValue("db"));
        String server = cl.getOptionValue("server");
        String sessionStr = cl.getOptionValue("s");
        Long sessionId = sessionStr != null ? Long.parseLong(sessionStr) : null;
        String nodeId = cl.getOptionValue("n");
        LogLevel minLevel = LogLevel.valueOf(cl.getOptionValue("level", "DEBUG"));
        boolean summaryOnly = cl.hasOption("summary");
        boolean listSessions = cl.hasOption("list");
        String workflowFilter = cl.getOptionValue("w");
        String overlayFilter = cl.getOptionValue("o");
        String inventoryFilter = cl.getOptionValue("i");
        String startedAfter = cl.getOptionValue("after");
        String since = cl.getOptionValue("since");
        String endedSince = cl.getOptionValue("ended-since");
        int limit = Integer.parseInt(cl.getOptionValue("limit", "0"));
        boolean listNodes = cl.hasOption("list-nodes");

        try (H2LogReader reader = createReader(dbPath, server)) {
            int exitCode;
            if (listSessions) {
                exitCode = listRecentSessions(reader, workflowFilter, overlayFilter,
                        inventoryFilter, startedAfter, since, endedSince, limit);
            } else {
                long targetSession = sessionId != null ? sessionId : reader.getLatestSessionId();
                if (targetSession < 0) {
                    System.err.println("No sessions found in database.");
                    System.exit(1);
                    return;
                }

                if (listNodes) {
                    exitCode = listNodesInSession(reader, targetSession);
                } else if (summaryOnly) {
                    exitCode = showSummary(reader, targetSession);
                } else {
                    exitCode = showLogs(reader, targetSession, nodeId, minLevel, limit);
                }
            }

            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private H2LogReader createReader(File dbPath, String server) throws SQLException {
        if (server != null && !server.isBlank()) {
            String[] parts = server.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 29090;
            return new H2LogReader(host, port, dbPath.getAbsolutePath());
        } else {
            return new H2LogReader(dbPath.toPath());
        }
    }

    private int listRecentSessions(H2LogReader reader, String workflowFilter,
            String overlayFilter, String inventoryFilter, String startedAfter,
            String since, String endedSince, int limit) {
        // Parse start time filter (--since takes precedence over --after)
        LocalDateTime startedAfterTime = null;
        if (since != null) {
            startedAfterTime = parseSince(since);
            if (startedAfterTime == null) {
                System.err.println("Invalid --since format. Use: 12h, 1d, 3d, 1w (h=hours, d=days, w=weeks)");
                return 1;
            }
        } else if (startedAfter != null) {
            try {
                startedAfterTime = LocalDateTime.parse(startedAfter);
            } catch (Exception e) {
                System.err.println("Invalid date format. Use ISO format: YYYY-MM-DDTHH:mm:ss");
                return 1;
            }
        }

        // Parse end time filter
        LocalDateTime endedAfterTime = null;
        if (endedSince != null) {
            endedAfterTime = parseSince(endedSince);
            if (endedAfterTime == null) {
                System.err.println("Invalid --ended-since format. Use: 1h, 12h, 1d, 3d (h=hours, d=days, w=weeks)");
                return 1;
            }
        }

        // Apply filters if any are specified. limit <= 0 means "no limit"; the SQL LIMIT
        // clause cannot express that, so substitute a large bound (LIMIT 0 returns no rows).
        int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<SessionSummary> sessions;
        if (workflowFilter != null || overlayFilter != null || inventoryFilter != null
                || startedAfterTime != null || endedAfterTime != null) {
            sessions = reader.listSessionsFiltered(workflowFilter, overlayFilter, inventoryFilter,
                    startedAfterTime, endedAfterTime, effectiveLimit);
        } else {
            sessions = reader.listSessions(effectiveLimit);
        }

        if (sessions.isEmpty()) {
            System.out.println("No sessions found.");
            return 0;
        }

        System.out.println("Sessions:");
        System.out.println("=".repeat(80));
        for (SessionSummary summary : sessions) {
            System.out.printf("#%-4d %-30s %-10s%n",
                    summary.getSessionId(),
                    summary.getWorkflowName(),
                    summary.getStatus());
            if (summary.getOverlayName() != null) {
                System.out.printf("      Overlay:   %s%n", summary.getOverlayName());
            }
            if (summary.getInventoryName() != null) {
                System.out.printf("      Inventory: %s%n", summary.getInventoryName());
            }
            System.out.printf("      Started:   %s%n", formatTimestamp(summary.getStartedAt()));
            if (summary.getEndedAt() != null) {
                System.out.printf("      Ended:     %s%n", formatTimestamp(summary.getEndedAt()));
            }
            if (summary.getCwd() != null) {
                System.out.printf("      CWD:       %s%n", summary.getCwd());
            }
            if (summary.getGitCommit() != null) {
                String gitInfo = summary.getGitCommit();
                if (summary.getGitBranch() != null) {
                    gitInfo += " (" + summary.getGitBranch() + ")";
                }
                System.out.printf("      Git:       %s%n", gitInfo);
            }
            if (summary.getCommandLine() != null) {
                System.out.printf("      Command:   %s%n", summary.getCommandLine());
            }
            if (summary.getPluginVersion() != null) {
                String versionInfo = summary.getPluginVersion();
                if (summary.getPluginCommit() != null) {
                    versionInfo += " (commit: " + summary.getPluginCommit() + ")";
                }
                System.out.printf("      Plugin:    %s%n", versionInfo);
            }
            System.out.println("-".repeat(80));
        }
        return 0;
    }

    private int listNodesInSession(H2LogReader reader, long targetSession) {
        List<H2LogReader.NodeInfo> nodes = reader.getNodesInSession(targetSession);
        if (nodes.isEmpty()) {
            System.out.println("No nodes found in session #" + targetSession);
            return 0;
        }

        SessionSummary summary = reader.getSummary(targetSession);
        System.out.println("Nodes in session #" + targetSession + " (" + summary.getWorkflowName() + "):");
        System.out.println("=".repeat(70));
        System.out.printf("%-30s %-10s %-10s%n", "NODE_ID", "STATUS", "LOG_LINES");
        System.out.println("-".repeat(70));
        for (H2LogReader.NodeInfo node : nodes) {
            System.out.printf("%-30s %-10s %-10d%n",
                    node.nodeId(),
                    node.status() != null ? node.status() : "-",
                    node.logCount());
        }
        System.out.println("=".repeat(70));
        System.out.println("Total: " + nodes.size() + " nodes");
        return 0;
    }

    private int showSummary(H2LogReader reader, long targetSession) {
        SessionSummary summary = reader.getSummary(targetSession);
        if (summary == null) {
            System.err.println("Session not found: " + targetSession);
            return 1;
        }
        System.out.println(summary);
        return 0;
    }

    private int showLogs(H2LogReader reader, long targetSession, String nodeId,
                         LogLevel minLevel, int limit) {
        List<LogEntry> logs;

        if (nodeId != null) {
            logs = reader.getLogsByNode(targetSession, nodeId);
            System.out.println("Logs for node: " + nodeId);
        } else {
            logs = reader.getLogsByLevel(targetSession, minLevel);
            System.out.println("Logs (level >= " + minLevel + "):");
        }

        System.out.println("=".repeat(80));

        int count = 0;
        for (LogEntry entry : logs) {
            if (limit > 0 && count >= limit) {
                System.out.println("... (truncated at " + limit + " lines, use --limit to change)");
                break;
            }

            String levelColor = getLevelPrefix(entry.getLevel());
            System.out.printf("%s[%s] %-5s [%s] %s%s%n",
                    levelColor,
                    formatTimestamp(entry.getTimestamp()),
                    entry.getLevel(),
                    entry.getNodeId(),
                    entry.getMessage(),
                    "[0m"); // Reset color

            count++;
        }

        System.out.println("=".repeat(80));
        System.out.println("Total: " + logs.size() + " lines");

        return 0;
    }

    private String getLevelPrefix(LogLevel level) {
        return switch (level) {
            case ERROR -> "[31m"; // Red
            case WARN -> "[33m";  // Yellow
            case INFO -> "[32m";  // Green
            case DEBUG -> "[36m"; // Cyan
            default -> "[0m";     // Default (covers any additional levels)
        };
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "N/A";
        }
        return timestamp.atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
    }

    private LocalDateTime parseSince(String sinceStr) {
        if (sinceStr == null || sinceStr.isEmpty()) {
            return null;
        }

        try {
            String numPart = sinceStr.substring(0, sinceStr.length() - 1);
            char unit = Character.toLowerCase(sinceStr.charAt(sinceStr.length() - 1));
            long amount = Long.parseLong(numPart);

            LocalDateTime now = LocalDateTime.now();
            return switch (unit) {
                case 'h' -> now.minusHours(amount);
                case 'd' -> now.minusDays(amount);
                case 'w' -> now.minusWeeks(amount);
                case 'm' -> now.minusMinutes(amount);
                default -> null;
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }
}
