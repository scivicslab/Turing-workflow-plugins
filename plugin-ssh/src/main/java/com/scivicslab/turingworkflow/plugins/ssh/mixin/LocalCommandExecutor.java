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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import com.scivicslab.turingworkflow.plugins.ssh.Node;

/**
 * Executes commands on the local machine using the system shell.
 *
 * <p>The class provides the following operations:
 * <ul>
 * <li>`execute(command, callback)`: Runs a command via {@code /bin/sh -c} and returns a {@link Node.CommandResult}
 * containing the stdout, stderr, and exit code.</li>
 * <li>`executeSudo(command, callback)`: Runs a command via {@code sudo -S} and returns a {@link Node.CommandResult}
 * using the password from the {@code SUDO_PASSWORD} environment variable.</li>
 * <li>`getIdentifier()`: Returns the local hostname as a string.</li>
 * </ul>
 *
 * <p>Commands are subject to a 5-minute timeout; if exceeded, the process is destroyed and a result with exit code -1
 * and a timeout message is returned. If the {@code SUDO_PASSWORD} environment variable is not set, {@code executeSudo}
 * throws an {@link IOException}.
 *
 * <p>Differs from {@link SshCommandExecutor} by running commands locally instead of over SSH.
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class LocalCommandExecutor implements CommandExecutor {

    /**
     * The maximum time in seconds a command is allowed to run before being forcibly terminated, which is 300 seconds.
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /** The local hostname used to identify this executor. */
    private final String hostname;

    /**
     * Initializes a command executor that runs commands on the local machine.
     *
     * <p>The executor identifies itself using the local hostname, falling back to {@code localhost} if the hostname
     * cannot be determined.</p>
     */
    public LocalCommandExecutor() {
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "localhost";
        }
        this.hostname = h;
    }

    /**
     * Executes the given command on the local machine via {@code /bin/sh -c}.
     *
     * Returns a {@link Node.CommandResult} containing the captured stdout, stderr, and the process exit code. If the
     * command exceeds 300 seconds, the process is forcibly destroyed and a result is returned with a message
     * indicating a timeout and an exit code of -1.
     *
     * @param command the shell command string to execute
     */
    @Override
    public Node.CommandResult execute(String command) throws IOException {
        return execute(command, null);
    }

    /**
     * Executes a command on the local machine using {@code /bin/sh -c}.
     *
     * @param command the shell command string to execute
     * @param callback a listener that receives lines of stdout and stderr as they are produced, or {@code null} to
     * only capture the final result
     *
     * @return a {@link Node.CommandResult} containing the trimmed stdout, the stderr, and the exit code; if the
     * command exceeds 300 seconds, returns a result with a message "Command timed out" and an exit code of -1
     *
     * @throws IOException if an I/O error occurs during process startup or communication
     */
    @Override
    public Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Read stdout in virtual thread (Java 21)
        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stdout) {
                        stdout.append(line).append("\n");
                    }
                    if (callback != null) {
                        callback.onStdout(line);
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });

        // Read stderr in virtual thread (Java 21)
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        stderr.append(line).append("\n");
                    }
                    if (callback != null) {
                        callback.onStderr(line);
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });

        try {
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // Join reader threads before accessing StringBuilders
                joinQuietly(stdoutThread);
                joinQuietly(stderrThread);
                synchronized (stdout) {
                    return new Node.CommandResult(stdout.toString(), "Command timed out", -1);
                }
            }

            // Join reader threads to ensure all output is captured
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);

            int exitCode = process.exitValue();
            String out;
            String err;
            synchronized (stdout) { out = stdout.toString().trim(); }
            synchronized (stderr) { err = stderr.toString().trim(); }
            return new Node.CommandResult(out, err, exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            synchronized (stdout) {
                return new Node.CommandResult(stdout.toString(), "Interrupted: " + e.getMessage(), -1);
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Executes a command with elevated privileges on the local machine.
     *
     * @param command the command string to run via {@code sudo -S bash -c}
     * @return a {@link Node.CommandResult} containing the stdout, stderr, and exit code of the command
     */
    @Override
    public Node.CommandResult executeSudo(String command) throws IOException {
        return executeSudo(command, null);
    }

    /**
     * Executes a command with elevated privileges using {@code sudo -S}.
     *
     * Returns a {@link Node.CommandResult} containing the command's stdout, stderr, and exit code.
     *
     * Throws an {@link IOException} if the {@code SUDO_PASSWORD} environment variable is not set or is empty.
     *
     * <pre>{@code
     * executor.executeSudo("systemctl restart nginx", callback);
     * }</pre>
     *
     * @param command the command string to execute via {@code bash -c}
     * @param callback a listener to receive stdout and stderr lines as they are produced
     * @return a result containing the trimmed stdout and stderr, and the process exit code; if the command times out
     * or is interrupted, the result contains the captured output and a message describing the failure with an exit
     * code of -1
     */
    @Override
    public Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException {
        String sudoPassword = System.getenv("SUDO_PASSWORD");
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new IOException("SUDO_PASSWORD environment variable is not set");
        }

        // Use ProcessBuilder to pass password via stdin, not shell arguments
        ProcessBuilder pb = new ProcessBuilder("sudo", "-S", "bash", "-c", command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Write password to stdin for sudo -S
        try (java.io.OutputStream stdin = process.getOutputStream()) {
            stdin.write((sudoPassword + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    if (callback != null) { callback.onStdout(line); }
                }
            } catch (IOException e) { /* Ignore */ }
        });

        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    if (callback != null) { callback.onStderr(line); }
                }
            } catch (IOException e) { /* Ignore */ }
        });

        try {
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(1000);
                stderrThread.join(1000);
                return new Node.CommandResult(stdout.toString(), "Command timed out", -1);
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            int exitCode = process.exitValue();
            return new Node.CommandResult(stdout.toString().trim(), stderr.toString().trim(), exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Node.CommandResult(stdout.toString(), "Interrupted: " + e.getMessage(), -1);
        }
    }

    /** Returns the hostname of the local machine used to identify this executor. */
    @Override
    public String getIdentifier() {
        return hostname;
    }
}
