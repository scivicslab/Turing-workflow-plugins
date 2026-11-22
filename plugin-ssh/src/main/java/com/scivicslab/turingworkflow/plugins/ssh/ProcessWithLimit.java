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

package com.scivicslab.turingworkflow.plugins.ssh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Waits for a started child process for at most a given number of seconds while reading its stdout and stderr, and
 * kills the process and its descendants when the limit passes.
 *
 * <p>Both streams are read by their own threads from the start, so a child that writes more than a pipe buffer holds
 * never blocks on a full pipe.</p>
 */
final class ProcessWithLimit {

    private static final Logger LOG = Logger.getLogger(ProcessWithLimit.class.getName());

    /** How long the readers may still take after the process ended or was killed, in milliseconds. */
    private static final long READER_GRACE_MILLIS = 5000;

    private ProcessWithLimit() {
    }

    /**
     * The text a child process wrote and how it ended.
     *
     * @param stdout the standard output, trimmed
     * @param stderr the standard error, trimmed
     * @param exitCode the exit code, or -1 when the process did not finish within its limit
     * @param finished false when the limit passed and the process was killed
     */
    record Outcome(String stdout, String stderr, int exitCode, boolean finished) {
    }

    /**
     * Returns what the process wrote and how it ended, waiting at most {@code limitSeconds} for it to exit.
     *
     * @param process the started process
     * @param limitSeconds how long the process may run
     * @param onStdout receives each stdout line as it is read, or {@code null}
     * @param onStderr receives each stderr line as it is read, or {@code null}
     * @throws InterruptedException if the waiting thread is interrupted; the process is killed first
     */
    static Outcome waitFor(Process process, long limitSeconds, Consumer<String> onStdout, Consumer<String> onStderr)
            throws InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutReader = startReader(process.getInputStream(), stdout, onStdout);
        Thread stderrReader = startReader(process.getErrorStream(), stderr, onStderr);
        boolean finished;
        try {
            finished = process.waitFor(limitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        if (!finished) {
            kill(process);
        }
        stdoutReader.join(READER_GRACE_MILLIS);
        stderrReader.join(READER_GRACE_MILLIS);
        return new Outcome(textOf(stdout), textOf(stderr), finished ? process.exitValue() : -1, finished);
    }

    /** Kills the process and every process it started. */
    static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static Thread startReader(InputStream in, StringBuilder sink, Consumer<String> onLine) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                    if (onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                // the stream closes when the process is killed
                LOG.log(Level.FINE, "child process stream closed", e);
            }
        });
    }

    private static String textOf(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString().trim();
        }
    }
}
