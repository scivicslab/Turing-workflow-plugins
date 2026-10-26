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

package com.scivicslab.turingworkflow.plugins.logdb;

/**
 * Represents the severity levels used to categorize and filter workflow execution logs.
 *
 * <p>Each level has a numeric priority where higher values indicate more severe events. Levels can be compared to
 * support filtering by minimum severity.</p>
 *
 * <ul>
 * <li>{@link #getPriority()} returns the integer priority of the level (0 for {@code DEBUG}, 1 for {@code INFO}, 2 for
 * {@code WARN}, 3 for {@code ERROR}).</li>
 * <li>{@link #isAtLeast(LogLevel)} returns {@code true} if this level's priority is greater than or equal to the
 * priority of the specified level.</li>
 * </ul>
 *
 * <p>Example of filtering logs in a workflow action:</p>
 * <pre>{@code
 * if (currentLevel.isAtLeast(LogLevel.WARN)) {
 *     // perform high-severity logic
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public enum LogLevel {
    /**
     * DEBUG(0) Detailed diagnostic information for debugging.
     * INFO(1) Normal operational messages.
     * WARN(2) Potential issues that do not prevent execution.
     * ERROR(3) Failures that may require attention.
     */
    DEBUG(0),
    /** Informational messages indicating normal operation. */
    INFO(1),
    /** Warning messages indicating potential issues that do not prevent execution. */
    WARN(2),
    /** Error messages indicating failures. */
    ERROR(3);

    /** The numeric priority of this log level, where higher values indicate more severe events. */
    private final int priority;

    /**
     * Assigns a numeric priority to a log level, where higher values indicate more severe log events.
     *
     * @param priority the numeric priority of the level
     */
    LogLevel(int priority) {
        this.priority = priority;
    }

    /**
     * Returns the numeric priority of this log level, where higher values indicate more severe events.
     *
     * @return the priority value (0 for {@code DEBUG}, 1 for {@code INFO}, 2 for {@code WARN}, 3 for {@code ERROR})
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns {@code true} if this log level's priority is greater than or equal to the priority of the specified
     * level.
     *
     * @param other the log level to compare against
     * @return {@code true} if this level is as severe as or more severe than {@code other}
     */
    public boolean isAtLeast(LogLevel other) {
        return this.priority >= other.priority;
    }
}
