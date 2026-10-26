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
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.scivicslab.pluggablecli.CommandRepository;

/**
 * Deletes the H2 log database files from the file system.
 *
 * <p>The command takes a database path and an optional HTTP port to check for a running log server, and returns a
 * success message or an error if the files cannot be deleted.
 *
 * <ul>
 * <li>`--db <path>`: The path to the H2 database (without the .mv.db extension).</li>
 * <li>`--http-port <port>`: The port to check for a running log server to prevent deleting active databases (default:
 * 29091).</li>
 * <li>`-f, --force`: If present, skips the check for a running log server.</li>
 * </ul>
 *
 * <p>If the log server is detected running on the specified port and {@code --force} is not provided, the command
 * fails with an error message. If the files exist but cannot be deleted, the command exits with a failure.
 *
 * <pre>{@code
 * db-clear --db ./workflow-logs/mylog --force
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 3.0.0
 */
public class DbClearCLI {

    /**
     * Adds the {@code db-clear} command to the given command repository.
     *
     * <p>The command allows deleting the H2 log database files by specifying the database path. It requires the {@code
     * --db} option to provide the path without the {@code .mv.db} extension. If the log server is detected running on
     * the specified {@code --http-port} (default 29091), the command fails unless the {@code --force} flag is used.
     *
     * @param repo the repository where the command is registered
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
                .longOpt("http-port")
                .hasArg(true).argName("port")
                .desc("HTTP port to check for running log server (default: 29091)")
                .build());
        opts.addOption(Option.builder("f")
                .longOpt("force")
                .desc("Force clear without checking if log server is running")
                .build());

        repo.addCommand("Log", "db-clear", opts, "Clear (delete) the H2 log database files.",
                cl -> new DbClearCLI().execute(cl));
    }

    /**
     * Deletes the H2 log database files at the specified path.
     *
     * If the {@code force} option is not present, the method first checks if a log server is responding on the
     * specified HTTP port; if a server is detected, the method prints an error message and terminates the program. It
     * then attempts to delete the {@code .mv.db} and {@code .trace.db} files associated with the provided database
     * path. If a file exists but cannot be deleted, the method prints a failure message and terminates the program. If
     * no files are found, it informs the user that no database files exist at that location.
     *
     * @param cl the parsed command line containing the {@code db} path, an optional {@code http-port}, and an optional
     * {@code force} flag.
     */
    public void execute(CommandLine cl) {
        File dbPath = new File(cl.getOptionValue("db"));
        int httpPort = Integer.parseInt(cl.getOptionValue("http-port", "29091"));
        boolean force = cl.hasOption("f");

        // Check if log server is running
        if (!force && isLogServerRunning(httpPort)) {
            System.err.println("Error: Log server is running on HTTP port " + httpPort);
            System.err.println("Please stop the log server first, or use --force to skip this check.");
            System.exit(1);
        }

        File mvDb = new File(dbPath.getAbsolutePath() + ".mv.db");
        File traceDb = new File(dbPath.getAbsolutePath() + ".trace.db");

        boolean anyDeleted = false;

        if (mvDb.exists()) {
            if (mvDb.delete()) {
                System.out.println("Deleted: " + mvDb.getAbsolutePath());
                anyDeleted = true;
            } else {
                System.err.println("Failed to delete: " + mvDb.getAbsolutePath());
                System.exit(1);
            }
        }

        if (traceDb.exists()) {
            if (traceDb.delete()) {
                System.out.println("Deleted: " + traceDb.getAbsolutePath());
                anyDeleted = true;
            } else {
                System.err.println("Failed to delete: " + traceDb.getAbsolutePath());
                System.exit(1);
            }
        }

        if (!anyDeleted) {
            System.out.println("No database files found at: " + dbPath.getAbsolutePath());
        } else {
            System.out.println("Database cleared successfully.");
        }
    }

    private boolean isLogServerRunning(int httpPort) {
        try {
            URL url = new URL("http://localhost:" + httpPort + "/info");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (IOException e) {
            return false;
        }
    }
}
