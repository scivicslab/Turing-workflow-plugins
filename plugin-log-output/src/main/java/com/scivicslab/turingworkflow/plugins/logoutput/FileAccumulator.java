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
 * An {@link Accumulator} implementation that writes output entries to a text file.
 *
 * <p>Each entry is formatted with a {@code [source]} prefix on every line and
 * flushed to disk immediately. The accumulator implements {@link Closeable}
 * so it can be used in try-with-resources blocks.</p>
 *
 * <p>Once {@link #close()} has been called, subsequent calls to {@link #add}
 * will increment the counter but produce no file output.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class FileAccumulator implements Accumulator, Closeable {

    /** The writer used to append formatted entries to the file. */
    private final PrintWriter writer;

    /** The path to the output file. */
    private final Path filePath;

    /** Thread-safe counter of entries processed by this accumulator. */
    private final AtomicInteger count = new AtomicInteger(0);

    /** Flag indicating whether this accumulator has been closed. */
    private volatile boolean closed = false;

    /**
     * Constructs a {@code FileAccumulator} that writes to the specified file path.
     * The file is created (or truncated) immediately.
     *
     * @param filePath the path to the output file
     * @throws IOException if the file cannot be opened for writing
     */
    public FileAccumulator(Path filePath) throws IOException {
        this.filePath = filePath;
        this.writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath.toFile())));
    }

    /**
     * Constructs a {@code FileAccumulator} that writes to the specified file path string.
     *
     * @param filePath the path string to the output file
     * @throws IOException if the file cannot be opened for writing
     */
    public FileAccumulator(String filePath) throws IOException {
        this(Path.of(filePath));
    }

    /**
     * Returns the path to the output file.
     *
     * @return the file path
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the formatted entry to the file and flushes immediately.
     * If the accumulator is closed or the data is {@code null}/empty,
     * only the counter is incremented.</p>
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

    /** {@inheritDoc} */
    @Override
    public String getSummary() {
        return "FileAccumulator: " + count.get() + " entries written to " + filePath;
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

    /**
     * Closes the underlying file writer. After this method returns,
     * subsequent calls to {@link #add} will no longer produce file output.
     * Calling {@code close()} multiple times has no additional effect.
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
