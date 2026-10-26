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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.logging.Logger;

/**
 * Adds a section to a report containing the state of a target actor formatted as YAML.
 *
 * <ul>
 * <li>{@code generate} returns an action result containing the YAML representation of the target actor's state.</li>
 * <li>{@code getTitle} returns an action result containing the section title, or an empty string if no title is
 * set.</li>
 * <li>{@code setTargetActor} sets the name of the actor whose state is to be reported.</li>
 * <li>{@code setJsonPath} sets a JSON path to filter the state; if the path is empty or null, the entire state is
 * reported.</li>
 * </ul>
 *
 * <p>The actor name can encode the target actor and an optional path using colons, such as {@code
 * "state:nodeGroup:cluster.nodes"}.</p>
 *
 * <p>An instance may serve several workflows at once.</p>
 *
 * <pre>{@code
 * actor: json-state-section
 * method: setTargetActor
 * arguments:
 *   actor: node-web1
 * actor: json-state-section
 * method: setJsonPath
 * arguments:
 *   path: cluster.nodes
 * actor: json-state-section
 * method: generate
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class JsonStateSectionActor extends IIActorRef<JsonStateSection> {

    private static final Logger logger = Logger.getLogger(JsonStateSectionActor.class.getName());

    private String targetActorName;
    private String jsonPath;

    /**
     * Initializes the actor to report the state of a target actor as a YAML section.
     *
     * @param actorName the actor name, which may encode the target actor and an optional JSON path using colons (e.g.,
     * "state:targetActor:path.to.key")
     *
     * @param system the actor system used to locate the target actor
     */
    public JsonStateSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new JsonStateSection(), system);
        parseActorName(actorName);
    }

    /** Extracts the target actor name and an optional JSON path from a colon-separated string. */
    private void parseActorName(String actorName) {
        if (actorName == null) {
            return;
        }

        // Format: "prefix:targetActor" or "prefix:targetActor:jsonPath"
        String[] parts = actorName.split(":", 3);
        if (parts.length >= 2) {
            this.targetActorName = parts[1];
            if (parts.length >= 3) {
                this.jsonPath = parts[2];
            }
        } else {
            this.targetActorName = actorName;
        }

        object.setActorName(this.targetActorName);
        object.setJsonPath(this.jsonPath);
    }

    /**
     * Updates the YAML content of the section by fetching the current state of the target actor.
     *
     * <p>If the target actor name is not set or the actor system is unavailable, the method does nothing. If the
     * target actor cannot be found in the system or if the target actor has no state to provide, the method returns
     * without updating the content and logs a warning.</p>
     */
    private void refreshContent() {
        if (targetActorName == null || actorSystem == null) {
            return;
        }

        if (!(actorSystem instanceof IIActorSystem)) {
            return;
        }
        IIActorSystem iiSystem = (IIActorSystem) actorSystem;

        IIActorRef<?> targetActor = iiSystem.getIIActor(targetActorName);
        if (targetActor == null) {
            logger.warning("JsonStateSectionActor: target actor not found: " + targetActorName);
            return;
        }

        JsonState jsonState = targetActor.json();
        if (jsonState == null) {
            logger.warning("JsonStateSectionActor: target actor has no JsonState: " + targetActorName);
            return;
        }

        String yamlContent = jsonState.toStringOfYaml(jsonPath);
        object.setYamlContent(yamlContent);
    }

    /**
     * Generates the JsonState section content.
     *
     * <p>Refreshes the YAML content from the target actor's {@link JsonState}
     * before generating, ensuring the latest state is always reported.</p>
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with the generated section content
     */
    /**
     * Specifies the name of the actor whose state is to be captured.
     *
     * @param actor the name of the target actor
     */
    public record TargetActorArgs(@NotNull String actor) {}

    /**
     * Specifies a JSON path to filter the state of a target actor.
     *
     * @param path a JSON path to a specific part of the state, or null to show the entire state
     */
    public record JsonPathArgs(String path) {}

    @Action("generate")
    public ActionResult generate(String args) {
        // Refresh content before generating
        refreshContent();
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns a string that is the title of the section.
     *
     * <p>The result is the title string, or an empty string if no title is set. The argument is unused.</p>
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the title string (empty if no title)
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }

    /**
     * Updates the name of the actor whose state is to be reported.
     *
     * Returns an action result that is a success with a message stating the new target actor name.
     *
     * @param args the name of the actor to target
     */
    @Action(value = "setTargetActor", argsType = TargetActorArgs.class)
    public ActionResult setTargetActor(TargetActorArgs args) {
        if (!args.actor().isEmpty()) {
            this.targetActorName = args.actor().trim();
            object.setActorName(this.targetActorName);
        }
        return new ActionResult(true, "Target actor set: " + targetActorName);
    }

    /**
     * Sets the JSON path filter used to extract a specific part of the target actor's state.
     *
     * @param args a {@code JsonPathArgs} containing the path string; if the path is null or empty, the filter is
     * cleared to show the entire state.
     *
     * @return an {@link ActionResult} indicating success and the text of the set path.
     */
    @Action(value = "setJsonPath", argsType = JsonPathArgs.class)
    public ActionResult setJsonPath(JsonPathArgs args) {
        String path = args.path();
        this.jsonPath = (path != null && !path.isEmpty()) ? path.trim() : null;
        object.setJsonPath(this.jsonPath);
        return new ActionResult(true, "JSON path set: " + jsonPath);
    }
}
