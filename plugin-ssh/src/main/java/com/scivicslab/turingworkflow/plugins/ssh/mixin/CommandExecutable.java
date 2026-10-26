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
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;

/**
 * Adds command execution actions to an actor, allowing a workflow to run commands via {@code executeCommand} and
 * {@code executeSudoCommand}.
 *
 * <ul>
 * <li>{@code executeCommand} runs a command and returns an {@link ActionResult} containing the stdout and stderr as a
 * single message, or a failure message if an {@link IOException} occurs.</li>
 * <li>{@code executeSudoCommand} runs a command with sudo privileges using the {@code SUDO_PASSWORD} environment
 * variable and returns an {@link ActionResult} containing the output or a failure message.</li>
 * </ul>
 *
 * <p>Implementing classes must provide a {@link CommandExecutor} via {@link #getCommandExecutor()} to determine if
 * commands run locally or via SSH.</p>
 *
 * <p>Example workflow step:</p>
 * <pre>{@code
 * - actor: this
 *   method: executeCommand
 *   arguments: ["ls -la"]
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 * @see CommandExecutor
 * @see SshCommandExecutor
 * @see LocalCommandExecutor
 */
public interface CommandExecutable {

    /**
     * Returns the command executor used by this instance to run commands.
     *
     * <p>Implementing classes must return a {@link SshCommandExecutor} for remote execution or a {@link
     * LocalCommandExecutor} for local execution.</p>
     *
     * @return the command executor
     */
    CommandExecutor getCommandExecutor();

    /**
     * Holds the command line string to be executed by an action.
     *
     * @param command the command line string
     */
    record CommandArgs(@NotNull String command) {}

    /**
     * Returns an optional callback that receives command output lines in real-time during execution.
     *
     * @return the output callback, or null if no streaming is configured
     */
    default Node.OutputCallback getOutputCallback() {
        return null;
    }

    /**
     * Executes a command on the node and returns an action result containing the output.
     *
     * <p>This action is available in workflow YAML as:
     * <pre>{@code
     * - actor: this
     *   method: executeCommand
     *   arguments: ["ls -la"]
     * }</pre>
     *
     * @param args a {@link CommandArgs} containing the command line to run
     * @return an {@link ActionResult} that is a success if the command exited with a zero status, with a message
     * holding the stdout (and stderr if stdout is empty or if stderr is appended), and is not a success if the command
     * failed or an I/O error occurred, with a message holding the error description.
     */
    @Action(value = "executeCommand", argsType = CommandArgs.class)
    default ActionResult doExecuteCommand(CommandArgs args) {
        try {
            String command = args.command();
            Node.OutputCallback callback = getOutputCallback();
            Node.CommandResult result = getCommandExecutor().execute(command, callback);
            return toActionResult(result);
        } catch (IOException e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Executes a command with sudo privileges and returns an action result.
     *
     * <p>A workflow calls this as:
     * <pre>{@code
     * - actor: this
     *   method: executeSudoCommand
     *   arguments: ["apt-get update"]
     * }</pre>
     *
     * <p>The result is a success if the command runs and returns a zero exit code, with the message containing the
     * command's stdout and stderr; otherwise, the result is a failure with an error message. If the {@code
     * SUDO_PASSWORD} environment variable is not set, the result is a failure with a message indicating the missing
     * password.</p>
     *
     * @param args the command line to run
     */
    @Action(value = "executeSudoCommand", argsType = CommandArgs.class)
    default ActionResult doExecuteSudoCommand(CommandArgs args) {
        try {
            String command = args.command();
            Node.OutputCallback callback = getOutputCallback();
            Node.CommandResult result = getCommandExecutor().executeSudo(command, callback);
            return toActionResult(result);
        } catch (IOException e) {
            String hostname = getCommandExecutor().getIdentifier();
            if (e.getMessage() != null && e.getMessage().contains("SUDO_PASSWORD")) {
                return new ActionResult(false, "%" + hostname + ": [FAIL] SUDO_PASSWORD not set");
            }
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Converts a command execution result into an action result for the workflow.
     *
     * Returns an {@link ActionResult} where the message contains the trimmed stdout, or the trimmed stderr if stdout
     * is empty, or both joined by a "[stderr]" separator if both are non-empty. The success status of the action
     * result matches the success status of the command result.
     */
    private static ActionResult toActionResult(Node.CommandResult result) {
        String output = result.getStdout().trim();
        String stderr = result.getStderr().trim();
        if (!stderr.isEmpty()) {
            output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
        }
        return new ActionResult(result.isSuccess(), output);
    }
}
