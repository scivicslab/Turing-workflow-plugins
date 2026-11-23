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

package com.scivicslab.turingworkflow.plugins.inventory;

import java.util.List;

import org.json.JSONObject;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import com.scivicslab.turingworkflow.plugins.logoutput.WorkflowStreamingAccumulator;
import com.scivicslab.turingworkflow.plugins.ssh.Node;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.Transition;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

/**
 * Executes workflows that orchestrate groups of nodes defined in an Ansible-style inventory.
 *
 * <p>The user gets a workflow interpreter that can drive multiple nodes simultaneously, with integrated logging to a
 * distributed store and visual feedback via ASCII art transitions.</p>
 *
 * <ul>
 * <li><code>createNodesForGroup</code> returns a list of {@link Node} objects for the specified group name.</li>
 * <li><code>createLocalNode</code> returns a list containing a single local host {@link Node}.</li>
 * <li><code>getInventory</code> returns the loaded {@link InventoryParser.Inventory} or {@code null} if no inventory
 * is loaded.</li>
 * <li><code>getNodeGroup</code> returns the underlying {@link NodeGroup} instance.</li>
 * <li><code>setOverlayDir</code> sets the path to a directory containing YAML files that override the workflow
 * configuration.</li>
 * <li><code>getOverlayDir</code> returns the current overlay directory path or {@code null}.</li>
 * <li><code>setVerbose</code> enables or disables detailed YAML output for every transition.</li>
 * <li><code>isVerbose</code> returns whether verbose mode is active.</li>
 * <li><code>setAccumulator</code> sets the accumulator used to render workflow transitions as ASCII art.</li>
 * <li><code>getAccumulator</code> returns the current transition accumulator or {@code null}.</li>
 * <li><code>setLogStore</code> configures the distributed log store, its actor reference, the database executor, and
 * the current session ID.</li>
 * <li><code>getLogStore</code> returns the synchronous log store instance or {@code null}.</li>
 * <li><code>getLogStoreActor</code> returns the actor reference for asynchronous logging or {@code null}.</li>
 * <li><code>getDbExecutor</code> returns the managed thread pool used for database writes or {@code null}.</li>
 * <li><code>getSessionId</code> returns the current session ID or -1 if not set.</li>
 * </ul>
 *
 * <p>The class holds a reference to a {@link NodeGroup}, an optional overlay directory, a log store, and a dedicated
 * database executor service. One instance may serve a single workflow execution at a time.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class NodeGroupInterpreter extends Interpreter {

    /** The underlying inventory and node configuration used to drive the actor's actions. */
    private final NodeGroup nodeGroup;

    /**
     * The path to a directory containing an {@code overlay-conf.yaml} file used to override workflow configurations.
     */
    private String overlayDir;

    /**
     * Whether the full YAML transition definition is displayed in the output.
     *
     * <p>When true, each transition displays the complete YAML content; when false, it displays only a truncated
     * version of at most 10 lines.</p>
     */
    private boolean verbose = false;

    /**
     * The accumulator used for displaying workflow steps with cowsay ASCII art. If set, workflow step transitions are
     * rendered through this accumulator.
     */
    private WorkflowStreamingAccumulator accumulator = null;

    /** Actor reference for the distributed log store used to send log messages asynchronously. */
    private ActorRef<DistributedLogStore> logStoreActor;

    /** The log store used for synchronous read operations. */
    private DistributedLogStore logStore;

    /**
     * Executes database write tasks in a single-threaded manner to ensure serialized logging of workflow transitions.
     */
    private ManagedThreadPool dbExecutor;

    /** The unique identifier for the current workflow execution. */
    private long sessionId = -1;

    /**
     * Initializes a new workflow interpreter that uses the specified {@link NodeGroup} to manage inventory and nodes
     * within the given {@link IIActorSystem}.
     *
     * @param nodeGroup the {@link NodeGroup} instance to be used for inventory and node operations
     * @param system the actor system used for workflow execution and actor communication
     */
    public NodeGroupInterpreter(NodeGroup nodeGroup, IIActorSystem system) {
        super();
        this.nodeGroup = nodeGroup;
        this.system = system;
    }

    /**
     * Returns a list of {@link Node} objects for all hosts belonging to the specified group.
     *
     * @param groupName the name of the group as defined in the inventory file
     * @return a list of {@link Node} objects representing the hosts in the group
     */
    public List<Node> createNodesForGroup(String groupName) {
        return nodeGroup.createNodesForGroup(groupName);
    }

    /**
     * Returns a list containing a single {@link Node} configured for local execution.
     *
     * @return a list containing one {@link Node} representing the localhost.
     */
    public List<Node> createLocalNode() {
        return nodeGroup.createLocalNode();
    }

    /**
     * Returns the loaded inventory data from the parsed file.
     *
     * @return the {@link InventoryParser.Inventory} object, or {@code null} if no inventory has been loaded
     */
    public InventoryParser.Inventory getInventory() {
        return nodeGroup.getInventory();
    }

    /** Returns the underlying {@link NodeGroup} instance used for inventory and node management. */
    public NodeGroup getNodeGroup() {
        return nodeGroup;
    }

    /**
     * Sets the path to a directory containing an overlay-conf.yaml file, which is used to override workflow
     * configurations.
     *
     * @param overlayDir the path to the directory containing the overlay configuration file
     */
    public void setOverlayDir(String overlayDir) {
        this.overlayDir = overlayDir;
    }

    /**
     * Returns the path to the directory containing {@code overlay-conf.yaml}, or {@code null} if no overlay directory
     * is set.
     */
    public String getOverlayDir() {
        return overlayDir;
    }

    /**
     * Enables or disables verbose mode for detailed output.
     *
     * <p>If {@code verbose} is true, the interpreter displays the full YAML transition definition for each step
     * instead of a truncated version. If false, only the first 10 lines of the transition are shown via the
     * accumulator.</p>
     *
     * @param verbose true to enable full YAML output for every transition, false to show only the first 10 lines.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /** Returns whether verbose mode is enabled. */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Assigns the accumulator used to display workflow steps with ASCII art.
     *
     * @param accumulator the accumulator to use for cowsay display
     */
    public void setAccumulator(WorkflowStreamingAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    /**
     * Returns the accumulator used to display workflow steps as ASCII art.
     *
     * @return the cowsay accumulator, or {@code null} if not set
     */
    public WorkflowStreamingAccumulator getAccumulator() {
        return accumulator;
    }

    /**
     * Configures the logging infrastructure used to record workflow execution results.
     *
     * @param logStore the instance used for synchronous read operations
     * @param logStoreActor the actor reference used for asynchronous write operations
     * @param dbExecutor the managed thread pool used to run database write tasks
     * @param sessionId the unique identifier for the current execution session
     */
    public void setLogStore(DistributedLogStore logStore,
                            ActorRef<DistributedLogStore> logStoreActor,
                            ManagedThreadPool dbExecutor, long sessionId) {
        this.logStore = logStore;
        this.logStoreActor = logStoreActor;
        this.dbExecutor = dbExecutor;
        this.sessionId = sessionId;
    }

    /**
     * Returns the log store used for synchronous read operations.
     *
     * @return the log store, or null if not set
     */
    public DistributedLogStore getLogStore() {
        return logStore;
    }

    /**
     * Returns the actor reference used to send asynchronous log messages to the distributed log store.
     *
     * @return the actor reference for the log store, or {@code null} if not set
     */
    public ActorRef<DistributedLogStore> getLogStoreActor() {
        return logStoreActor;
    }

    /**
     * Returns the executor service used for serialized writes to the distributed log store, or {@code null} if not
     * set.
     */
    public ManagedThreadPool getDbExecutor() {
        return dbExecutor;
    }

    /** Returns the unique identifier for the current workflow execution, or -1 if no session is active. */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * Sends the transition details to the output multiplexer and the distributed log store.
     *
     * <p>The transition's YAML representation is sent as a cowsay-formatted message to the output multiplexer, and the
     * transition entry is logged asynchronously to the log store. If verbose mode is enabled, the full transition YAML
     * is also sent to the multiplexer. If no accumulator is set, the transition details are sent as plain text.</p>
     *
     * @param transition the transition being entered
     */
    @Override
    protected void onEnterTransition(Transition transition) {
        // Get workflow name
        String workflowName = (getCode() != null && getCode().getName() != null)
                ? getCode().getName()
                : "unknown-workflow";

        // Get YAML-formatted output (first 10 lines for cowsay)
        String yamlText = transition.toYamlString(10).trim();

        // Render cowsay output
        String cowsayOutput;
        if (accumulator != null) {
            cowsayOutput = accumulator.renderCowsay(workflowName, yamlText);
        } else {
            // Fallback to simple text if no accumulator
            cowsayOutput = "[" + workflowName + "]\n" + yamlText;
        }

        // Send cowsay output to outputMultiplexer (loose coupling via ActorSystem)
        IIActorRef<?> multiplexer = system.getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME);
        // Use actor name as source (consistent across all output)
        String actorName = selfActorRef != null ? selfActorRef.getName() : "unknown";
        if (multiplexer != null) {
            JSONObject arg = new JSONObject();
            arg.put("source", actorName);
            arg.put("type", "cowsay");
            arg.put("data", cowsayOutput);
            multiplexer.callByActionName("add", arg.toString());
        }

        // In verbose mode, also send the full YAML
        if (verbose) {
            String fullYaml = transition.toYamlString(-1);
            String verboseOutput = "--- Full transition YAML ---\n" + fullYaml + "\n----------------------------";
            if (multiplexer != null) {
                JSONObject arg = new JSONObject();
                arg.put("source", actorName);
                arg.put("type", "verbose");
                arg.put("data", verboseOutput);
                multiplexer.callByActionName("add", arg.toString());
            }
        }

        // Log to distributed log store asynchronously
        if (logStoreActor != null && sessionId >= 0) {
            String label = transition.getLabel();
            if (label == null && transition.getStates() != null && transition.getStates().size() >= 2) {
                label = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
            }
            final String finalLabel = label;
            final String message = "Entering transition: " + yamlText.split("\n")[0];
            // Fire-and-forget: don't wait for DB write to complete
            logStoreActor.tell(
                store -> store.log(sessionId, "nodeGroup", finalLabel, LogLevel.INFO, message),
                dbExecutor);
        }
    }

    /**
     * Logs the outcome of a transition to the distributed log store.
     *
     * <p>If the log store actor is available and the session ID is valid, it records a log entry containing the
     * transition label, the success status, any associated note, and the result message. If the transition succeeded,
     * the log level is {@code INFO}; if it failed, the level is {@code WARN}. The result message is truncated to 500
     * characters if it is longer.</p>
     *
     * @param transition the transition that was attempted
     * @param success true if the transition succeeded, false if it failed
     * @param result the action result containing the message from the transition's actions
     */
    @Override
    protected void onExitTransition(Transition transition, boolean success, ActionResult result) {
        if (logStoreActor == null || sessionId < 0) {
            return;
        }

        String label = transition.getLabel();
        if (label == null && transition.getStates() != null && transition.getStates().size() >= 2) {
            label = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
        }
        final String finalLabel = label;

        // Get note (up to 60 chars or first line)
        String note = getTransitionNote(transition);

        String status = success ? "SUCCESS" : "FAILED";
        String resultMsg = result != null ? result.getResult() : "";
        // Truncate long result messages
        if (resultMsg.length() > 500) {
            resultMsg = resultMsg.substring(0, 500) + "...";
        }
        // Include note in message if present
        final String message = "Transition " + status + ": " + finalLabel +
                (note.isEmpty() ? "" : " [" + note + "]") +
                (resultMsg.isEmpty() ? "" : " - " + resultMsg);

        LogLevel level = success ? LogLevel.INFO : LogLevel.WARN;

        // Fire-and-forget: don't wait for DB write to complete
        logStoreActor.tell(
            store -> store.log(sessionId, "nodeGroup", finalLabel, level, message),
            dbExecutor);
    }

    /**
     * Returns a string that is a truncated version of the transition's note, containing at most 60 characters or only
     * the first line.
     *
     * If the note is null or empty, returns an empty string.
     */
    private String getTransitionNote(Transition transition) {
        String note = transition.getNote();
        if (note == null || note.isEmpty()) {
            return "";
        }
        // First line only
        int newlineIdx = note.indexOf('\n');
        if (newlineIdx > 0) {
            note = note.substring(0, newlineIdx);
        }
        // Up to 60 chars
        if (note.length() > 60) {
            note = note.substring(0, 57) + "...";
        }
        return note.trim();
    }
}
