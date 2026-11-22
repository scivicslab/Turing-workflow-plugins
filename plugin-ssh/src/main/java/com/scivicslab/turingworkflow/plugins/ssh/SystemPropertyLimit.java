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

import java.util.logging.Logger;

/** Reads a positive whole-number limit from a system property, falling back to a default. */
final class SystemPropertyLimit {

    private static final Logger LOG = Logger.getLogger(SystemPropertyLimit.class.getName());

    private SystemPropertyLimit() {
    }

    /**
     * Returns the value of the system property {@code name} as a positive number, or {@code defaultValue} when the
     * property is not set; a value that is not a positive whole number is logged and replaced by the default.
     */
    static long read(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        LOG.warning("System property " + name + "=" + value + " is not a positive whole number; using "
                + defaultValue);
        return defaultValue;
    }
}
