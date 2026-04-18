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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
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
 * Actor for building and outputting workflow reports.
 *
 * <p>This actor wraps a {@link ReportBuilder} POJO and provides workflow-callable
 * actions via {@code @Action} annotations.</p>
 *
 * <h2>Actions:</h2>
 * <ul>
 *   <li>{@code addWorkflowInfo} - Add workflow metadata section</li>
 *   <li>{@code addJsonStateSection} - Add actor's JsonState as YAML</li>
 *   <li>{@code report} - Build and output the report</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilderActor extends IIActorRef<ReportBuilder> {

    private static final String CLASS_NAME = ReportBuilderActor.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    /**
     * Constructs a new ReportBuilderActor with a new POJO instance.
     *
     * <p>Required by {@code loader.createChild} for dynamic instantiation.</p>
     *
     * @param actorName the actor name
     * @param system the actor system
     */
    public ReportBuilderActor(String actorName, IIActorSystem system) {
        super(actorName, new ReportBuilder(), system);
        this.object.setActorSystem(system);
        this.object.setIIActorRef(this);
    }

    /**
     * Constructs a new ReportBuilderActor.
     *
     * @param name the actor name
     * @param builder the ReportBuilder POJO to wrap
     */
    public ReportBuilderActor(String name, ReportBuilder builder) {
        super(name, builder);
    }

    /**
     * Constructs a new ReportBuilderActor with actor system.
     *
     * @param name the actor name
     * @param builder the ReportBuilder POJO to wrap
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
     * Adds a JsonState section for the specified actor to the report.
     *
     * <p>Retrieves the target actor's {@link JsonState} and converts it to YAML format.
     * The resulting {@link JsonStateSection} is appended to the report.</p>
     *
     * <p><strong>Expected args format (JSON):</strong></p>
     * <pre>{@code {"actor": "<actorName>", "path": "<optional JSON path>"}}</pre>
     *
     * @param args JSON string with {@code "actor"} (required) and {@code "path"} (optional) fields
     * @return an {@link ActionResult} indicating success or failure with a descriptive message
     */
    @Action("addJsonStateSection")
    public ActionResult addJsonStateSection(String args) {
        logger.info("ReportBuilderActor.addJsonStateSection: args=" + args);

        String actorName;
        String path = "";

        try {
            JSONObject json = new JSONObject(args);
            actorName = json.getString("actor");
            path = json.optString("path", "");
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage());
        }

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
     * Builds the complete report and sends it to the output multiplexer.
     *
     * <p>Delegates to {@link ReportBuilder#build()} to assemble all sections (both legacy
     * and child actor sections), then forwards the result to the {@code outputMultiplexer}
     * actor with source {@code "report-builder"} and type {@code "plugin-result"}.</p>
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with {@code success=true} and the full report content as the result string
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
     * Gets workflow path from nodeGroup actor.
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
     * Outputs report to outputMultiplexer.
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
