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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.json.JSONObject;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * A java.util.logging Handler that forwards log messages to MultiplexerAccumulatorActor.
 *
 * <p>Bridges java.util.logging with the MultiplexerAccumulator system,
 * allowing all log messages to be captured in the same output destinations
 * (console, file, database) as command output and cowsay.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerLogHandler extends Handler {

    /** ISO 8601 formatter with timezone offset for log timestamps. */
    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** The system default timezone used for formatting timestamps. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The actor system used to look up the {@code outputMultiplexer} actor. */
    private final IIActorSystem system;

    /** Flag indicating whether this handler has been closed. */
    private volatile boolean closed = false;

    /**
     * Constructs a {@code MultiplexerLogHandler} that forwards log records
     * to the {@code outputMultiplexer} actor within the given actor system.
     *
     * @param system the actor system containing the {@code outputMultiplexer} actor
     */
    public MultiplexerLogHandler(IIActorSystem system) {
        this.system = system;
    }

    /**
     * Publishes a log record by formatting it as an ISO 8601 timestamped message
     * and forwarding it to the {@code outputMultiplexer} actor via the {@code add} action.
     *
     * <p>The log entry is sent with:</p>
     * <ul>
     *   <li>{@code source} - a short name derived from the logger name
     *       (e.g., {@code "turing-workflow"}, {@code "pojo-actor"}, or the simple class name)</li>
     *   <li>{@code type} - {@code "log-"} followed by the log level name
     *       (e.g., {@code "log-INFO"}, {@code "log-SEVERE"})</li>
     *   <li>{@code data} - the formatted log message including timestamp, level, and
     *       optional stack trace</li>
     * </ul>
     *
     * <p>If the handler is closed, the record is {@code null}, or the
     * {@code outputMultiplexer} actor is not registered, the record is silently discarded.</p>
     *
     * @param record the log record to publish; may be {@code null}
     */
    @Override
    public void publish(LogRecord record) {
        if (closed || record == null) {
            return;
        }

        if (!isLoggable(record)) {
            return;
        }

        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            return;
        }

        String timestamp = LocalDateTime.now().atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
        String level = record.getLevel().getName();
        String message = formatMessage(record);
        String formattedLog = String.format("%s %s %s", timestamp, level, message);

        try {
            JSONObject arg = new JSONObject();
            arg.put("source", getSourceName(record));
            arg.put("type", "log-" + level);
            arg.put("data", formattedLog);
            multiplexer.callByActionName("add", arg.toString());
        } catch (Exception e) {
            // Avoid infinite recursion
            System.err.println("MultiplexerLogHandler error: " + e.getMessage());
        }
    }

    private String formatMessage(LogRecord record) {
        String message = record.getMessage();

        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            try {
                message = String.format(message, params);
            } catch (Exception e) {
                // Use raw message if formatting fails
            }
        }

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringBuilder sb = new StringBuilder(message);
            sb.append("\n").append(thrown.getClass().getName());
            if (thrown.getMessage() != null) {
                sb.append(": ").append(thrown.getMessage());
            }
            for (StackTraceElement element : thrown.getStackTrace()) {
                sb.append("\n\tat ").append(element);
            }
            message = sb.toString();
        }

        return message;
    }

    private String getSourceName(LogRecord record) {
        String loggerName = record.getLoggerName();
        if (loggerName == null || loggerName.isEmpty()) {
            return "system";
        }

        if (loggerName.startsWith("com.scivicslab.turingworkflow")) {
            return "turing-workflow";
        }
        if (loggerName.startsWith("com.scivicslab.pojoactor")) {
            return "pojo-actor";
        }

        int lastDot = loggerName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < loggerName.length() - 1) {
            return loggerName.substring(lastDot + 1);
        }

        return loggerName;
    }

    /**
     * No-op flush. Log entries are forwarded immediately on {@link #publish}.
     */
    @Override
    public void flush() {
    }

    /**
     * Closes this handler. After closing, subsequent calls to {@link #publish}
     * will silently discard log records.
     *
     * @throws SecurityException if a security manager denies the operation
     */
    @Override
    public void close() throws SecurityException {
        closed = true;
    }
}
