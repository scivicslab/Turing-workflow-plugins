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
 * Writes output to the console via {@link PrintStream} instances, typically {@code System.out} and {@code System.err}.
 *
 * <ul><li>{@code add(source, type, data)} routes entries with type {@code "stderr"} to the error stream and all other
 * types to the standard output stream, prefixing every line of the data with {@code [source]}.</li><li>{@code
 * getSummary()} returns a string stating the number of entries written to the console.</li><li>{@code getCount()}
 * returns the total number of entries processed.</li><li>{@code clear()} resets the entry counter to
 * zero.</li><li>{@code setQuiet(boolean)} enables a mode where entries are counted but not printed to the
 * console.</li></ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * console.add("node-web1", "stdout", "Deployment complete");
 * // Displays: [node-web1] Deployment complete
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class ConsoleAccumulator implements Accumulator {

    /**
     * The output stream used for standard output, including types {@code "stdout"}, {@code "cowsay"}, and all other
     * types not explicitly routed to the error stream.
     */
    private final PrintStream stdout;

    /** The output stream used for error output when the entry type is {@code "stderr"}. */
    private final PrintStream stderr;

    /** The total number of entries processed by this accumulator. */
    private final AtomicInteger count = new AtomicInteger(0);

    /** Indicates whether entries are counted but suppressed from the console. */
    private volatile boolean quiet = false;

    /** Initializes a {@code ConsoleAccumulator} that writes to {@link System#out} and {@link System#err}. */
    public ConsoleAccumulator() {
        this(System.out, System.err);
    }

    /**
     * Constructs a {@code ConsoleAccumulator} that writes to the specified {@link PrintStream} instances.
     *
     * @param stdout the stream used for standard output entries, including {@code "stdout"} and {@code "cowsay"} types
     * @param stderr the stream used for error output entries of type {@code "stderr"}
     */
    public ConsoleAccumulator(PrintStream stdout, PrintStream stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Sets whether entries are printed to the console or only counted.
     *
     * @param quiet {@code true} to suppress printing of entries, {@code false} to print them normally
     */
    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Returns whether quiet mode is enabled, in which case entries are counted but not printed to the console.
     *
     * @return {@code true} if quiet mode is enabled, {@code false} otherwise
     */
    public boolean isQuiet() {
        return quiet;
    }

    /**
     * Records an output entry to the console.
     *
     * <p>Routes the entry to the error stream if {@code type} is {@code "stderr"}, otherwise routes it to the standard
     * output stream. If quiet mode is enabled, or if {@code data} is {@code null} or empty, the entry is counted but
     * nothing is printed.</p>
     *
     * @param source the name of the source to be prefixed to every line of the output
     * @param type the output type, where {@code "stderr"} directs to the error stream and all other values direct to
     * standard output
     *
     * @param data the text to be printed, with {@code source} prefixed to every line
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

    /**
     * Returns a string that summarizes the number of entries processed by this accumulator.
     *
     * The result is a string in the format "ConsoleAccumulator: [count] entries written to console", where [count] is
     * the total number of entries added, regardless of whether quiet mode was enabled.
     */
    @Override
    public String getSummary() {
        return "ConsoleAccumulator: " + count.get() + " entries written to console";
    }

    /** Returns the total number of entries processed by this accumulator. */
    @Override
    public int getCount() {
        return count.get();
    }

    /** Resets the number of entries processed by this accumulator to zero. */
    @Override
    public void clear() {
        count.set(0);
    }
}
