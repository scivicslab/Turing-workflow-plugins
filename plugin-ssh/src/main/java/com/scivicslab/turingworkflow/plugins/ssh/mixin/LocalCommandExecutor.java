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
 * Command executor that executes commands on the local machine.
 *
 * <p>Used by NodeGroupInterpreter to execute commands locally when running
 * on the control node itself.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class LocalCommandExecutor implements CommandExecutor {

    /** Default timeout for command execution in seconds (5 minutes). */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /** The local hostname, used as the executor identifier. */
    private final String hostname;

    /**
     * Constructs a local command executor that runs commands on the local machine.
     *
     * <p>Automatically detects the local hostname for use as the executor identifier.
     * Falls back to "localhost" if hostname detection fails.</p>
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

    /** {@inheritDoc} */
    @Override
    public Node.CommandResult execute(String command) throws IOException {
        return execute(command, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes the command via {@code /bin/sh -c} with a timeout of
     * {@value #DEFAULT_TIMEOUT_SECONDS} seconds. Stdout and stderr are read
     * in separate virtual threads (Java 21) to prevent deadlocks.</p>
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

    /** {@inheritDoc} */
    @Override
    public Node.CommandResult executeSudo(String command) throws IOException {
        return executeSudo(command, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes via {@code sudo -S bash -c}, writing the password from the
     * {@code SUDO_PASSWORD} environment variable to stdin.</p>
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

    /** {@inheritDoc} */
    @Override
    public String getIdentifier() {
        return hostname;
    }
}
