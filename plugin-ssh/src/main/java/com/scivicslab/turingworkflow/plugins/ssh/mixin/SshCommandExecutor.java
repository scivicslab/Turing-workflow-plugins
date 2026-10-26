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
 * Executes commands on a remote node via SSH by delegating to a {@link Node}.
 *
 * <p><ul><li>{@code execute(command)} returns a {@link Node.CommandResult} containing the stdout, stderr, and exit
 * code of the command.</li><li>{@code execute(command, callback)} returns a {@link Node.CommandResult} and streams
 * output to the provided callback.</li><li>{@code executeSudo(command)} returns a {@link Node.CommandResult} for a
 * command run with sudo privileges, using the {@code SUDO_PASSWORD} environment variable.</li><li>{@code
 * executeSudo(command, callback)} returns a {@link Node.CommandResult} for a sudo command and streams output to the
 * provided callback.</li><li>{@code getIdentifier()} returns the hostname of the remote node.</li></ul>
 *
 * <p>One instance may serve several threads at once.
 *
 * <p>An action result is a success if the command's exit code is 0, and the message holds the command output. If the
 * connection fails or the command cannot be sent, the result is not a success.
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class SshCommandExecutor implements CommandExecutor {

    /** The remote node to execute commands on via SSH. */
    private final Node node;

    /**
     * Initializes an SSH command executor that runs commands on the specified {@link Node}.
     *
     * @param node the node to execute commands on
     */
    public SshCommandExecutor(Node node) {
        this.node = node;
    }

    /**
     * Executes a command on the remote node via SSH.
     *
     * @param command the command string to be executed on the remote host
     * @return a {@link Node.CommandResult} containing the exit code, stdout, and stderr
     * @throws IOException if an error occurs during communication with the remote node
     */
    @Override
    public Node.CommandResult execute(String command) throws IOException {
        return node.executeCommand(command);
    }

    /**
     * Executes a command on the remote node via SSH and streams its output to the provided callback.
     *
     * @param command the command string to execute on the remote host
     * @param callback a listener that receives lines of stdout and stderr as they are produced
     * @return a {@link Node.CommandResult} containing the exit code and the full captured output
     * @throws IOException if an error occurs during the SSH session or command execution
     */
    @Override
    public Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException {
        return node.executeCommand(command, callback);
    }

    /**
     * Executes the given command with sudo privileges.
     *
     * @param command the command string to run
     * @return a result containing the exit code, stdout, and stderr
     * @throws IOException if an I/O error occurs during execution
     */
    @Override
    public Node.CommandResult executeSudo(String command) throws IOException {
        return node.executeSudoCommand(command);
    }

    /**
     * Executes a command with sudo privileges and streams its output via the provided callback.
     *
     * @param command the command string to execute
     * @param callback a listener that receives lines from stdout and stderr as they are produced
     * @return a CommandResult containing the exit code and the combined stdout and stderr as a string
     * @throws IOException if an error occurs during command execution or communication with the node
     */
    @Override
    public Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException {
        return node.executeSudoCommand(command, callback);
    }

    /** Returns the hostname of the remote node. */
    @Override
    public String getIdentifier() {
        return node.getHostname();
    }

    /** Returns the {@link Node} used to execute commands via SSH. */
    public Node getNode() {
        return node;
    }
}
