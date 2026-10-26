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
 * Sends java.util.logging records to the {@code outputMultiplexer} actor so that system logs appear in the same
 * destinations as workflow command output.
 *
 * <p>The handler delivers log entries as action results to the multiplexer, using a source name derived from the
 * logger, a type prefixed with {@code log-}, and a data string containing an ISO 8601 timestamp, the log level, and
 * the message or stack trace.</p>
 *
 * <p>If the handler is closed, the record is null, or the {@code outputMultiplexer} actor is not registered, the
 * record is silently discarded.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerLogHandler extends Handler {

    /** ISO 8601 formatter with timezone offset used to format log timestamps. */
    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** The system default timezone used to format log timestamps. */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    /** The actor system used to locate the {@code outputMultiplexer} actor. */
    private final IIActorSystem system;

    /** Indicates whether the handler is no longer accepting log records. */
    private volatile boolean closed = false;

    /**
     * Initializes a {@code MultiplexerLogHandler} that forwards log records to the {@code outputMultiplexer} actor
     * within the specified actor system.
     *
     * @param system the actor system containing the {@code outputMultiplexer} actor
     */
    public MultiplexerLogHandler(IIActorSystem system) {
        this.system = system;
    }

    /**
     * Sends a log record to the {@code outputMultiplexer} actor as a formatted message.
     *
     * <p>The record is sent to the actor with a JSON argument containing:
     * <ul>
     * <li>{@code source} - a short name derived from the logger name (e.g., {@code "turing-workflow"}, {@code
     * "pojo-actor"}, or the simple class name)</li>
     * <li>{@code type} - {@code "log-"} followed by the log level name (e.g., {@code "log-INFO"}, {@code
     * "log-SEVERE"})</li>
     * <li>{@code data} - a string containing an ISO 8601 timestamp, the log level, and the formatted message or stack
     * trace</li>
     * </ul>
     *
     * <p>If the handler is closed, the record is {@code null}, or the {@code outputMultiplexer} actor is not
     * registered, the record is silently discarded.
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
     * Performs no operation. Log entries are forwarded to the {@code outputMultiplexer} actor immediately upon calling
     * {@link #publish}.
     */
    @Override
    public void flush() {
    }

    /**
     * Closes the handler. After this call, any subsequent calls to {@link #publish} are silently discarded.
     *
     * @throws SecurityException if a security manager denies the operation
     */
    @Override
    public void close() throws SecurityException {
        closed = true;
    }
}
