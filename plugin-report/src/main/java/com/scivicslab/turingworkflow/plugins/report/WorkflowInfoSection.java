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
 * Generates a report section containing workflow metadata, including the file path, name, and description.
 *
 * <p>It produces a formatted text block where each field is indented under a {@code [Workflow Info]} header. If a
 * description is present, it is placed under a separate {@code [Description]} header with each line indented.
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class WorkflowInfoSection implements ReportSection {

    private final String workflowPath;
    private final String name;
    private final String description;

    /**
     * Initializes a section that displays workflow metadata.
     *
     * @param workflowPath the path to the workflow file
     * @param name the name of the workflow
     * @param description the description of the workflow, which may be null
     */
    public WorkflowInfoSection(String workflowPath, String name, String description) {
        this.workflowPath = workflowPath;
        this.name = name;
        this.description = description;
    }

    /** Returns {@code null} to ensure the section content is printed directly without a separate title line. */
    @Override
    public String getTitle() {
        return null;  // No title - content is output directly
    }

    /**
     * Returns a string containing the workflow's file path, name, and description formatted under a {@code [Workflow
     * Info]} header.
     *
     * <p>The string includes the file path, the name if it is not empty, and the description if it is present. If a
     * description exists, it is placed under a {@code [Description]} header with each line indented.
     *
     * @return a formatted string of workflow metadata
     */
    @Override
    public String getContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Workflow Info]\n");
        sb.append("  File: ").append(workflowPath).append("\n");
        if (name != null && !name.isEmpty()) {
            sb.append("  Name: ").append(name).append("\n");
        }
        if (description != null && !description.isEmpty()) {
            sb.append("\n[Description]\n");
            for (String line : description.split("\n")) {
                sb.append("  ").append(line.trim()).append("\n");
            }
        }
        return sb.toString();
    }
}
