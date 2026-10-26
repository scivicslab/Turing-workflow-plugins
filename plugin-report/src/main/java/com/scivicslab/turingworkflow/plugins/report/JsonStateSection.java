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
 * Generates a report section that displays an actor's state as a YAML-formatted block.
 *
 * <ul><li>{@code getTitle()} returns a string in the form {@code "Collected Data (<actorName>)"}.</li><li>{@code
 * getContent()} returns the YAML-formatted state content.</li></ul>
 *
 * <p>One instance is used for a single actor's state snapshot.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class JsonStateSection implements ReportSection {

    private final String actorName;
    private final String yamlContent;

    /**
     * Initializes a report section that displays an actor's state as YAML.
     *
     * @param actorName the name of the actor whose data is being displayed
     * @param yamlContent the YAML-formatted string representing the actor's state
     */
    public JsonStateSection(String actorName, String yamlContent) {
        this.actorName = actorName;
        this.yamlContent = yamlContent;
    }

    /**
     * Returns a string that is the section title in the form {@code "Collected Data (<actorName>)"}.
     *
     * @return the title string containing the name of the actor whose data is being displayed
     */
    @Override
    public String getTitle() {
        return "Collected Data (" + actorName + ")";
    }

    /** Returns the YAML-formatted content of the actor's state. */
    @Override
    public String getContent() {
        return yamlContent;
    }
}
