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
 * POJO section builder that outputs the workflow description.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link WorkflowDescriptionSectionActor} to expose as an actor.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowDescriptionSection implements SectionBuilder {

    private String description;

    /**
     * Sets the workflow description.
     *
     * @param description the workflow description from YAML
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generates a {@code [Description]} block with each line of the description
     * indented by two spaces. Returns an empty string if no description has been set,
     * causing the section to be skipped in the report.</p>
     *
     * @return the formatted description content, or empty string if no description
     */
    @Override
    public String generate() {
        if (description == null || description.isEmpty()) {
            return "";  // No description, skip this section
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Description]\n");
        for (String line : description.split("\n")) {
            sb.append("  ").append(line.trim()).append("\n");
        }
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code null} because the title is embedded within the
     * generated content.</p>
     */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
