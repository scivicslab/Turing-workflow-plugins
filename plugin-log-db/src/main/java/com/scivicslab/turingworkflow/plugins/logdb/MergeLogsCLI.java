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
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;

/**
 * CLI subcommand to merge scattered log databases into a single database.
 *
 * <p>The copying itself lives in {@link LogMerger}, which a long-running program can call without
 * a terminal. What remains here is the terminal: parsing the options, choosing the sources, and
 * printing what happened.</p>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class MergeLogsCLI {

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
                .longOpt("name-prefix")
                .hasArg(true).argName("prefix[,prefix...]")
                .desc("Keep only scanned files whose name starts with one of these prefixes "
                        + "(e.g. chat-ui-iolog-). Without this, --scan takes every .mv.db it finds, "
                        + "including databases that belong to other programs.")
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
                .desc("Skip sessions that already exist in target (always on)")
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
        Path targetDb = Path.of(cl.getOptionValue("target"));
        String scanPath = cl.getOptionValue("scan");
        boolean dryRun = cl.hasOption("dry-run");
        boolean verbose = cl.hasOption("v");
        List<String> namePrefixes = parseNamePrefixes(cl.getOptionValue("name-prefix"));

        List<Path> sources = collectSourceDatabases(cl.getArgs(), scanPath, namePrefixes);
        if (sources.isEmpty()) {
            System.err.println("No source databases found.");
            System.err.println("Use --scan <dir> to scan for databases, or specify source files directly.");
            System.exit(1);
        }

        String targetPath = targetDb.toAbsolutePath().toString();
        sources.removeIf(p -> p.toAbsolutePath().toString().equals(targetPath));
        if (sources.isEmpty()) {
            System.err.println("No source databases to merge (target was the only database found).");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("Log Database Merge");
        System.out.println("=".repeat(60));
        System.out.println("Target: " + targetPath + ".mv.db");
        System.out.println("Sources: " + sources.size() + " database(s)");
        if (verbose) {
            for (Path source : sources) {
                System.out.println("  - " + source.toAbsolutePath() + ".mv.db");
            }
        }
        System.out.println("-".repeat(60));

        if (dryRun) {
            System.out.println("[DRY-RUN MODE - No changes will be made]");
            System.out.println();
            printCounts(sources);
            return;
        }

        try {
            LogMerger.Report report = LogMerger.merge(targetDb, sources);
            for (String problem : report.problems()) {
                System.err.println("Error reading " + problem);
            }
            System.out.println("-".repeat(60));
            System.out.println("Merge completed:");
            System.out.println("  Sessions merged:     " + report.sessionsMerged());
            System.out.println("  Sessions skipped:    " + report.sessionsSkipped() + " (duplicates)");
            System.out.println("  Log entries merged:  " + report.logsMerged());
            System.out.println("  Node results merged: " + report.nodeResultsMerged());
            System.out.println("=".repeat(60));
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /**
     * Splits the {@code --name-prefix} value into individual prefixes.
     *
     * @param value the raw option value, comma separated, or {@code null} when the option is absent
     * @return the prefixes, or an empty list meaning "keep every scanned file"
     */
    static List<String> parseNamePrefixes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> prefixes = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed);
            }
        }
        return prefixes;
    }

    /**
     * Chooses the databases to read: the ones named on the command line, then the ones found under
     * {@code --scan}.
     *
     * <p>The name prefixes apply to the scan only. A database named on the command line was chosen
     * by whoever typed it.</p>
     *
     * @param named        the positional arguments, as paths with no {@code .mv.db} extension
     * @param scanPath     the directory to walk, or null
     * @param namePrefixes the prefixes a scanned file's name must start with; empty accepts all
     * @return the sources, in the order they were chosen, without duplicates
     */
    private List<Path> collectSourceDatabases(String[] named, String scanPath,
                                              List<String> namePrefixes) {
        List<Path> sources = new ArrayList<>();
        for (String arg : named) {
            Path source = Path.of(arg);
            if (LogMerger.exists(source)) {
                sources.add(source);
            } else {
                System.err.println("Warning: Database not found: "
                        + source.toAbsolutePath() + ".mv.db");
            }
        }
        if (scanPath == null) {
            return sources;
        }
        File scanDir = new File(scanPath);
        if (!scanDir.isDirectory()) {
            System.err.println("Warning: Not a directory: " + scanPath);
            return sources;
        }
        try {
            for (Path found : LogMerger.scan(scanDir.toPath(), namePrefixes)) {
                if (!sources.contains(found)) {
                    sources.add(found);
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to scan directory: " + e.getMessage());
        }
        return sources;
    }

    /** Prints one line per source with what it holds, and a total. */
    private void printCounts(List<Path> sources) {
        int sessions = 0;
        int logs = 0;
        int nodeResults = 0;
        for (Path source : sources) {
            LogMerger.Count count = LogMerger.count(source);
            if (!count.problem().isEmpty()) {
                System.err.println("Error reading " + source + ": " + count.problem());
                continue;
            }
            sessions += count.sessions();
            logs += count.logs();
            nodeResults += count.nodeResults();
            System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
                    truncate(source.getFileName().toString(), 50),
                    count.sessions(), count.logs(), count.nodeResults());
        }
        System.out.println("-".repeat(60));
        System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
                "TOTAL", sessions, logs, nodeResults);
        System.out.println("=".repeat(60));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }
}
