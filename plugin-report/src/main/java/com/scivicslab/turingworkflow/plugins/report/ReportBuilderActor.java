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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Assembles and outputs a multi-section execution report containing workflow metadata, actor states, and transition
 * history.
 *
 * <ul><li>{@code addWorkflowInfo} - Adds a section with the name and description extracted from the workflow YAML
 * file.</li><li>{@code addJsonStateSection} - Adds a YAML-formatted section containing the state of a specific
 * actor.</li><li>{@code report} - Combines all added sections into a single report and sends it to the {@code
 * outputMultiplexer}.</li></ul>
 *
 * <p>One instance of this actor may serve several workflows at once, as it builds a report from the sections added
 * during a single execution sequence.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilderActor extends IIActorRef<ReportBuilder> {

    private static final String CLASS_NAME = ReportBuilderActor.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    /**
     * Initializes a new {@code ReportBuilderActor} with a fresh {@link ReportBuilder} and links it to the provided
     * actor system.
     *
     * @param actorName the name to assign to this actor
     * @param system the actor system to which this actor belongs
     */
    public ReportBuilderActor(String actorName, IIActorSystem system) {
        super(actorName, new ReportBuilder(), system);
        this.object.setActorSystem(system);
        this.object.setIIActorRef(this);
    }

    /**
     * Initializes a new {@code ReportBuilderActor} with the specified name and builder.
     *
     * @param name the actor name
     * @param builder the {@link ReportBuilder} instance to be used for assembling the report
     */
    public ReportBuilderActor(String name, ReportBuilder builder) {
        super(name, builder);
    }

    /**
     * Initializes a new {@code ReportBuilderActor} using a specific {@link ReportBuilder} and an actor system.
     *
     * @param name the actor name
     * @param builder the report builder instance
     * @param system the actor system
     */
    public ReportBuilderActor(String name, ReportBuilder builder, IIActorSystem system) {
        super(name, builder, system);
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Adds a workflow information section to the report.
     *
     * <p>Retrieves the workflow file path from the {@code nodeGroup} actor, then
     * reads the workflow YAML file to extract the {@code name} and {@code description}
     * fields. The resulting {@link WorkflowInfoSection} is appended to the report.</p>
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} indicating success or failure with a descriptive message
     */
    /**
     * Arguments for adding an actor's state to the report.
     *
     * @param actor the name of the actor whose state is to be captured
     * @param path a JSON path narrowing the section to one part of that state; if absent, the whole state is used
     */
    public record JsonStateSectionArgs(@NotNull String actor, String path) {}

    @Action("addWorkflowInfo")
    public ActionResult addWorkflowInfo(String args) {
        logger.info("ReportBuilderActor.addWorkflowInfo");

        String workflowPath = getWorkflowPathFromNodeGroup();
        if (workflowPath == null) {
            return new ActionResult(false, "Could not get workflow path from nodeGroup");
        }

        String name = null;
        String description = null;

        // Try to read workflow YAML for name and description
        try {
            Path path = Paths.get(workflowPath);
            if (!Files.exists(path)) {
                path = Paths.get(System.getProperty("user.dir"), workflowPath);
            }

            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> data = yaml.load(is);
                    if (data != null) {
                        name = (String) data.get("name");
                        Object descObj = data.get("description");
                        description = descObj != null ? descObj.toString().trim() : null;
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("ReportBuilderActor.addWorkflowInfo: Could not read workflow file: " + e.getMessage());
        }

        this.object.addSection(new WorkflowInfoSection(workflowPath, name, description));
        return new ActionResult(true, "Workflow info section added");
    }

    /**
     * Appends a section to the report containing the specified actor's state in YAML format.
     *
     * @param args a record containing the name of the actor to inspect and an optional JSON path to a specific part of
     * its state
     *
     * @return an {@link ActionResult} that is a success with a message "JsonState section added for [actor]" if the
     * actor was found and had a state, or a failure if the actor system is unavailable, the actor does not exist, or
     * the actor has no {@link JsonState}
     */
    @Action(value = "addJsonStateSection", argsType = JsonStateSectionArgs.class)
    public ActionResult addJsonStateSection(JsonStateSectionArgs args) {
        logger.info("ReportBuilderActor.addJsonStateSection: actor=" + args.actor());

        String actorName = args.actor();
        String path = (args.path() != null) ? args.path() : "";

        if (system() == null) {
            return new ActionResult(false, "ActorSystem not available");
        }

        IIActorRef<?> targetActor = ((IIActorSystem) system()).getIIActor(actorName);
        if (targetActor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }

        JsonState jsonState = targetActor.json();
        if (jsonState == null) {
            return new ActionResult(false, "Actor has no JsonState: " + actorName);
        }

        String yamlContent = jsonState.toStringOfYaml(path);
        this.object.addSection(new JsonStateSection(actorName, yamlContent));

        return new ActionResult(true, "JsonState section added for " + actorName);
    }

    /**
     * Assembles the final report from all added sections and returns it as a single text block.
     *
     * <p>The result is a string containing the complete report, including all manually added sections and the output
     * from all registered child actor sections. If the report is successfully built, the action succeeds and the
     * result string holds the full report content.</p>
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success with the full report content as the result string, or a
     * failure if the report could not be built
     */
    @Action("report")
    public ActionResult report(String args) {
        logger.info("ReportBuilderActor.report");

        String reportContent = this.object.build();
        reportToMultiplexer(reportContent);

        return new ActionResult(true, reportContent);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Returns the workflow file path by querying the {@code nodeGroup} actor.
     *
     * <p>Returns the path string if the {@code nodeGroup} actor is found and successfully responds to the {@code
     * getWorkflowPath} action; otherwise, returns {@code null}.</p>
     */
    private String getWorkflowPathFromNodeGroup() {
        if (system() == null) return null;
        IIActorSystem iiSystem = (IIActorSystem) system();

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    /**
     * Sends the assembled report text to the {@code outputMultiplexer} actor.
     *
     * <p>The report is sent as a JSON string containing the source {@code "report-builder"}, the type {@code
     * "plugin-result"}, and the report text itself. If the actor system or the {@code outputMultiplexer} actor is not
     * available, the method does nothing.</p>
     *
     * @param data the full text of the report to be sent
     */
    private void reportToMultiplexer(String data) {
        if (system() == null) return;
        IIActorSystem iiSystem = (IIActorSystem) system();

        IIActorRef<?> multiplexer = iiSystem.getIIActor("outputMultiplexer");
        if (multiplexer == null) return;

        JSONObject arg = new JSONObject();
        arg.put("source", "report-builder");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }
}
