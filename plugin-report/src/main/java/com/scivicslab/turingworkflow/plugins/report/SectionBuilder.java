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

package com.scivicslab.turingworkflow.plugins.report;

/**
 * Defines the logic for generating a dynamic section of a workflow execution report.
 *
 * <ul>
 * <li>{@code generate()} returns the text content of the section.</li>
 * <li>{@code getTitle()} returns the title of the section.</li>
 * </ul>
 *
 * <p>If {@code getTitle()} returns null or an empty string, the section content is included in the report without a
 * title line.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public interface SectionBuilder {

    /**
     * Returns a string containing the body of the report section.
     *
     * @return the section content as a string
     */
    String generate();

    /**
     * Returns the title of the section.
     *
     * @return the section title, or {@code null} if there is no title
     */
    default String getTitle() {
        return null;
    }
}
