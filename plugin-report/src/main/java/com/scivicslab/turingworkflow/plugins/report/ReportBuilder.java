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

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.action.CallableByActionName;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.turingworkflow.workflow.ActorSystemAware;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.IIActorRefAware;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Generates a text report of a workflow execution by combining metadata, actor states, and dynamic sections from child
 * actors.
 *
 * <ul>
 * <li>{@code addWorkflowInfo} adds a section containing the workflow name and description extracted from its YAML
 * file.</li>
 * <li>{@code addJsonStateSection} adds a section containing the YAML-formatted state of a specified actor.</li>
 * <li>{@code report} builds the full report and sends it to the {@code outputMultiplexer} actor.</li>
 * </ul>
 *
 * <p>One instance serves one workflow at a time, as it collects sections and child actor results into a single
 * report.</p>
 *
 * <pre>{@code
 * actor: report-builder
 * method: report
 * }</pre>
 *
 * <p>If an action fails, it returns an action result where {@code isSuccess()} is false and the message contains the
 * error description. The {@code addJsonStateSection} action fails if the specified actor cannot be found or has no
 * state.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilder implements CallableByActionName, ActorSystemAware, IIActorRefAware {

    private static final String CLASS_NAME = ReportBuilder.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    private final List<ReportSection> sections = new ArrayList<>();
    private IIActorSystem system;
    private IIActorRef<?> selfRef;

    /** Initializes a new report builder that can be registered as a child actor via {@code loader.createChild}. */
    public ReportBuilder() {
    }

    /**
     * Sets the actor system used to locate other actors, such as {@code nodeGroup} for workflow paths or {@code
     * outputMultiplexer} for sending the final report, during the report building process.
     */
    @Override
    public void setActorSystem(IIActorSystem system) {
        this.system = system;
        logger.info("ReportBuilder: ActorSystem set");
    }

    /**
     * Sets a reference to this actor's own {@link IIActorRef}.
     *
     * <p>This allows the builder to discover and call child actor sections during the report build process.</p>
     *
     * @param actorRef the reference to this actor.
     */
    @Override
    public void setIIActorRef(IIActorRef<?> actorRef) {
        this.selfRef = actorRef;
        logger.info("ReportBuilder: IIActorRef set");
    }

    // ========================================================================
    // CallableByActionName implementation
    // ========================================================================

    /**
     * Executes a specific report-building action.
     *
     * <p>Returns an action result indicating whether the requested action succeeded, with the result message
     * containing either the generated report text or an error description.</p>
     *
     * <p>The following actions are available:</p>
     * <ul>
     * <li>{@code addWorkflowInfo} - adds a section containing the workflow's name and description</li>
     * <li>{@code addJsonStateSection} - adds a section containing a specified actor's state as YAML</li>
     * <li>{@code report} - builds the complete report and sends it to the output multiplexer</li>
     * </ul>
     *
     * <p>If the action name is not recognized, returns a failed action result with an error message.</p>
     *
     * @param actionName the name of the action to execute
     * @param args a JSON-formatted string of arguments specific to the action
     * @return an action result that is a success if the action completed, or a failure if the action name is unknown
     * or the execution failed
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.info("ReportBuilder.callByActionName: " + actionName);

        return switch (actionName) {
            case "addWorkflowInfo" -> addWorkflowInfo(args);
            case "addJsonStateSection" -> addJsonStateSection(args);
            case "report" -> report(args);
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Adds a section containing the workflow's name and description to the report.
     *
     * Returns an action result that is a success if the workflow path could be retrieved from the {@code nodeGroup}
     * actor and the corresponding YAML file was read, with a message indicating the section was added. If the workflow
     * path cannot be determined, the result is a failure with a message stating the path could not be retrieved.
     *
     * @param args the unused arguments for this action
     */
    private ActionResult addWorkflowInfo(String args) {
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
            logger.warning("ReportBuilder.addWorkflowInfo: Could not read workflow file: " + e.getMessage());
        }

        addSection(new WorkflowInfoSection(workflowPath, name, description));
        return new ActionResult(true, "Workflow info section added");
    }

    /**
     * Adds an actor's current state as a YAML-formatted section to the report.
     *
     * @param args a JSON string containing the key {@code actor} (the name of the target actor) and an optional {@code
     * path} (the file path for the YAML output).
     *
     * @return a success result if the actor was found and its state was successfully added; a failure result if the
     * arguments are malformed, the actor system is unavailable, the actor does not exist, or the actor has no state.
     */
    private ActionResult addJsonStateSection(String args) {
        String actorName;
        String path = "";

        try {
            JSONObject json = new JSONObject(args);
            actorName = json.getString("actor");
            path = json.optString("path", "");
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage() +
                ". Expected: {\"actor\": \"name\", \"path\": \"optional\"}");
        }

        if (system == null) {
            return new ActionResult(false, "ActorSystem not available");
        }

        IIActorRef<?> targetActor = system.getIIActor(actorName);
        if (targetActor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }

        JsonState jsonState = targetActor.json();
        if (jsonState == null) {
            return new ActionResult(false, "Actor has no JsonState: " + actorName);
        }

        String yamlContent = jsonState.toStringOfYaml(path);
        addSection(new JsonStateSection(actorName, yamlContent));

        return new ActionResult(true, "JsonState section added for " + actorName);
    }

    /**
     * Sends a complete execution report to the {@code outputMultiplexer} actor.
     *
     * @param args the arguments, which are not used
     * @return an action result that is a success and contains the full report text as its message
     */
    private ActionResult report(String args) {
        String reportContent = build();
        reportToMultiplexer(reportContent);
        return new ActionResult(true, reportContent);
    }

    // ========================================================================
    // Core Methods
    // ========================================================================

    /**
     * Adds a section to the report.
     *
     * @param section the section to add; if null or if the section's content is empty, the section is skipped
     */
    public void addSection(ReportSection section) {
        if (section != null) {
            sections.add(section);
        }
    }

    /**
     * Returns a formatted string containing the complete workflow execution report.
     *
     * <p>The report includes a header, followed by sections added via {@code addWorkflowInfo} or {@code
     * addJsonStateSection}, and finally sections generated by any child actors that implement the {@link
     * SectionBuilder} interface. Sections with null or empty content are omitted from the result.</p>
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("=== Workflow Execution Report ===\n");

        // 1. Output legacy sections first (in order added)
        for (ReportSection section : sections) {
            appendSection(sb, section.getTitle(), section.getContent());
        }

        // 2. Output child actor sections (in creation order)
        if (selfRef != null && system != null) {
            Set<String> childNames = selfRef.getNamesOfChildren();
            for (String childName : childNames) {
                IIActorRef<?> childRef = system.getIIActor(childName);
                if (childRef == null) continue;

                // Call generate action on the child
                ActionResult generateResult = childRef.callByActionName("generate", "");
                if (!generateResult.isSuccess()) {
                    // Child doesn't support generate action, skip it
                    continue;
                }

                String content = generateResult.getResult();
                if (content == null || content.isEmpty()) {
                    continue;
                }

                // Get title (may be empty)
                String title = null;
                ActionResult titleResult = childRef.callByActionName("getTitle", "");
                if (titleResult.isSuccess()) {
                    String t = titleResult.getResult();
                    if (t != null && !t.isEmpty()) {
                        title = t;
                    }
                }

                appendSection(sb, title, content);
            }
        }

        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (content != null && !content.isEmpty()) {
            if (title != null && !title.isEmpty()) {
                sb.append("\n--- ").append(title).append(" ---\n");
            }
            sb.append(content).append("\n");
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private String getWorkflowPathFromNodeGroup() {
        if (system == null) return null;

        IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    private void reportToMultiplexer(String data) {
        if (system == null) return;

        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) return;

        JSONObject arg = new JSONObject();
        arg.put("source", "report-builder");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }
}
