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
 * Defines a single block of text and a title to be included in a workflow execution report.
 *
 * <ul><li>{@link #getTitle()} returns the title string for the section.</li><li>{@link #getContent()} returns the body
 * text of the section.</li></ul>
 *
 * <p>Sections are output in the order they were added to the report builder.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public interface ReportSection {

    /**
     * Returns the title of the report section.
     *
     * <p>If the title is null or empty, the section content is output without a title line. A null or empty title does
     * not prevent the content from being included in the report.</p>
     *
     * @return the section title, or null if no title is provided
     */
    String getTitle();

    /**
     * Returns the section content as a string.
     *
     * <p>If the returned string is null or empty, the section is skipped in the report.
     *
     * @return the section content
     */
    String getContent();
}
