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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import com.scivicslab.pojoactor.core.ManagedThreadPool;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import com.scivicslab.turingworkflow.plugins.ssh.ActorHelper;
import com.scivicslab.turingworkflow.plugins.ssh.Node;
import com.scivicslab.turingworkflow.plugins.ssh.NodeInterpreter;
import com.scivicslab.turingworkflow.plugins.ssh.NodeActor;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

/**
 * An actor reference for {@link NodeGroupInterpreter} that orchestrates groups of infrastructure nodes and executes
 * workflows across them.
 *
 * <p>The actor offers the following actions:
 * <ul>
 * <li>{@code createNodeActors} creates child actors for each host in a specified inventory group.</li>
 * <li>{@code apply} runs a specific action on all child actors matching a wildcard pattern.</li>
 * <li>{@code executeCommandOnAllNodes} runs a shell command on every child node.</li>
 * <li>{@code runWorkflow} loads and executes a workflow file on this node group.</li>
 * <li>{@code runUntilEnd} executes the current workflow until it reaches the "end" state.</li>
 * <li>{@code loadInventory} reads the inventory file a workflow names, under the workflow's base directory.</li>
 * <li>{@code hasInventory} returns whether an inventory is currently loaded.</li>
 * <li>{@code hasAccumulator} returns whether an output multiplexer is registered.</li>
 * <li>{@code getAccumulatorSummary} returns the collected results from the output multiplexer.</li>
 * <li>{@code getSessionId} returns the current session ID.</li>
 * <li>{@code printJson} and {@code printYaml} output the current state as formatted JSON or YAML.</li>
 * </ul>
 *
 * <p>A workflow makes a node group itself with {@code loader.createChild}, which uses the
 * {@code (String, IIActorSystem)} constructor, and then names its inventory file:
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["jexl:self", "nodeGroup", "com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor"]
 * - actor: nodeGroup
 *   method: loadInventory
 *   arguments: {path: inventory.ini}
 * }</pre>
 *
 * <p>When {@code createNodeActors} is called, it establishes a hierarchy where this actor is the parent and each host
 * becomes a child actor named {@code node-<hostname>}.
 *
 * <p>One instance may serve several workflows at once, as child actors are created for each node.
 *
 * <p>Example workflow step:
 * <pre>{@code
 * - states: [0, 1]
 *   actions:
 *     - actor: nodeGroup
 *       method: createNodeActors
 *       arguments:
 *         group: web-servers
 * - states: [1, end]
 *   actions:
 *     - actor: nodeGroup
 *       method: apply
 *       arguments:
 *         actor: "node-*"
 *         method: runWorkflow
 *         arguments:
 *           workflowFile: deploy.yaml
 * }</pre>
 *
 * <p>If an action fails, the result is not a success and the message contains the error details. If {@code apply} is
 * used and some nodes fail, the result is not a success and the message lists the specific failures.
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class NodeGroupActor extends IIActorRef<NodeGroupInterpreter> {

    /** A logger used to record contextual information about the actor's operations. */
    Logger logger = null;

    /** Holds the file system path of the workflow currently being executed. */
    private String currentWorkflowPath = null;

    /**
     * Initializes a new actor reference for a {@link NodeGroupInterpreter} with the given name.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeGroupInterpreter} instance managed by this actor reference
     */
    public NodeGroupActor(String actorName, NodeGroupInterpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Initializes a node group with no inventory yet, as {@code loader.createChild} makes it; the workflow then names
     * the inventory file with {@code loadInventory}.
     *
     * @param actorName the name of this actor
     * @param system the actor system to register in
     */
    public NodeGroupActor(String actorName, IIActorSystem system) {
        this(actorName, new NodeGroupInterpreter(new NodeGroup(), system), system);
    }

    /** Initializes a new {@code NodeGroupActor} that links a {@link NodeGroupInterpreter} to an actor system. */
    public NodeGroupActor(String actorName, NodeGroupInterpreter object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);

        // Set the selfActorRef in the Interpreter (NodeGroupInterpreter extends Interpreter)
        object.setSelfActorRef(this);
    }

    // ========================================================================
    // Workflow Actions (from Interpreter)
    // ========================================================================

    /**
     * Executes the current step code.
     *
     * @param args the argument string (not used)
     * @return ActionResult indicating success or failure
     */
    /**
     * Holds a file system path used to locate a workflow definition or a JSON state file.
     *
     * @param path the path to the file on the file system
     */
    public record PathArgs(@NotNull String path) {}

    /** Holds a non-null string of text. */
    public record TextArgs(@NotNull String text) {}

    /**
     * The name of the group in the inventory to act on.
     *
     * @param group the group's name in the inventory
     */
    public record GroupArgs(@NotNull String group) {}

    /**
     * A shell command to run on every node of the group.
     *
     * @param command the command line to execute
     */
    public record CommandArgs(@NotNull String command) {}

    /**
     * The maximum number of steps the interpreter may execute before stopping.
     *
     * @param maxIterations the limit of steps to execute; if null, the limit is 10000
     */
    public record MaxIterationsArgs(Integer maxIterations) {}

    /**
     * Arguments for running a workflow.
     *
     * @param workflowFile the path to the workflow YAML file
     * @param maxIterations the maximum number of steps to execute; if null, defaults to 10000
     */
    public record RunWorkflowArgs(@NotNull String workflowFile, Integer maxIterations) {}

    /**
     * Arguments for calling an action on multiple child actors matching a pattern.
     *
     * @param actor a name pattern the child actors are matched against; {@code *} stands for any run of characters
     * @param method the name of the action to call on every matched child actor
     * @param arguments the arguments to pass to the action: a map reaches each child as a JSON object (for an action
     * with named arguments, such as {@code executeCommand}), a list as a JSON array, a single value as a one-element
     * array, and none as the empty array
     */
    public record ApplyArgs(@NotNull String actor, @NotNull String method, Object arguments) {}

    @Action("execCode")
    public ActionResult execCode(String args) {
        try {
            return this.ask(n -> n.execCode()).get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "execCode failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Executes the current workflow until it reaches the "end" state or hits the iteration limit.
     *
     * @param args the maximum number of steps to execute; if omitted, the limit is 10000
     * @return an action result indicating success or failure, with the result message containing the outcome of the
     * workflow execution
     */
    @Action(value = "runUntilEnd", argsType = MaxIterationsArgs.class)
    public ActionResult runUntilEnd(MaxIterationsArgs args) {
        try {
            int maxIterations = args.maxIterations() == null ? 10000 : args.maxIterations();
            return this.ask(n -> n.runUntilEnd(maxIterations)).get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "runUntilEnd failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Loads and runs a workflow file.
     *
     * <p>Returns an action result indicating whether the workflow execution succeeded or failed. If the workflow
     * fails, the message holds the error details.</p>
     *
     * <p>An optional {@code maxIterations} argument limits the number of steps the workflow may take; if omitted, it
     * defaults to 10000.</p>
     *
     * @param args a {@link RunWorkflowArgs} containing the path to the workflow file and an optional maximum number of
     * iterations.
     *
     * @return an {@link ActionResult} where success indicates the workflow reached the "end" state, and failure
     * indicates an error occurred during execution.
     */
    @Action(value = "runWorkflow", argsType = RunWorkflowArgs.class)
    public ActionResult runWorkflow(RunWorkflowArgs args) {
        try {
            String workflowFile = args.workflowFile();
            this.currentWorkflowPath = workflowFile;  // Store for WorkflowReporter
            int runMaxIterations = args.maxIterations() == null ? 10000 : args.maxIterations();
            logger.info(String.format("Running workflow: %s (maxIterations=%d)", workflowFile, runMaxIterations));
            ActionResult result = this.object.runWorkflow(workflowFile, runMaxIterations);
            logger.info(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "runWorkflow failed: " + args, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Loads a YAML workflow definition from the file system, optionally using an overlay directory to override
     * settings.
     *
     * @param args a record containing the path to the YAML file
     * @return an ActionResult that is a success if the file was loaded, with a message indicating if an overlay was
     * used; otherwise, a failure with a message describing the error (such as file not found, IO error, or
     * interruption)
     */
    @Action(value = "readYaml", argsType = PathArgs.class)
    public ActionResult readYaml(PathArgs args) {
        String filePath = args.path();
        this.currentWorkflowPath = filePath;  // Store for WorkflowReporter
        try {
            String overlayPath = this.object.getOverlayDir();
            if (overlayPath != null) {
                java.nio.file.Path yamlPath = java.nio.file.Path.of(filePath);
                java.nio.file.Path overlayDir = java.nio.file.Path.of(overlayPath);
                this.tell(n -> {
                    try {
                        n.readYaml(yamlPath, overlayDir);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();
                return new ActionResult(true, "YAML loaded with overlay: " + overlayPath);
            } else {
                try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(filePath))) {
                    this.tell(n -> n.readYaml(input)).get();
                    return new ActionResult(true, "YAML loaded successfully");
                }
            }
        } catch (java.io.FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", filePath), e);
            return new ActionResult(false, "File not found: " + filePath);
        } catch (java.io.IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", filePath), e);
            return new ActionResult(false, "IO error: " + filePath);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof java.io.IOException) {
                logger.log(Level.SEVERE, String.format("IOException: %s", filePath), e.getCause());
                return new ActionResult(false, "IO error: " + filePath);
            }
            throw e;
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "readYaml failed: " + filePath, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    // ========================================================================
    // NodeGroup-specific Actions
    // ========================================================================

    /**
     * Reads an Ansible inventory file into this node group.
     *
     * <p>The path is read under the workflow's base directory: this node group's own, or, when it has none, that of the
     * interpreter it was created under. A path that is outside the base directory, also once its links are resolved, is
     * refused. With no base directory the path is read as written.</p>
     *
     * <pre>{@code
     * - actor: nodeGroup
     *   method: loadInventory
     *   arguments: {path: inventory.ini}
     * }</pre>
     *
     * @param args the inventory file's path, relative to the base directory or absolute
     * @return a success naming the file read, or a failure saying why it was not read
     */
    @Action(value = "loadInventory", argsType = PathArgs.class)
    public ActionResult loadInventory(PathArgs args) {
        try {
            Path file = underBaseDirectory(baseDirectory(), args.path());
            try (InputStream in = Files.newInputStream(file)) {
                this.object.getNodeGroup().loadInventory(in);
            }
            return new ActionResult(true, "Inventory loaded: " + file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "loadInventory failed: " + args.path(), e);
            return new ActionResult(false, "loadInventory " + args.path() + ": " + e.getMessage());
        }
    }

    /**
     * Returns the base directory file reads are limited to: this node group's own, else that of the interpreter that is
     * its parent, else null.
     */
    private String baseDirectory() throws IOException {
        String own = this.object.getWorkflowBaseDir();
        if (own != null || getParentName() == null) {
            return own;
        }
        IIActorRef<?> parent = ((IIActorSystem) this.system()).getIIActor(getParentName());
        if (parent == null) {
            return null;
        }
        try {
            // askNow reads the parent's base directory on a virtual thread of its own, past the parent's mailbox,
            // which may be busy running the very workflow that called this action.
            return parent.askNow(o -> o instanceof Interpreter i ? i.getWorkflowBaseDir() : null).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the base directory of " + getParentName(), e);
        } catch (ExecutionException e) {
            throw new IOException("cannot read the base directory of " + getParentName(), e.getCause());
        }
    }

    /**
     * Returns the real path of {@code requested} under {@code baseDir}.
     *
     * <p>The path is resolved against the base and normalised, so {@code ..} is taken out, and refused when it is not
     * under the base; then its real path, with links resolved, is compared again, so a link inside the base that points
     * outside it is refused too. The base is compared by its real path. With no base directory, {@code requested} is
     * returned as written.</p>
     *
     * @throws IOException when the path is outside the base directory, does not exist, or cannot be resolved
     */
    static Path underBaseDirectory(String baseDir, String requested) throws IOException {
        if (baseDir == null) {
            return Path.of(requested);
        }
        Path base = Path.of(baseDir).toRealPath();
        Path candidate = base.resolve(requested).normalize();
        if (!candidate.startsWith(base)) {
            throw new IOException("outside the base directory: " + requested + " (base directory " + base + ")");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IOException("no such file under the base directory: " + candidate, e);
        }
        if (!real.startsWith(base)) {
            throw new IOException("outside the base directory: " + requested + " (base directory " + base + ")");
        }
        return real;
    }

    /**
     * Returns an action result indicating whether an inventory is currently loaded.
     *
     * @param args the argument string (not used)
     * @return an action result that is a success if an inventory is available, with a message "Inventory available";
     * otherwise, it is a failure with the message "No inventory"
     */
    @Action("hasInventory")
    public ActionResult hasInventory(String args) {
        boolean hasInv = this.object.getInventory() != null;
        return new ActionResult(hasInv, hasInv ? "Inventory available" : "No inventory");
    }

    /**
     * Creates a set of child actors for every host in the specified inventory group.
     *
     * <p>Returns an action result that is a success if the child actors were created, or a failure message if the
     * group could not be found or processed.</p>
     *
     * <p>Hosts whose node actor this group already has are skipped, so a second call for the same group succeeds
     * and creates nothing. If an actor that is not a child of this group holds a node actor's name, the result is
     * a failure naming it.</p>
     *
     * @param args a {@link GroupArgs} object containing the name of the group in the inventory.
     * @return an ActionResult indicating whether the child actors were successfully created and a message describing
     * the outcome.
     */
    @Action(value = "createNodeActors", argsType = GroupArgs.class)
    public ActionResult createNodeActorsAction(GroupArgs args) {
        String groupName = args.group();
        try {
            createNodeActors(groupName);
        } catch (IllegalStateException e) {
            logger.log(Level.SEVERE, "createNodeActors failed for group " + groupName, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
        return new ActionResult(true, String.format("Created node actors for group '%s'", groupName));
    }

    /**
     * Applies an action to child actors matching a wildcard pattern.
     *
     * <p>Returns an action result that is a success if all matched child actors executed the action successfully, or a
     * failure containing a message listing the specific nodes that failed and their error messages.</p>
     *
     * <p>The {@code actor} argument is a name pattern where {@code *} matches any sequence of characters; the {@code
     * method} is the name of the action to call on each matched child; the {@code arguments} is a list of objects
     * passed to that action.</p>
     *
     * <p>If no child actors match the pattern, the action returns a failure.</p>
     *
     * <pre>{@code
     * - actor: nodeGroup
     *   method: apply
     *   arguments:
     *     actor: "node-*"
     *     method: runWorkflow
     *     arguments:
     *       workflowFile: deploy.yaml
     * }</pre>
     *
     * @param args a record containing the actor pattern, the method name, and the arguments to pass to the matched
     * actors.
     *
     * @return an action result indicating whether all matched actors succeeded, or a failure message detailing which
     * nodes failed and why.
     */
    @Action(value = "apply", argsType = ApplyArgs.class, orchestrates = true)
    public ActionResult applyAction(ApplyArgs args) {
        return apply(args.actor(), args.method(), args.arguments());
    }

    /**
     * Executes a shell command on every child node actor.
     *
     * <p>The result is a success only if the command succeeded on every node; if it failed on any node, the result
     * is a failure. Either way the message holds each node's output.</p>
     *
     * @param args a {@link CommandArgs} containing the command string to run
     * @return an ActionResult that is a success containing a list of the results from each node, or a failure if the
     * command failed on any node, or the operation was interrupted or an execution error occurred
     */
    @Action(value = "executeCommandOnAllNodes", argsType = CommandArgs.class)
    public ActionResult executeCommandOnAllNodesAction(CommandArgs args) {
        try {
            String command = args.command();
            List<NodeCommandResult> results = executeCommandOnAllNodes(command);
            long failed = results.stream().filter(r -> !r.result().isSuccess()).count();
            if (failed > 0) {
                return new ActionResult(false,
                    String.format("Command failed on %d/%d nodes: %s", failed, results.size(), results));
            }
            return new ActionResult(true,
                String.format("Executed command on %d nodes: %s", results.size(), results));
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "executeCommandOnAllNodes failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns a result indicating whether an output multiplexer is registered in the actor system.
     *
     * @param args the argument string (not used)
     * @return an action result that is a success if the accumulator exists, and a failure if it does not
     */
    @Action("hasAccumulator")
    public ActionResult hasAccumulator(String args) {
        boolean hasAcc = ((IIActorSystem) this.system()).getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME) != null;
        return new ActionResult(hasAcc, hasAcc ? "Accumulator exists" : "No accumulator");
    }

    /**
     * Says whether there is an output multiplexer to collect the nodes' output into.
     *
     * <p>This action makes nothing: whoever runs the workflow registers the multiplexer, as the run command does, under
     * {@link MultiplexerAccumulatorIIAR#ACTOR_NAME}. With none registered the nodes' output would be lost, so this is a
     * failure, and a workflow that expects its output collected stops here.</p>
     *
     * @param args the argument string (not used)
     * @return a success when a multiplexer is registered, a failure naming the missing one otherwise
     */
    @Action("createAccumulator")
    public ActionResult createAccumulator(String args) {
        if (((IIActorSystem) this.system()).getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME) == null) {
            return new ActionResult(false,
                "No output multiplexer to collect into: nothing is registered as " + MultiplexerAccumulatorIIAR.ACTOR_NAME);
        }
        return new ActionResult(true, "Output is collected by " + MultiplexerAccumulatorIIAR.ACTOR_NAME);
    }

    /**
     * Returns a summary of the results collected by the output multiplexer.
     *
     * @param args the argument string (not used)
     * @return an ActionResult where a success indicates the summary was retrieved, and the result holds the summary
     * text; a failure indicates no output multiplexer is registered
     */
    @Action("getAccumulatorSummary")
    public ActionResult getAccumulatorSummaryAction(String args) {
        return getAccumulatorSummary();
    }

    /**
     * Returns a text summary of the verification results for the current session.
     *
     * <p>The result is a success if a log store is available and a session ID is set; otherwise, it returns a failure
     * with a message indicating the log store is unavailable.</p>
     *
     * @param args the argument string (not used)
     * @return an action result where a success contains a formatted ASCII table of verification statuses (OK, WARN,
     * ERROR) for various infrastructure components, or a failure message if the session cannot be queried
     */
    @Action("printSessionSummary")
    public ActionResult printSessionSummaryAction(String args) {
        return printSessionSummary();
    }

    /**
     * Returns an action result containing the current session ID as a string.
     *
     * Returns a success result with the session ID as the message if a session is active; otherwise, returns a failure
     * result with the message "No session ID set".
     */
    @Action("getSessionId")
    public ActionResult getSessionId(String args) {
        long sessionId = this.object.getSessionId();
        if (sessionId < 0) {
            return new ActionResult(false, "No session ID set");
        }
        return new ActionResult(true, String.valueOf(sessionId));
    }

    /**
     * Returns an action result containing the path to the currently executing workflow file.
     *
     * If no workflow is currently being executed, returns a failure result with the message "No workflow path set".
     *
     * @param args the argument string (not used)
     * @return an action result that is a success and holds the file path as its message, or a failure if no path is
     * set
     */
    @Action("getWorkflowPath")
    public ActionResult getWorkflowPath(String args) {
        if (currentWorkflowPath == null) {
            return new ActionResult(false, "No workflow path set");
        }
        return new ActionResult(true, currentWorkflowPath);
    }

    /**
     * Returns the provided text as a successful action result.
     *
     * @param args the text to be returned as the result message
     * @return an ActionResult that is a success and whose message is the input text
     */
    @Action(value = "doNothing", argsType = TextArgs.class)
    public ActionResult doNothing(TextArgs args) {
        return new ActionResult(true, args.text());
    }

    // ========================================================================
    // JSON State Output Actions
    // ========================================================================

    /**
     * Sends a pretty-printed JSON representation of the state at the given path to the output multiplexer and returns
     * the formatted text.
     *
     * @param args the path to the state file to be converted to JSON
     * @return an action result that is a success containing the formatted JSON string, or a failure if the file cannot
     * be read or processed
     */
    @Action(value = "printJson", argsType = PathArgs.class)
    public ActionResult printJson(PathArgs args) {
        String path = args.path();
        String formatted = toStringOfJson(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Outputs the YAML-formatted representation of the current state to the output multiplexer.
     *
     * @param args the path to the file to be read
     * @return an action result that is a success if the YAML was successfully formatted and sent, and contains an
     * error message if the file could not be read or an IO error occurred
     */
    @Action(value = "printYaml", argsType = PathArgs.class)
    public ActionResult printYaml(PathArgs args) {
        String path = args.path();
        String formatted = toStringOfYaml(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /** Sends the formatted text to the output multiplexer actor line by line. */
    private void sendToMultiplexer(String formatted) {
        ActorHelper.sendToMultiplexer((IIActorSystem) this.system(), this.getName(), formatted);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private int parseMaxIterations(String arg, int defaultValue) {
        return ActorHelper.parseMaxIterations(arg, defaultValue);
    }

    private String getFirst(String args) {
        return ActorHelper.getFirst(args);
    }

    /**
     * Creates child actors for all nodes in the specified group.
     *
     * <p>When a workflow calls this action, it populates the actor hierarchy with new child actors, one for each host
     * found in the inventory group. Each child is a {@code NodeActor} that manages its own SSH connection and workflow
     * execution, inheriting the parent's base directory, overlay directory, and result accumulator.</p>
     *
     * <p>If the group name is "local", it creates a single child actor representing the local machine instead of using
     * the inventory. If the group name does not exist in the inventory, no child actors are created.</p>
     *
     * <p>Each node is registered once in the actor system, as a {@code NodeActor} named {@code node-<host>}
     * ({@code <host>} is the {@code ansible_host} value, or the inventory host name when it has none), and is
     * added to this actor's children. A node whose actor this group already has as a child is skipped and kept
     * as it is, so calling this again for the same group, or for a group sharing hosts with an earlier one,
     * creates only the missing node actors. A name held by an actor that is not a child of this group is not
     * taken over: the call throws {@link IllegalStateException}.</p>
     *
     * @param groupName the name of the group to expand, or "local" for the localhost node
     * @return the number of node actors created by this call
     * @throws IllegalStateException when an actor outside this group already holds a node actor's name
     */
    private int createNodeActors(String groupName) {
        // Direct execution (no tell().get() to avoid deadlock when called from workflow)
        IIActorSystem sys = (IIActorSystem) this.system();
        NodeGroupInterpreter nodeGroupInterpreter = this.object;

        // Create Node POJOs for the group
        // Special handling for "local" group: create localhost node without inventory
        List<Node> nodes;
        if ("local".equals(groupName)) {
            nodes = nodeGroupInterpreter.createLocalNode();
        } else {
            nodes = nodeGroupInterpreter.createNodesForGroup(groupName);
        }

        // Create child actors for each node
        int created = 0;
        for (Node node : nodes) {
            String nodeName = "node-" + node.getHostname();

            if (sys.hasActor(nodeName)) {
                if (this.getNamesOfChildren().contains(nodeName)) {
                    logger.fine(String.format("Node actor already exists, kept as is: %s", nodeName));
                    continue;
                }
                // Checked before addChildActor, which would record the name as a child before refusing it.
                throw new IllegalStateException(String.format(
                    "an actor that is not a child of %s is already registered as %s", this.getName(), nodeName));
            }

            // Wrap Node in NodeInterpreter to add workflow capabilities
            NodeInterpreter nodeInterpreter = new NodeInterpreter(node, sys);

            // Propagate workflowBaseDir to child interpreter
            if (nodeGroupInterpreter.getWorkflowBaseDir() != null) {
                nodeInterpreter.setWorkflowBaseDir(nodeGroupInterpreter.getWorkflowBaseDir());
            }

            // Propagate overlayDir to child interpreter
            if (nodeGroupInterpreter.getOverlayDir() != null) {
                nodeInterpreter.setOverlayDir(nodeGroupInterpreter.getOverlayDir());
            }

            // Propagate accumulator to child interpreter
            if (nodeGroupInterpreter.getAccumulator() != null) {
                nodeInterpreter.setAccumulator(nodeGroupInterpreter.getAccumulator());
            }

            // Register one NodeActor under the name, as a child of this group.
            NodeActor nodeActor = new NodeActor(nodeName, nodeInterpreter, sys);
            this.addChildActor(nodeActor);
            created++;

            logger.fine(String.format("Created child node actor: %s", nodeName));
        }
        logger.info(String.format("Created %d node actors for group '%s' (%d already existed)",
            created, groupName, nodes.size() - created));
        return created;
    }

    /**
     * Applies an action to all child actors matching a wildcard pattern.
     *
     * <p>Each child's work runs on the ManagedThreadPool's real threads, never on commonPool. Called
     * on a thread that is not one of the pool's real threads, the children run in parallel and the
     * calling thread waits; called on one of the pool's real threads, which is where a workflow
     * step reaches it, they run on that real thread one after another.</p>
     *
     * Returns an action result that is a success if all matched child actors executed the method successfully, or a
     * failure containing a list of specific errors for each failed actor. If no actors match the pattern, it returns a
     * failure.
     *
     * <pre>{@code
     * - actor: nodeGroup
     *   method: apply
     *   arguments:
     *     actor: "node-*"
     *     method: runWorkflow
     *     arguments:
     *       workflowFile: deploy.yaml
     * }</pre>
     *
     * @param actorPattern a name pattern to match against child actors, where {@code *} acts as a wildcard
     * @param method the name of the action to call on each matched actor
     * @param arguments the list of arguments to pass to the action on each matched actor
     * @return an action result that is a success if all matched actors succeeded, or a failure with a message listing
     * the failures for each actor (e.g., "Applied to 5/6 actors. Failures: node-web1: Error: connection refused")
     */
    private ActionResult apply(String actorPattern, String method, Object arguments) {
        try {
            // Shape the arguments as a step's own action call does, so each child receives the same JSON.
            String args = Interpreter.argumentsToJson(arguments);

            // Find matching child actors
            List<IIActorRef<?>> matchedActors = findMatchingChildActors(actorPattern);

            if (matchedActors.isEmpty()) {
                return new ActionResult(false, "No actors matched pattern: " + actorPattern);
            }

            logger.info(String.format("Applying method '%s' to %d actors matching '%s'",
                method, matchedActors.size(), actorPattern));

            // Thread-safe collections for gathering results
            AtomicInteger successCount = new AtomicInteger(0);
            Map<String, String> failures = new ConcurrentHashMap<>();
            DistributedLogStore logStore = this.object.getLogStore();
            long sessionId = this.object.getSessionId();

            // Each child's work is given to the ManagedThreadPool through the child actor,
            // ask(action, pool); nothing here runs on commonPool. Called on a thread that is not
            // one of the pool's real threads (this actor's mailbox virtual thread, say), the
            // children run in parallel on the pool's real threads and this thread waits for them.
            // Called on one of the pool's real threads, as a workflow step reaches it, ask runs
            // each child right here, one after another (ActorRef's inline rule): no parallelism,
            // but no pool real thread waits for work queued behind itself.
            ManagedThreadPool pool = this.system().getManagedThreadPool();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (IIActorRef<?> actor : matchedActors) {
                CompletableFuture<Void> future = actor.<Void>ask(unused -> {
                    try {
                        ActionResult result = actor.callByActionName(method, args);
                        if (!result.isSuccess()) {
                            failures.put(actor.getName(), result.getResult());
                            logger.warning(String.format("Failed on %s: %s", actor.getName(), result.getResult()));
                            // Record node failure in log store
                            if (logStore != null && sessionId >= 0) {
                                logStore.markNodeFailed(sessionId, actor.getName(), result.getResult());
                            }
                        } else {
                            successCount.incrementAndGet();
                            logger.fine(String.format("Applied to %s: %s", actor.getName(), result.getResult()));
                            // Record node success in log store
                            if (logStore != null && sessionId >= 0) {
                                logStore.markNodeSuccess(sessionId, actor.getName());
                            }
                        }
                    } catch (Exception e) {
                        failures.put(actor.getName(), e.getMessage());
                        logger.log(Level.WARNING, "Exception on " + actor.getName(), e);
                    }
                    return null;
                }, pool);
                futures.add(future);
            }

            // Wait for all async tasks to complete. A pool real thread that waits runs the pool's queued
            // tasks meanwhile (awaitHelping), so a pool whose real threads all wait here makes progress.
            if (pool.ownsCurrentThread()) {
                for (CompletableFuture<Void> f : futures) {
                    pool.awaitHelping(f);
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (failures.isEmpty()) {
                return new ActionResult(true,
                    String.format("Applied to %d actors", successCount.get()));
            } else {
                List<String> failureMessages = new ArrayList<>();
                failures.forEach((name, msg) -> failureMessages.add(name + ": " + msg));
                return new ActionResult(false,
                    String.format("Applied to %d/%d actors. Failures: %s",
                        successCount.get(), matchedActors.size(), String.join("; ", failureMessages)));
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in apply: " + method + " on " + actorPattern, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns a list of child actors whose names match the provided wildcard pattern.
     *
     * <p>The pattern is matched against the names of the actors created by {@code createNodeActors}. A single asterisk
     * {@code *} matches any sequence of characters, such as {@code node-*} matching all node actors, or {@code *-web}
     * matching any actor ending in {@code -web}. If no wildcard is present, the pattern must be an exact match for an
     * actor name.</p>
     *
     * @param pattern the wildcard pattern to match against child actor names
     */
    private List<IIActorRef<?>> findMatchingChildActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();
        IIActorSystem system = (IIActorSystem) this.system();

        if (system == null) {
            return matched;
        }

        List<String> childNames = new ArrayList<>(this.getNamesOfChildren());

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            if (childNames.contains(pattern)) {
                IIActorRef<?> actor = system.getIIActor(pattern);
                if (actor != null) {
                    matched.add(actor);
                }
            }
            return matched;
        }

        // Convert wildcard to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String childName : childNames) {
            if (compiled.matcher(childName).matches()) {
                IIActorRef<?> actor = system.getIIActor(childName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    /**
     * Executes a shell command on every child node actor in the group.
     *
     * Returns one entry per node, holding the node's name and the result of its {@code executeCommand} action;
     * an entry prints as "node-hostname: output".
     *
     * @param command the shell command to run on each node
     */
    private List<NodeCommandResult> executeCommandOnAllNodes(String command)
            throws ExecutionException, InterruptedException {

        IIActorSystem system = (IIActorSystem) this.system();
        List<NodeCommandResult> results = new ArrayList<>();

        // Get all child node names
        List<String> childNames = new ArrayList<>(this.getNamesOfChildren());

        logger.info(String.format("Executing command on %d nodes: %s", childNames.size(), command));

        // Execute on each child node
        for (String childName : childNames) {
            IIActorRef<?> actorRef = system.getIIActor(childName);
            if (actorRef == null || !(actorRef instanceof NodeActor)) {
                logger.warning(String.format("Child node actor not found or wrong type: %s", childName));
                continue;
            }
            NodeActor nodeActor = (NodeActor) actorRef;

            // executeCommand declares the named argument "command", so it receives a JSON object.
            String commandArgs = new JSONObject().put("command", command).toString();
            ActionResult result = nodeActor.callByActionName("executeCommand", commandArgs);

            results.add(new NodeCommandResult(childName, result));
        }

        return results;
    }

    /**
     * The outcome of running a command on one node.
     *
     * @param nodeName the name of the node actor
     * @param result the result of the node's {@code executeCommand} action
     */
    private record NodeCommandResult(String nodeName, ActionResult result) {
        @Override
        public String toString() {
            return nodeName + ": " + result.getResult();
        }
    }

    /**
     * Returns a string that is the first element of a JSON array.
     *
     * @param arg the JSON array argument string
     */
    private String extractSingleArgument(String arg) {
        return ActorHelper.extractCommandFromArgs(arg);
    }

    /**
     * Returns a summary of all collected results from the output multiplexer.
     *
     * @return an action result that is a success and holds the summary text, or a failure if no output multiplexer is
     * registered
     */
    private ActionResult getAccumulatorSummary() {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME);
        if (multiplexer == null) {
            return new ActionResult(false, "No output multiplexer registered");
        }
        ActionResult result = multiplexer.callByActionName("getSummary", "");
        return result;
    }

    /**
     * Returns a text summary of the verification results for the current session, formatted as an ASCII table of
     * statuses for various infrastructure components.
     *
     * <p>The result is a success if the log store is available and a session ID is set; otherwise, it returns a
     * failure with a message indicating the log store is unavailable. The summary includes counts of {@code [OK]},
     * {@code [WARN]}, and {@code [ERROR]} markers found in logs labeled with {@code verify-} prefixes, along with any
     * extracted metadata like document counts or cluster health.</p>
     */
    private ActionResult printSessionSummary() {
        DistributedLogStore logStore = this.object.getLogStore();
        long sessionId = this.object.getSessionId();

        if (logStore == null || sessionId < 0) {
            String msg = "Log store not available";
            System.out.println(msg);
            return new ActionResult(false, msg);
        }

        // Get all logs for this session
        List<LogEntry> logs = logStore.getLogsByLevel(sessionId, LogLevel.DEBUG);

        // Group logs by label and count results
        java.util.Map<String, VerifyResult> resultsByLabel = new java.util.LinkedHashMap<>();

        for (LogEntry entry : logs) {
            String message = entry.getMessage();
            String label = entry.getLabel();
            if (message == null) continue;

            // Extract label from the message if it contains step info
            if (label != null && label.contains("label:")) {
                int idx = label.indexOf("label:");
                if (idx >= 0) {
                    String rest = label.substring(idx + 11).trim();
                    int end = rest.indexOf('\n');
                    label = end > 0 ? rest.substring(0, end).trim() : rest.trim();
                }
            }

            // Skip non-verify steps
            if (label == null || !label.startsWith("verify-")) {
                continue;
            }

            VerifyResult result = resultsByLabel.computeIfAbsent(label, k -> new VerifyResult());

            // Count occurrences in message
            result.okCount += countOccurrences(message, "[OK]");
            result.warnCount += countOccurrences(message, "[WARN]");
            result.errorCount += countOccurrences(message, "[ERROR]");
            result.infoCount += countOccurrences(message, "[INFO]");

            // Extract special info (like document count, cluster health)
            extractSpecialInfo(message, result);
        }

        // Build summary output
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("============================================================\n");
        sb.append("                 VERIFICATION SUMMARY\n");
        sb.append("============================================================\n");
        sb.append("\n");
        sb.append(String.format("| %-35s | %-20s |\n", "Item", "Status"));
        sb.append("|-------------------------------------|----------------------|\n");

        // Mapping from labels to display names
        String[][] mappings = {
            {"verify-repos", "Document repositories"},
            {"verify-utility-cli", "Utility-cli"},
            {"verify-utility-sau3", "Utility-sau3"},
            {"verify-builds", "Docusaurus builds"},
            {"verify-public-html", "public_html deploy"},
            {"verify-apache", "Apache2 + UserDir"},
            {"verify-opensearch-install", "OpenSearch install"},
            {"verify-opensearch-running", "OpenSearch status"},
            {"verify-docusearch-build", "quarkus-docusearch build"},
            {"verify-docusearch-running", "quarkus-docusearch server"},
            {"verify-search-index", "Search index"},
            {"verify-web-access", "Web access"},
        };

        int totalOk = 0, totalWarn = 0, totalError = 0;
        int executedChecks = 0;
        List<String> errorDetails = new ArrayList<>();
        List<String> warnDetails = new ArrayList<>();

        for (String[] mapping : mappings) {
            String label = mapping[0];
            String displayName = mapping[1];
            VerifyResult result = resultsByLabel.get(label);

            if (result == null) {
                sb.append(String.format("| %-35s | %-20s |\n", displayName, "SKIP"));
                continue;
            }
            executedChecks++;

            totalOk += result.okCount;
            totalWarn += result.warnCount;
            totalError += result.errorCount;

            String status = formatStatus(result);
            sb.append(String.format("| %-35s | %-20s |\n", displayName, status));

            // Collect error/warning details
            if (result.errorCount > 0) {
                errorDetails.add(displayName + ": " + result.errorCount + " error(s)");
            }
            if (result.warnCount > 0) {
                warnDetails.add(displayName + ": " + result.warnCount + " warning(s)");
            }
        }

        sb.append("|-------------------------------------|----------------------|\n");
        sb.append(String.format("| %-35s | %d OK, %d WARN, %d ERR |\n",
            "TOTAL", totalOk, totalWarn, totalError));
        sb.append("============================================================\n");

        // Show error details if any
        if (!errorDetails.isEmpty()) {
            sb.append("\n--- Errors ---\n");
            for (String detail : errorDetails) {
                sb.append("  * ").append(detail).append("\n");
            }
        }

        // Show warning details if any
        if (!warnDetails.isEmpty()) {
            sb.append("\n--- Warnings ---\n");
            for (String detail : warnDetails) {
                sb.append("  * ").append(detail).append("\n");
            }
        }

        sb.append("\n");
        if (executedChecks == 0) {
            sb.append("No verification checks were executed.\n");
        } else if (totalError == 0 && totalWarn == 0) {
            sb.append("All checks passed!\n");
        } else if (totalError > 0) {
            sb.append("To fix issues, run:\n");
            sb.append("  ./actor_iac.java --dir ./docu-search --workflow main-setup\n");
        }

        String summary = sb.toString();
        System.out.println(summary);
        return new ActionResult(true, summary);
    }

    /**
     * Returns a human-readable status string summarizing the counts of OK, WARN, and ERROR markers found in a
     * verification result.
     *
     * The result is a string such as "3 OK, 1 WARN", "ERROR", or "2 OK" depending on the counts in the provided
     * result.
     */
    private String formatStatus(VerifyResult result) {
        if (result.errorCount > 0) {
            if (result.okCount > 0) {
                return String.format("%d OK, %d ERROR", result.okCount, result.errorCount);
            }
            return "ERROR";
        }
        if (result.warnCount > 0) {
            if (result.okCount > 0) {
                return String.format("%d OK, %d WARN", result.okCount, result.warnCount);
            }
            return "WARN";
        }
        if (result.okCount > 0) {
            String extra = result.extraInfo != null ? " " + result.extraInfo : "";
            return result.okCount + " OK" + extra;
        }
        return "OK";
    }

    /**
     * Updates the verification result with contextual details found in the log message.
     *
     * If the message contains patterns for document counts or cluster health, the corresponding values are extracted
     * and stored in the result's extra information field.
     *
     * @param message the log message to scan
     * @param result the verification result to update with extracted info
     */
    private void extractSpecialInfo(String message, VerifyResult result) {
        // Extract document count from search index
        if (message.contains("documents")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+documents").matcher(message);
            if (m.find()) {
                result.extraInfo = "(" + m.group(1) + " docs)";
            }
        }
        // Extract cluster health
        if (message.contains("Cluster health:")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Cluster health:\\s*(\\w+)").matcher(message);
            if (m.find()) {
                result.extraInfo = "(" + m.group(1) + ")";
            }
        }
    }

    /**
     * Returns the number of non-overlapping occurrences of a substring within a text.
     *
     * @param text the text to search through
     * @param sub the substring to count
     * @return the number of times {@code sub} appears in {@code text}
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Aggregates the counts of status markers and extracted metadata from log messages for a single verification step.
     *
     * <p>It holds the number of {@code [OK]}, {@code [WARN]}, {@code [ERROR]}, and {@code [INFO]} occurrences found in
     * the logs, along with an optional string for additional context like document counts or cluster health
     * status.</p>
     */
    private static class VerifyResult {
        /** The number of {@code [OK]} markers found in the log messages. */
        int okCount = 0;
        /** The number of {@code [WARN]} markers found in the log messages. */
        int warnCount = 0;
        /** Number of {@code [ERROR]} markers found. */
        int errorCount = 0;
        /** Number of {@code [INFO]} markers found. */
        int infoCount = 0;
        /** Holds optional additional context extracted from log messages, such as "(42 docs)" or "(healthy)". */
        String extraInfo = null;
    }
}
