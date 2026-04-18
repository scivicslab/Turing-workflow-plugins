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
 * Log levels for distributed logging.
 *
 * <p>Each level has a numeric priority, where higher values indicate
 * more severe log events. Levels can be compared using {@link #isAtLeast(LogLevel)}
 * to support filtering by minimum severity.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public enum LogLevel {
    /** Debug-level messages for detailed diagnostic information. */
    DEBUG(0),
    /** Informational messages indicating normal operation. */
    INFO(1),
    /** Warning messages indicating potential issues that do not prevent execution. */
    WARN(2),
    /** Error messages indicating failures that may require attention. */
    ERROR(3);

    /** The numeric priority of this log level (higher = more severe). */
    private final int priority;

    /**
     * Constructs a log level with the specified numeric priority.
     *
     * @param priority the numeric priority (higher values indicate more severe levels)
     */
    LogLevel(int priority) {
        this.priority = priority;
    }

    /**
     * Returns the numeric priority of this log level.
     *
     * @return the priority value (0 for DEBUG, 1 for INFO, 2 for WARN, 3 for ERROR)
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Checks whether this log level is at least as severe as the given level.
     *
     * @param other the log level to compare against
     * @return {@code true} if this level's priority is greater than or equal to {@code other}'s priority
     */
    public boolean isAtLeast(LogLevel other) {
        return this.priority >= other.priority;
    }
}
