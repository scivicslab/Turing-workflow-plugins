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

import com.scivicslab.pluggablecli.CliPlugin;
import com.scivicslab.pluggablecli.CommandRepository;

/**
 * Adds the log database management commands to the host CLI.
 *
 * <p>The plugin provides the following subcommands to the host application:
 * <ul>
 * <li>{@code log-search}: searches the H2 database for logs.</li>
 * <li>{@code log-merge}: merges log files.</li>
 * <li>{@code db-clear}: clears the log database.</li>
 * <li>{@code log-serve}: serves the logs.</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.5.0
 */
public class LogDbCliPlugin implements CliPlugin {

    @Override
    public String getPluginName() {
        return "log-db";
    }

    @Override
    public String getPluginVersion() {
        return "1.5.0";
    }

    @Override
    public String getDescription() {
        return "Log database commands (log-search, log-merge, db-clear, log-serve)";
    }

    @Override
    public void registerCommands(CommandRepository repository) {
        ensureH2Driver();
        LogsCLI.registerCommand(repository);
        MergeLogsCLI.registerCommand(repository);
        DbClearCLI.registerCommand(repository);
        LogServeCLI.registerCommand(repository);
    }

    /**
     * Registers the H2 JDBC driver with the system's driver manager so that database connections can be established.
     *
     * <p>If the H2 driver is not found on the plugin's class path, it prints a warning to standard error. This occurs
     * if the host's plugins configuration does not include {@code com.h2database:h2} as a dependency, in which case
     * all database-related commands in this plugin will fail with a "No suitable driver found" error.</p>
     */
    private static void ensureH2Driver() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("Warning: H2 driver not found on the plugin class path: " + e.getMessage()
                    + ". Add an entry for com.h2database:h2 to the plugins config.");
        }
    }
}
