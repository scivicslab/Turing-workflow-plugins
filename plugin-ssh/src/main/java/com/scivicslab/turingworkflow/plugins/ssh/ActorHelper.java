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

import java.util.concurrent.ExecutionException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Provides static utility methods for formatting output to the {@code outputMultiplexer}, parsing JSON arguments from
 * workflow steps, and extracting error messages from execution failures.
 *
 * @author devteam@scivicslab.com
 * @since 2.10.0
 */
public final class ActorHelper {

    /** Prevents instantiation of this utility class. */
    private ActorHelper() {
        // Utility class
    }

    /**
     * Sends the formatted output to the {@code outputMultiplexer} actor, one line at a time.
     *
     * @param system the actor system containing the {@code outputMultiplexer}
     * @param sourceName the name of the actor producing the output
     * @param formatted the text to be sent, which is split into individual lines
     */
    public static void sendToMultiplexer(IIActorSystem system, String sourceName, String formatted) {
        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            return;
        }

        for (String line : formatted.split("\n")) {
            JSONObject arg = new JSONObject();
            arg.put("source", sourceName);
            arg.put("type", "stdout");
            arg.put("data", line);
            multiplexer.callByActionName("add", arg.toString());
        }
    }

    /**
     * Extracts a maximum number of iterations from a JSON array string.
     *
     * @param arg the JSON array string containing the iteration count, such as {@code "[10]"}
     * @param defaultValue the value to return if the argument is null, empty, or not a valid integer array
     * @return the integer value found at the first position of the array, or defaultValue if no valid integer could be
     * parsed
     */
    public static int parseMaxIterations(String arg, int defaultValue) {
        if (arg != null && !arg.isEmpty() && !arg.equals("[]")) {
            try {
                JSONArray args = new JSONArray(arg);
                if (args.length() > 0) {
                    return args.getInt(0);
                }
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        return defaultValue;
    }

    /**
     * Returns the first element of a JSON array string as a string, or an empty string if the input is null, empty, or
     * not a valid array.
     *
     * @param args the JSON array string to parse
     * @return the first element of the array, or an empty string if the array is empty or the input is invalid
     */
    public static String getFirst(String args) {
        if (args == null || args.isEmpty() || args.equals("[]")) {
            return "";
        }
        try {
            JSONArray jsonArray = new JSONArray(args);
            if (jsonArray.length() > 0) {
                return jsonArray.getString(0);
            }
        } catch (Exception e) {
            // Return empty string if parsing fails
        }
        return "";
    }

    /**
     * Returns a command string extracted from the first element of a JSON array.
     *
     * Throws an {@link IllegalArgumentException} if the argument is not a valid JSON array or if the array is empty.
     *
     * @param arg the JSON array string containing the command as its first element
     * @return the command string found at index 0 of the array
     */
    public static String extractCommandFromArgs(String arg) {
        try {
            JSONArray jsonArray = new JSONArray(arg);
            if (jsonArray.length() == 0) {
                throw new IllegalArgumentException("Command arguments cannot be empty");
            }
            return jsonArray.getString(0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid command argument format. Expected JSON array with command string: " + arg, e);
        }
    }

    /**
     * Returns a single string that merges the standard output and standard error of a command result.
     *
     * The result is the trimmed stdout, or if stderr is not empty, the trimmed stdout followed by the trimmed stderr
     * preceded by a newline and the marker "[stderr]".
     *
     * @param result the command result to merge
     */
    public static String combineOutput(Node.CommandResult result) {
        String output = result.getStdout().trim();
        String stderr = result.getStderr().trim();
        if (!stderr.isEmpty()) {
            output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
        }
        return output;
    }

    /**
     * Returns a string that is the message of the most specific underlying cause of an execution error.
     *
     * <p>The method traverses the exception chain starting from the cause of the provided {@code ExecutionException}
     * and returns the message of the first {@code java.io.IOException} encountered. If no {@code IOException} is found
     * in the chain, it returns the message of the immediate cause, or the message of the {@code ExecutionException}
     * itself if no cause exists.</p>
     *
     * @param e the execution exception to inspect
     * @return the message of the underlying {@code IOException} if found, otherwise the message of the immediate cause
     * or the original exception
     */
    public static String extractRootCauseMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        Throwable current = cause;
        while (current != null) {
            if (current instanceof java.io.IOException) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return cause != null ? cause.getMessage() : e.getMessage();
    }
}
