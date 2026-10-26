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
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Adds a section to a report containing the description extracted from the workflow's YAML file.
 *
 * <ul>
 * <li>{@code generate} returns an action result containing the workflow description text.</li>
 * <li>{@code getTitle} returns an action result containing the section title.</li>
 * </ul>
 *
 * <p>One instance may serve one workflow. It relies on the {@code nodeGroup} actor to resolve the workflow path.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowDescriptionSectionActor extends IIActorRef<WorkflowDescriptionSection> {

    private static final Logger logger = Logger.getLogger(WorkflowDescriptionSectionActor.class.getName());

    /**
     * Initializes a new actor that extracts and provides the description of the current workflow for a report.
     *
     * @param actorName the name to assign to this actor
     * @param system the actor system in which this actor is registered
     */
    public WorkflowDescriptionSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new WorkflowDescriptionSection(), system);
        initializeFromWorkflow();
    }

    /**
     * Populates the description field of the underlying section object by reading the workflow's YAML file.
     *
     * <p>It retrieves the workflow path from the {@code nodeGroup} actor and parses the {@code description} field from
     * the corresponding file. If the file cannot be found or the description is missing, the object remains
     * unchanged.</p>
     */
    private void initializeFromWorkflow() {
        String workflowPath = getWorkflowPath();
        if (workflowPath == null) {
            return;
        }

        String description = readDescriptionFromYaml(workflowPath);
        if (description != null) {
            object.setDescription(description);
        }
    }

    private String getWorkflowPath() {
        if (actorSystem == null || !(actorSystem instanceof IIActorSystem)) return null;
        IIActorSystem iiSystem = (IIActorSystem) actorSystem;

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    private String readDescriptionFromYaml(String workflowPath) {
        try {
            Path path = Paths.get(workflowPath);
            if (!Files.exists(path)) {
                path = Paths.get(System.getProperty("user.dir"), workflowPath);
            }

            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> data = yaml.load(is);
                    if (data != null && data.containsKey("description")) {
                        Object descObj = data.get("description");
                        return descObj != null ? descObj.toString().trim() : null;
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("WorkflowDescriptionSectionActor: Could not read workflow file: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the description of the workflow as the section content.
     *
     * @param args unused
     * @return an ActionResult that is a success with the workflow description text as its result
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the title of the workflow description section as a string.
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the section title, or an empty string
     * if no title is available
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }
}
