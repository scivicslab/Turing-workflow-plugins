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

package com.scivicslab.turingworkflow.plugins.logoutput;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * An {@link Accumulator} implementation that writes output to the console
 * via {@link PrintStream} instances (typically {@code System.out} and {@code System.err}).
 *
 * <p>Each entry is formatted with a {@code [source]} prefix on every line.
 * Entries with type {@code "stderr"} are routed to the error stream; all other
 * types (including {@code "stdout"} and {@code "cowsay"}) are routed to the
 * standard output stream.</p>
 *
 * <p>When {@linkplain #setQuiet(boolean) quiet mode} is enabled, entries are
 * counted but not printed.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class ConsoleAccumulator implements Accumulator {

    /** The output stream used for standard output (stdout, cowsay, and default types). */
    private final PrintStream stdout;

    /** The output stream used for error output (stderr type). */
    private final PrintStream stderr;

    /** Thread-safe counter of entries processed by this accumulator. */
    private final AtomicInteger count = new AtomicInteger(0);

    /** When {@code true}, entries are counted but not printed. */
    private volatile boolean quiet = false;

    /**
     * Constructs a {@code ConsoleAccumulator} that writes to
     * {@link System#out} and {@link System#err}.
     */
    public ConsoleAccumulator() {
        this(System.out, System.err);
    }

    /**
     * Constructs a {@code ConsoleAccumulator} with the specified output streams.
     *
     * @param stdout the stream for standard output entries
     * @param stderr the stream for error output entries
     */
    public ConsoleAccumulator(PrintStream stdout, PrintStream stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Enables or disables quiet mode. In quiet mode, entries are counted
     * but not printed to the console.
     *
     * @param quiet {@code true} to suppress output; {@code false} to print normally
     */
    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Returns whether quiet mode is currently enabled.
     *
     * @return {@code true} if quiet mode is enabled
     */
    public boolean isQuiet() {
        return quiet;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Routes the entry to {@code stderr} if the type is {@code "stderr"},
     * otherwise to {@code stdout}. If quiet mode is enabled or data is
     * {@code null}/empty, the entry is silently counted.</p>
     */
    @Override
    public void add(String source, String type, String data) {
        if (quiet) {
            count.incrementAndGet();
            return;
        }

        if (data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        String output = formatOutput(source, data);

        switch (type) {
            case "stderr":
                stderr.print(output);
                break;
            case "cowsay":
            case "stdout":
            default:
                stdout.print(output);
                break;
        }
        count.incrementAndGet();
    }

    private String formatOutput(String source, String data) {
        String prefix = "[" + (source != null ? source : "") + "] ";
        StringBuilder sb = new StringBuilder();

        String[] lines = data.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        sb.append("\n");

        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override
    public String getSummary() {
        return "ConsoleAccumulator: " + count.get() + " entries written to console";
    }

    /** {@inheritDoc} */
    @Override
    public int getCount() {
        return count.get();
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        count.set(0);
    }
}
