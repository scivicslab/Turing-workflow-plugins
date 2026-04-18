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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.logging.Logger;

/**
 * Actor wrapper for {@link JsonStateSection}.
 *
 * <p>Exposes the POJO's methods as actions via {@code @Action} annotations.
 * Retrieves JsonState from a target actor and formats it as YAML.</p>
 *
 * <p>The actor name encodes the target actor using ":" separator:</p>
 * <ul>
 *   <li>"state:nodeGroup" - outputs nodeGroup's JsonState</li>
 *   <li>"state:nodeGroup:cluster.nodes" - outputs only the "cluster.nodes" path</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class JsonStateSectionActor extends IIActorRef<JsonStateSection> {

    private static final Logger logger = Logger.getLogger(JsonStateSectionActor.class.getName());

    private String targetActorName;
    private String jsonPath;

    /**
     * Constructs the actor with a new POJO instance.
     *
     * @param actorName the actor name (may encode target actor)
     * @param system the actor system
     */
    public JsonStateSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new JsonStateSection(), system);
        parseActorName(actorName);
    }

    /**
     * Parses the actor name to extract target actor and optional path.
     */
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
     * Refreshes the YAML content from the target actor's JsonState.
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
    @Action("generate")
    public ActionResult generate(String args) {
        // Refresh content before generating
        refreshContent();
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the section title.
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with the title string (empty if no title)
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }

    /**
     * Sets the target actor name dynamically.
     *
     * @param args the target actor name
     * @return action result
     */
    @Action("setTargetActor")
    public ActionResult setTargetActor(String args) {
        if (args != null && !args.isEmpty()) {
            this.targetActorName = args.trim();
            object.setActorName(this.targetActorName);
        }
        return new ActionResult(true, "Target actor set: " + targetActorName);
    }

    /**
     * Sets the JSON path filter dynamically.
     *
     * @param args the JSON path
     * @return action result
     */
    @Action("setJsonPath")
    public ActionResult setJsonPath(String args) {
        this.jsonPath = (args != null && !args.isEmpty()) ? args.trim() : null;
        object.setJsonPath(this.jsonPath);
        return new ActionResult(true, "JSON path set: " + jsonPath);
    }
}
