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

/**
 * Adds a section to a report containing the full content of the workflow YAML file.
 *
 * <ul><li>{@code generate} returns an action result containing the raw text of the workflow file.</li><li>{@code
 * getTitle} returns an action result containing the section title.</li></ul>
 *
 * One instance may serve several workflows or threads at once.
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowFileSectionActor extends IIActorRef<WorkflowFileSection> {

    /**
     * Initializes a new actor that adds a section containing the full content of the workflow YAML file to a report.
     *
     * @param actorName the name assigned to this actor
     * @param system the actor system in which this actor is registered
     */
    public WorkflowFileSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new WorkflowFileSection(), system);
        initializeFromWorkflow();
    }

    /**
     * Sets the workflow file path in the underlying section object by retrieving it from the {@code nodeGroup} actor.
     */
    private void initializeFromWorkflow() {
        String workflowPath = getWorkflowPath();
        if (workflowPath != null) {
            object.setWorkflowPath(workflowPath);
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

    /**
     * Returns the full text of the workflow YAML file as a section content.
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the entire content of the workflow
     * file.
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the title of the workflow file section as a string.
     *
     * @param args unused
     * @return an action result that is a success containing the section title string, or an empty string if no title
     * is available
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }
}
