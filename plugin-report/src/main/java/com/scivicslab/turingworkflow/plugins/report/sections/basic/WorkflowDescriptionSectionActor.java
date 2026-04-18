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
 * Actor wrapper for {@link WorkflowDescriptionSection}.
 *
 * <p>Exposes the POJO's methods as actions via {@code @Action} annotations.
 * Handles workflow path resolution and YAML parsing.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowDescriptionSectionActor extends IIActorRef<WorkflowDescriptionSection> {

    private static final Logger logger = Logger.getLogger(WorkflowDescriptionSectionActor.class.getName());

    /**
     * Constructs the actor with a new POJO instance.
     *
     * @param actorName the actor name
     * @param system the actor system
     */
    public WorkflowDescriptionSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new WorkflowDescriptionSection(), system);
        initializeFromWorkflow();
    }

    /**
     * Initializes the POJO with workflow information from nodeGroup.
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
     * Generates the workflow description section content.
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with the generated section content
     */
    @Action("generate")
    public ActionResult generate(String args) {
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
}
