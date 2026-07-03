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
 * CLI command plugin for the log database: contributes the {@code log-search} subcommand to
 * the host CLI via the pluggable-cli {@code ServiceLoader} mechanism.
 *
 * <p>The host (turing-workflow {@code App}) discovers this plugin from the JAR's
 * {@code META-INF/services/com.scivicslab.pluggablecli.CliPlugin} entry and calls
 * {@link #registerCommands(CommandRepository)} at startup, which registers the command
 * defined in {@link LogsCLI}. The command then appears in the host's command list and its
 * {@code -h} help.</p>
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
     * Registers the bundled H2 JDBC driver with {@link java.sql.DriverManager}.
     *
     * <p>When this plugin JAR is loaded through a child {@link ClassLoader} (as the host does
     * for plugins), the bundled H2 driver is not auto-discovered by {@code DriverManager},
     * whose service lookup runs against the system class path. Loading the driver class here,
     * from this plugin's class loader, registers it so that {@code DriverManager.getConnection}
     * called from this plugin's code can find it.</p>
     */
    private static void ensureH2Driver() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            // h2 is bundled in this plugin JAR; this should not happen.
            System.err.println("Warning: H2 driver not found on the plugin class path: " + e.getMessage());
        }
    }
}
