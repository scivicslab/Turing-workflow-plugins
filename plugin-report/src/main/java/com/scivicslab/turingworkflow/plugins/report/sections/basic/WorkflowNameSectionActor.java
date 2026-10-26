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
 * Extracts the name of a workflow from its YAML file and provides it as a section in a report.
 *
 * <ul>
 * <li>{@code generate} returns an action result containing the workflow name as the section content.</li>
 * <li>{@code getTitle} returns an action result containing the section title.</li>
 * </ul>
 *
 * <p>One instance may serve a single workflow's report generation.</p>
 *
 * <p>If the workflow file cannot be found or the {@code name} field is missing from the YAML, the section content is
 * empty.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowNameSectionActor extends IIActorRef<WorkflowNameSection> {

    private static final Logger logger = Logger.getLogger(WorkflowNameSectionActor.class.getName());

    /**
     * Initializes the actor by extracting the workflow name from the YAML file at the path provided by the {@code
     * nodeGroup} actor.
     */
    public WorkflowNameSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new WorkflowNameSection(), system);
        initializeFromWorkflow();
    }

    /**
     * Sets the workflow path and name in the underlying section by querying the {@code nodeGroup} actor and parsing
     * the resulting YAML file.
     */
    private void initializeFromWorkflow() {
        String workflowPath = getWorkflowPath();
        if (workflowPath == null) {
            return;
        }

        object.setWorkflowPath(workflowPath);

        // Try to read name from YAML
        String name = readNameFromYaml(workflowPath);
        if (name != null) {
            object.setWorkflowName(name);
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

    private String readNameFromYaml(String workflowPath) {
        try {
            Path path = Paths.get(workflowPath);
            if (!Files.exists(path)) {
                path = Paths.get(System.getProperty("user.dir"), workflowPath);
            }

            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> data = yaml.load(is);
                    if (data != null && data.containsKey("name")) {
                        return (String) data.get("name");
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("WorkflowNameSectionActor: Could not read workflow file: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns an action result containing the workflow name section content.
     *
     * @param args unused
     * @return an action result that is a success and whose message holds the generated section content
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns a string that is the title of the workflow name section.
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the title string, or an empty string
     * if no title is available
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }
}
