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

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Writes output entries to a text file, prefixing every line with a source tag and flushing to disk immediately.
 *
 * <ul><li>{@code add(source, type, data)} writes the data to the file with a {@code [source]} prefix on every
 * line.</li><li>{@code getFilePath()} returns the {@link Path} of the output file.</li><li>{@code getCount()} returns
 * the number of entries processed.</li><li>{@code getSummary()} returns a string stating the number of entries written
 * and the file path.</li><li>{@code clear()} resets the entry counter to zero.</li><li>{@code close()} closes the file
 * writer.</li></ul>
 *
 * <p>The user must call {@link #close()} to release the file handle. Once closed, subsequent calls to {@link #add}
 * increment the counter but produce no file output.</p>
 *
 * <p>One instance may serve several threads at once.</p>
 *
 * <pre>{@code
 * FileAccumulator file = new FileAccumulator("output.log");
 * file.add("node-web1", "stdout", "Started");
 * file.close();
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class FileAccumulator implements Accumulator, Closeable {

    /** The writer used to append formatted entries to the file. */
    private final PrintWriter writer;

    /** The path to the text file where output entries are written. */
    private final Path filePath;

    /** The total number of entries processed by this accumulator. */
    private final AtomicInteger count = new AtomicInteger(0);

    /** Indicates whether the accumulator has been closed and will no longer write to the file. */
    private volatile boolean closed = false;

    /**
     * Creates a {@code FileAccumulator} that writes output entries to the specified file path.
     *
     * @param filePath the path to the output file
     * @throws IOException if the file cannot be opened or created for writing
     */
    public FileAccumulator(Path filePath) throws IOException {
        this.filePath = filePath;
        this.writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath.toFile())));
    }

    /**
     * Initializes a {@code FileAccumulator} that writes to the file at the specified path string.
     *
     * @param filePath the path string to the output file
     * @throws IOException if the file cannot be opened for writing
     */
    public FileAccumulator(String filePath) throws IOException {
        this(Path.of(filePath));
    }

    /** Returns the path to the output file. */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Writes a formatted entry to the file and flushes it to disk.
     *
     * <p>If the accumulator is closed, or if the data is null or empty, the entry is not written to the file but the
     * internal counter is still incremented.</p>
     *
     * @param source the name of the source to be prefixed to every line of the data
     * @param type the type of the output (e.g., "stdout" or "stderr")
     * @param data the text to be written to the file
     */
    @Override
    public void add(String source, String type, String data) {
        if (closed || data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        String output = formatOutput(source, data);

        synchronized (writer) {
            writer.print(output);
            writer.flush();
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
     * Returns a string that summarizes the file output, stating the number of entries processed and the path to the
     * file.
     */
    @Override
    public String getSummary() {
        return "FileAccumulator: " + count.get() + " entries written to " + filePath;
    }

    /** Returns the total number of entries processed by this accumulator. */
    @Override
    public int getCount() {
        return count.get();
    }

    /**
     * Resets the number of processed entries to zero.
     *
     * <p>The counter is set to 0, but no entries are removed from the output file.</p>
     */
    @Override
    public void clear() {
        count.set(0);
    }

    /**
     * Closes the underlying file writer. After this method returns, subsequent calls to {@link #add} will increment
     * the counter but produce no file output. Calling {@code close} multiple times has no effect.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            synchronized (writer) {
                writer.close();
            }
        }
    }
}
