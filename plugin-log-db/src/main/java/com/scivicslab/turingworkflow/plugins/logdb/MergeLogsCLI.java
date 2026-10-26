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
 * Combines multiple H2 log databases into a single target database via a command-line interface.
 *
 * <p>The command-line tool takes a target database path and one or more source databases—either specified directly or
 * found by scanning a directory—and merges their sessions, logs, and node results into the target. It skips sessions
 * that already exist in the target to prevent duplicates.</p>
 *
 * <ul><li>Registers the {@code log-merge} command with a command repository.</li><li>Executes the merge process,
 * printing a summary of sessions and entries merged or errors encountered.</li></ul>
 *
 * <p>A dry run shows the counts of sessions and logs in each source without modifying any files.</p>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class MergeLogsCLI {

    /** Adds the {@code log-merge} command to the given repository. */
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
     * Performs the log-merge command by parsing the provided command line arguments and printing the results or errors
     * to the terminal.
     *
     * @param cl the parsed command line arguments containing options like --target, --scan, --name-prefix, and
     * --dry-run
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
     * Splits a comma-separated string into a list of individual prefixes.
     *
     * @param value the raw option value, which is a comma-separated string of prefixes, or {@code null} if the option
     * is absent
     *
     * @return a list of non-empty, trimmed prefixes, or an empty list if the input is null or blank
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
     * Returns a list of paths to the H2 database files that will be merged.
     *
     * <p>The list includes the files specified directly as positional arguments, followed by any files found by
     * scanning the directory at {@code scanPath} that match the provided {@code namePrefixes}. A file named via a
     * positional argument is included regardless of its name, while the name prefixes only restrict files discovered
     * during the scan. The resulting list contains no duplicates and maintains the order of discovery.</p>
     *
     * @param named the positional arguments, each a path without the {@code .mv.db} extension
     * @param scanPath the directory to scan, or null to skip scanning
     * @param namePrefixes the prefixes a scanned file's name must start with; an empty list accepts all scanned files
     * @return a list of paths to the identified database files
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

    /**
     * Prints a summary of the source databases to standard output.
     *
     * <p>The method prints one line per source containing the filename and the number of sessions, logs, and node
     * results it holds, followed by a final line showing the total counts across all sources. If a source cannot be
     * read, an error message is printed to standard error for that specific file and its counts are not included in
     * the totals.</p>
     *
     * @param sources the list of paths to the source databases to be summarized
     */
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
