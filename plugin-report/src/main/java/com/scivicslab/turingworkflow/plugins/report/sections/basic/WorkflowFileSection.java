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

package com.scivicslab.turingworkflow.plugins.report.sections.basic;

import com.scivicslab.turingworkflow.plugins.report.SectionBuilder;

/**
 * Generates a report section that displays the path to the workflow file.
 *
 * <ul>
 * <li><code>generate</code> returns a string containing the header "[Workflow File]" followed by the file path.</li>
 * <li><code>getTitle</code> always returns {@code null} because the title is embedded within the generated
 * content.</li>
 * </ul>
 *
 * <p>One instance holds the workflow file path provided via {@link #setWorkflowPath(String)}.</p>
 *
 * <p>If no path is set, the section outputs {@code (unknown)}.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowFileSection implements SectionBuilder {

    private String workflowPath;

    /**
     * Sets the workflow file path to be used in the generated report.
     *
     * @param path the path to the workflow YAML file
     */
    public void setWorkflowPath(String path) {
        this.workflowPath = path;
    }

    /**
     * Returns a string that is a block containing the workflow file path under the header "[Workflow File]".
     *
     * If no path has been set, the result is "[Workflow File]\n(unknown)\n".
     *
     * @return the formatted section content containing the path or "(unknown)"
     */
    @Override
    public String generate() {
        String path = workflowPath != null ? workflowPath : "(unknown)";
        return "[Workflow File]\n" + path + "\n";
    }

    /**
     * Returns {@code null} because the section title is already included within the string returned by {@code
     * generate()}.
     */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
