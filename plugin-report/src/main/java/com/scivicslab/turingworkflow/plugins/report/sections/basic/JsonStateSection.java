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
 * Generates a report section containing an actor's state in YAML format.
 *
 * <p>The section consists of:
 * <ul>
 * <li>{@code generate()}: Returns a formatted block starting with a title like {@code [JsonState: actorName]} (and an
 * optional path filter) followed by the YAML content.</li>
 * <li>{@code getTitle()}: Returns {@code null} because the title is embedded within the generated content.</li>
 * </ul>
 *
 * <p>If no YAML content has been set, {@code generate()} returns an empty string.
 *
 * <p>One instance may serve several workflows or threads at once.
 *
 * <p>Example of a SectionBuilder implementation:
 * <pre>{@code
 * public class MySection implements SectionBuilder {
 *     @Override
 *     public String generate() {
 *         return "data: value";
 *     }
 *     @Override
 *     public String getTitle() {
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class JsonStateSection implements SectionBuilder {

    private String actorName;
    private String yamlContent;
    private String jsonPath;

    /**
     * Sets the name of the actor to be displayed in the section title.
     *
     * @param actorName the name of the actor to show in the title
     */
    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    /**
     * Sets the YAML-formatted content that will be included in the section.
     *
     * @param yamlContent the YAML string representing the actor's state
     */
    public void setYamlContent(String yamlContent) {
        this.yamlContent = yamlContent;
    }

    /**
     * Sets the JSON path filter used to specify a subset of the actor's state.
     *
     * @param jsonPath the JSON path filter to apply, or null to include the entire state
     */
    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    /**
     * Returns a string that is a formatted block containing the actor's state in YAML format.
     *
     * The result is a block starting with a title in the form `[JsonState: <actorName>]`, optionally followed by
     * `(path: <jsonPath>)` if a JSON path filter is set, and ending with the provided YAML content.
     *
     * If no YAML content has been set, or if the content is an empty string, it returns an empty string.
     *
     * <pre>{@code
     * // Example of what the generated string looks like:
     * // [JsonState: my-actor] (path: $.status)
     * // status: OK
     * // value: 10
     * }</pre>
     *
     * @return the formatted JsonState section content, or an empty string if no content is available.
     */
    @Override
    public String generate() {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return "";  // No content, skip this section
        }

        StringBuilder sb = new StringBuilder();
        String title = "[JsonState: " + (actorName != null ? actorName : "unknown") + "]";
        if (jsonPath != null && !jsonPath.isEmpty()) {
            title += " (path: " + jsonPath + ")";
        }
        sb.append(title).append("\n");
        sb.append(yamlContent);
        if (!yamlContent.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns {@code null}.
     *
     * <p>The section title is instead embedded within the string returned by {@link #generate()}.
     */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
