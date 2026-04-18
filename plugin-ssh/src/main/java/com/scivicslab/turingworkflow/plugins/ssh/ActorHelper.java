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
 * Shared helper methods used by both NodeActor and NodeGroupActor.
 *
 * <p>Eliminates code duplication between the two actor classes for
 * common operations like multiplexer output, argument parsing, and
 * error handling.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.10.0
 */
public final class ActorHelper {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ActorHelper() {
        // Utility class
    }

    /**
     * Sends formatted output to the outputMultiplexer, line by line.
     *
     * @param system the actor system
     * @param sourceName the source actor name
     * @param formatted the formatted output text
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
     * Parses a max iterations value from a JSON array argument.
     *
     * @param arg the JSON array string (e.g., "[10]")
     * @param defaultValue the default value if parsing fails
     * @return the parsed value or defaultValue
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
     * Extracts the first element from a JSON array string.
     * Returns empty string on failure (lenient).
     *
     * @param args the JSON array string
     * @return the first element, or empty string
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
     * Extracts a command string from JSON array arguments.
     * Throws on failure (strict).
     *
     * @param arg the JSON array string
     * @return the extracted command string
     * @throws IllegalArgumentException if the argument is invalid or empty
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
     * Combines stdout and stderr from a CommandResult into a single string.
     *
     * @param result the command result
     * @return combined output string
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
     * Extracts a meaningful error message from an ExecutionException.
     *
     * @param e the execution exception
     * @return the root cause message
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
