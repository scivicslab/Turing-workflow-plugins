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

package com.scivicslab.turingworkflow.plugins.ssh.mixin;

import java.io.IOException;

import com.scivicslab.turingworkflow.plugins.ssh.Node;

/**
 * An interface for running commands on a target machine, either locally or remotely via SSH.
 *
 * <ul>
 * <li>{@link #execute(String)} returns the result of the command.</li>
 * <li>{@link #execute(String, Node.OutputCallback)} returns the result of the command and sends real-time output to
 * the callback.</li>
 * <li>{@link #executeSudo(String)} returns the result of the command executed with sudo privileges.</li>
 * <li>{@link #executeSudo(String, Node.OutputCallback)} returns the result of the command executed with sudo
 * privileges and sends real-time output to the callback.</li>
 * <li>{@link #getIdentifier()} returns a short identifier for the target, such as a hostname.</li>
 * </ul>
 *
 * <p>Implementations include {@link SshCommandExecutor} for remote execution and {@link LocalCommandExecutor} for
 * local execution.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public interface CommandExecutor {

    /**
     * Executes the given command and returns its result.
     *
     * @param command the command string to be executed
     * @return a {@link Node.CommandResult} containing the exit code, stdout, and stderr of the execution
     */
    Node.CommandResult execute(String command) throws IOException;

    /**
     * Executes a command and returns the result, optionally providing real-time access to the output via a callback.
     *
     * @param command the command to execute
     * @param callback the callback for real-time output, which may be null if no streaming is required
     * @return the result of command execution
     * @throws IOException if command execution fails
     */
    Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException;

    /**
     * Executes a command with sudo privileges.
     *
     * @param command the command to execute
     * @return the result of command execution
     * @throws IOException if command execution fails or if the {@code SUDO_PASSWORD} environment variable is not set
     */
    Node.CommandResult executeSudo(String command) throws IOException;

    /**
     * Executes a command with sudo privileges and streams real-time output to the provided callback.
     *
     * @param command the command to execute with sudo
     * @param callback the callback for real-time output, which may be null to suppress output
     * @return the result of command execution
     * @throws IOException if command execution fails or if the {@code SUDO_PASSWORD} environment variable is not set
     */
    Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException;

    /**
     * Returns a short string that identifies the execution context, such as a hostname or local designation.
     *
     * @return the identifier string
     */
    String getIdentifier();
}
