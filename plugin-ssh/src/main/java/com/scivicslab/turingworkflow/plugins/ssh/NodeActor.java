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

package com.scivicslab.turingworkflow.plugins.ssh;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import static com.scivicslab.pojoactor.core.ActionArgs.getFirst;

/**
 * Interpreter-interfaced actor reference for {@link NodeInterpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link NodeInterpreter} objects. It handles action invocations
 * by name, supporting both workflow execution actions (inherited from Interpreter)
 * and infrastructure actions (SSH command execution).</p>
 *
 * <p><strong>Supported actions:</strong></p>
 * <p><em>Workflow actions (from Interpreter):</em></p>
 * <ul>
 *   <li>{@code execCode} - Executes the loaded workflow code</li>
 *   <li>{@code readYaml} - Reads a YAML workflow definition from a file path</li>
 *   <li>{@code readJson} - Reads a JSON workflow definition from a file path</li>
 *   <li>{@code readXml} - Reads an XML workflow definition from a file path</li>
 *   <li>{@code reset} - Resets the interpreter state</li>
 * </ul>
 * <p><em>Infrastructure actions (Node-specific):</em></p>
 * <ul>
 *   <li>{@code executeCommand} - Executes a command and reports to accumulator (default)</li>
 *   <li>{@code executeCommandQuiet} - Executes a command without reporting</li>
 *   <li>{@code executeSudoCommand} - Executes sudo command and reports to accumulator (default)</li>
 *   <li>{@code executeSudoCommandQuiet} - Executes sudo command without reporting</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 */
public class NodeActor extends IIActorRef<NodeInterpreter> {

    /** Logger instance named after the actor for context-aware logging. */
    Logger logger = null;

    /**
     * Constructs a new NodeActor with the specified actor name and node interpreter object.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeInterpreter} instance managed by this actor reference
     */
    public NodeActor(String actorName, NodeInterpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Constructs a new NodeActor with the specified actor name, node interpreter object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeInterpreter} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public NodeActor(String actorName, NodeInterpreter object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);

        // Set the selfActorRef in the Interpreter (NodeInterpreter extends Interpreter)
        object.setSelfActorRef(this);
    }

    // ========================================================================
    // Workflow Actions
    // ========================================================================

    /**
     * Executes the loaded workflow code.
     *
     * <p>JSON args format: ignored (no arguments required).</p>
     *
     * @param args JSON array string (unused)
     * @return ActionResult with the execution outcome
     */
    @Action("execCode")
    public ActionResult execCode(String args) {
        try {
            return this.ask(n -> n.execCode()).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Reads a YAML workflow definition from a file path.
     *
     * <p>If an overlay directory is configured on the interpreter, the YAML
     * is loaded with overlay variable substitution applied.</p>
     *
     * <p>JSON args format: {@code ["path/to/workflow.yaml"]}</p>
     *
     * @param args JSON array containing the file path as the first element
     * @return ActionResult indicating success or failure with error details
     */
    @Action("readYaml")
    public ActionResult readYaml(String args) {
        String arg = getFirst(args);
        try {
            String overlayPath = this.object.getOverlayDir();
            if (overlayPath != null) {
                java.nio.file.Path yamlPath = java.nio.file.Path.of(arg);
                java.nio.file.Path overlayDir = java.nio.file.Path.of(overlayPath);
                this.tell(n -> {
                    try {
                        n.readYaml(yamlPath, overlayDir);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();
                return new ActionResult(true, "YAML loaded with overlay: " + overlayPath);
            } else {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell(n -> n.readYaml(input)).get();
                    return new ActionResult(true, "YAML loaded successfully");
                }
            }
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
            return new ActionResult(false, "IO error: " + arg);
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                logger.log(Level.SEVERE, String.format("IOException: %s", arg), e.getCause());
                return new ActionResult(false, "IO error: " + arg);
            }
            throw e;
        }
    }

    /**
     * Reads a JSON workflow definition from a file path.
     *
     * <p>JSON args format: {@code ["path/to/workflow.json"]}</p>
     *
     * @param args JSON array containing the file path as the first element
     * @return ActionResult indicating success or failure with error details
     */
    @Action("readJson")
    public ActionResult readJson(String args) {
        String arg = getFirst(args);
        try (InputStream input = new FileInputStream(new File(arg))) {
            this.tell(n -> {
                try {
                    n.readJson(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();
            return new ActionResult(true, "JSON loaded successfully");
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
            return new ActionResult(false, "IO error: " + arg);
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Reads an XML workflow definition from a file path.
     *
     * <p>JSON args format: {@code ["path/to/workflow.xml"]}</p>
     *
     * @param args JSON array containing the file path as the first element
     * @return ActionResult indicating success or failure with error details
     */
    @Action("readXml")
    public ActionResult readXml(String args) {
        String arg = getFirst(args);
        try (InputStream input = new FileInputStream(new File(arg))) {
            this.tell(n -> {
                try {
                    n.readXml(input);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get();
            return new ActionResult(true, "XML loaded successfully");
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("Exception: %s", arg), e);
            return new ActionResult(false, "Error: " + arg);
        }
    }

    /**
     * Resets the interpreter state, clearing the loaded workflow code and current state.
     *
     * <p>JSON args format: ignored (no arguments required).</p>
     *
     * @param args JSON array string (unused)
     * @return ActionResult indicating success or failure
     */
    @Action("reset")
    public ActionResult reset(String args) {
        try {
            this.tell(n -> n.reset()).get();
            return new ActionResult(true, "Interpreter reset successfully");
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Runs the loaded workflow until it reaches an end state or exceeds the iteration limit.
     *
     * <p>JSON args format: {@code [maxIterations]} where maxIterations is optional
     * (defaults to 10000).</p>
     *
     * @param args JSON array optionally containing the max iterations as the first element
     * @return ActionResult with the workflow execution outcome
     */
    @Action("runUntilEnd")
    public ActionResult runUntilEnd(String args) {
        try {
            int maxIterations = parseMaxIterations(args, 10000);
            return this.ask(n -> n.runUntilEnd(maxIterations)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Calls (loads and prepares) a sub-workflow from a file.
     *
     * <p>JSON args format: {@code ["path/to/sub-workflow.yaml"]}</p>
     *
     * @param args JSON array containing the workflow file path as the first element
     * @return ActionResult with the call outcome
     */
    @Action("call")
    public ActionResult call(String args) {
        try {
            JSONArray callArgs = new JSONArray(args);
            String callWorkflowFile = callArgs.getString(0);
            return this.ask(n -> n.call(callWorkflowFile)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Loads and runs a workflow file to completion in a single action.
     *
     * <p>JSON args format: {@code ["path/to/workflow.yaml"]} or
     * {@code ["path/to/workflow.yaml", maxIterations]}.</p>
     *
     * @param args JSON array with workflow file path and optional max iterations
     * @return ActionResult with the workflow execution outcome
     */
    @Action("runWorkflow")
    public ActionResult runWorkflow(String args) {
        try {
            JSONArray runArgs = new JSONArray(args);
            String runWorkflowFile = runArgs.getString(0);
            int runMaxIterations = runArgs.length() > 1 ? runArgs.getInt(1) : 10000;
            logger.fine(String.format("Running workflow: %s (maxIterations=%d)", runWorkflowFile, runMaxIterations));
            ActionResult result = this.object.runWorkflow(runWorkflowFile, runMaxIterations);
            logger.fine(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
            return result;
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Applies a JSON patch or transformation to the interpreter's JSON state.
     *
     * <p>JSON args format: a JSON string representing the transformation to apply.</p>
     *
     * @param args the JSON transformation arguments
     * @return ActionResult with the apply outcome
     */
    @Action("apply")
    public ActionResult apply(String args) {
        try {
            return this.ask(n -> n.apply(args)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // Command Execution Actions
    // ========================================================================

    /**
     * Executes a command on the remote node and reports output to the accumulator.
     *
     * <p>Output is streamed in real-time to the outputMultiplexer if available.
     * After execution, the full result is reported to the accumulator.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with success status and combined stdout/stderr
     */
    @Action("executeCommand")
    public ActionResult executeCommand(String args) {
        try {
            String command = extractCommandFromArgs(args);
            String nodeName = this.getName();

            Node.OutputCallback callback = createOutputCallback(nodeName);

            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeCommand(command, callback);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            reportToAccumulator(result);
            return new ActionResult(result.isSuccess(), combineOutput(result));
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Executes a command on the remote node without reporting to the accumulator.
     *
     * <p>Unlike {@code executeCommand}, this action does not stream output or
     * report results to the outputMultiplexer.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with exit code, stdout, and stderr in the result string
     */
    @Action("executeCommandQuiet")
    public ActionResult executeCommandQuiet(String args) {
        try {
            String command = extractCommandFromArgs(args);
            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeCommand(command);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            return new ActionResult(result.isSuccess(),
                String.format("exitCode=%d, stdout='%s', stderr='%s'",
                    result.getExitCode(), result.getStdout(), result.getStderr()));
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Executes a command with sudo privileges and reports output to the accumulator.
     *
     * <p>Requires the {@code SUDO_PASSWORD} environment variable to be set.
     * Output is streamed in real-time to the outputMultiplexer if available.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with success status and combined stdout/stderr
     */
    @Action("executeSudoCommand")
    public ActionResult executeSudoCommand(String args) {
        try {
            String command = extractCommandFromArgs(args);
            String nodeName = this.getName();

            Node.OutputCallback callback = createOutputCallback(nodeName);

            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeSudoCommand(command, callback);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            reportToAccumulator(result);
            return new ActionResult(result.isSuccess(), combineOutput(result));
        } catch (ExecutionException e) {
            // Check if this is a SUDO_PASSWORD error
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null &&
                cause.getMessage().contains("SUDO_PASSWORD environment variable is not set")) {
                String hostname = this.object.getHostname();
                String errorMessage = "%" + hostname + ": [FAIL] SUDO_PASSWORD not set";
                reportOutputToMultiplexer(this.getName(), errorMessage);
                return new ActionResult(false, errorMessage);
            }
            return handleException(e);
        } catch (InterruptedException e) {
            return handleException(e);
        }
    }

    /**
     * Executes a command with sudo privileges without reporting to the accumulator.
     *
     * <p>Requires the {@code SUDO_PASSWORD} environment variable to be set.
     * Unlike {@code executeSudoCommand}, this action does not stream output.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with exit code, stdout, and stderr in the result string
     */
    @Action("executeSudoCommandQuiet")
    public ActionResult executeSudoCommandQuiet(String args) {
        try {
            String command = extractCommandFromArgs(args);
            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeSudoCommand(command);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            return new ActionResult(result.isSuccess(),
                String.format("exitCode=%d, stdout='%s', stderr='%s'",
                    result.getExitCode(), result.getStdout(), result.getStderr()));
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // Utility Actions
    // ========================================================================

    /**
     * Pauses execution for the specified number of milliseconds.
     *
     * <p>JSON args format: {@code ["milliseconds"]} (e.g., {@code ["5000"]} for 5 seconds).</p>
     *
     * @param args JSON array containing the sleep duration in milliseconds
     * @return ActionResult indicating success or failure if interrupted
     */
    @Action("sleep")
    public ActionResult sleep(String args) {
        try {
            long millis = Long.parseLong(getFirst(args));
            Thread.sleep(millis);
            return new ActionResult(true, "Slept for " + millis + "ms");
        } catch (NumberFormatException e) {
            logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", args), e);
            return new ActionResult(false, "Invalid sleep duration: " + args);
        } catch (InterruptedException e) {
            return new ActionResult(false, "Sleep interrupted: " + e.getMessage());
        }
    }

    /**
     * Prints a text message to standard output.
     *
     * <p>JSON args format: {@code ["text to print"]}</p>
     *
     * @param args JSON array containing the text to print as the first element
     * @return ActionResult with the printed text
     */
    @Action("print")
    public ActionResult print(String args) {
        String text = getFirst(args);
        System.out.println(text);
        return new ActionResult(true, "Printed: " + text);
    }

    /**
     * A no-op action that always succeeds, returning the first argument as the result.
     *
     * <p>Useful as a placeholder or pass-through in workflow definitions.</p>
     *
     * <p>JSON args format: {@code ["optional message"]}</p>
     *
     * @param args JSON array containing an optional message
     * @return ActionResult with success and the first argument as the result
     */
    @Action("doNothing")
    public ActionResult doNothing(String args) {
        return new ActionResult(true, getFirst(args));
    }

    // ========================================================================
    // Document Workflow Actions
    // ========================================================================

    /**
     * Detects which documents have changed by comparing local and remote git state.
     *
     * <p>JSON args format: {@code ["path/to/doc-list.txt"]}</p>
     *
     * @param args JSON array containing the document list file path
     * @return ActionResult with the number of changed documents detected
     */
    @Action("detectDocumentChanges")
    public ActionResult detectDocumentChanges(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.detectDocumentChanges(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error detecting changes: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Clones documents that were detected as changed from their git repositories.
     *
     * <p>Only processes documents previously identified by {@code detectDocumentChanges}.
     * Performs a fresh clone (removes existing directory first) to avoid conflicts.</p>
     *
     * <p>JSON args format: {@code ["path/to/doc-list.txt"]}</p>
     *
     * @param args JSON array containing the document list file path
     * @return ActionResult with the number of documents cloned
     */
    @Action("cloneChangedDocuments")
    public ActionResult cloneChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.cloneChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error cloning documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Builds changed Docusaurus documents using yarn install and yarn build.
     *
     * <p>Only processes documents previously identified by {@code detectDocumentChanges}.</p>
     *
     * <p>JSON args format: {@code ["path/to/doc-list.txt"]}</p>
     *
     * @param args JSON array containing the document list file path
     * @return ActionResult with the number of documents built
     */
    @Action("buildChangedDocuments")
    public ActionResult buildChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.buildChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error building documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Deploys changed document builds to the public_html directory.
     *
     * <p>Copies the build output of each changed document to {@code ~/public_html/}.
     * Only processes documents previously identified by {@code detectDocumentChanges}.</p>
     *
     * <p>JSON args format: {@code ["path/to/doc-list.txt"]}</p>
     *
     * @param args JSON array containing the document list file path
     * @return ActionResult with the number of documents deployed
     */
    @Action("deployChangedDocuments")
    public ActionResult deployChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.deployChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error deploying documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // JSON State Output Actions
    // ========================================================================

    /**
     * Outputs JSON State at the given path in pretty JSON format via outputMultiplexer.
     *
     * @param args the path to output (from JSON array)
     * @return ActionResult with the formatted JSON
     */
    @Action("printJson")
    public ActionResult printJson(String args) {
        String path = getFirst(args);
        String formatted = toStringOfJson(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Outputs JSON State at the given path in YAML format via outputMultiplexer.
     *
     * @param args the path to output (from JSON array)
     * @return ActionResult with the formatted YAML
     */
    @Action("printYaml")
    public ActionResult printYaml(String args) {
        String path = getFirst(args);
        logger.info(String.format("printYaml called: path='%s'", path));
        String formatted = toStringOfYaml(path);
        logger.info(String.format("printYaml output length: %d", formatted.length()));
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Sends formatted output to the outputMultiplexer, line by line.
     */
    private void sendToMultiplexer(String formatted) {
        ActorHelper.sendToMultiplexer((IIActorSystem) this.system(), this.getName(), formatted);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Handles exceptions and returns an appropriate ActionResult.
     */
    private ActionResult handleException(Exception e) {
        String message;
        if (e instanceof ExecutionException) {
            message = extractRootCauseMessage((ExecutionException) e);
        } else {
            message = e.getMessage();
        }
        logger.warning(String.format("%s: %s", this.getName(), message));
        return new ActionResult(false, message);
    }

    private int parseMaxIterations(String arg, int defaultValue) {
        return ActorHelper.parseMaxIterations(arg, defaultValue);
    }

    /**
     * Creates an OutputCallback that forwards output to the multiplexer accumulator.
     */
    private Node.OutputCallback createOutputCallback(String nodeName) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");

        if (multiplexer == null) {
            return null;
        }

        return new Node.OutputCallback() {
            @Override
            public void onStdout(String line) {
                JSONObject arg = new JSONObject();
                arg.put("source", nodeName);
                arg.put("type", "stdout");
                arg.put("data", line);
                multiplexer.callByActionName("add", arg.toString());
            }

            @Override
            public void onStderr(String line) {
                JSONObject arg = new JSONObject();
                arg.put("source", nodeName);
                arg.put("type", "stderr");
                arg.put("data", line);
                multiplexer.callByActionName("add", arg.toString());
            }
        };
    }

    /**
     * Reports command result to the multiplexer accumulator actor if available.
     */
    private void reportToAccumulator(Node.CommandResult result) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", this.getName());
            reportArg.put("type", this.object.getCurrentTransitionYaml());
            reportArg.put("data", combineOutput(result));
            multiplexer.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Reports a message to the multiplexer accumulator.
     */
    private void reportOutputToMultiplexer(String nodeName, String message) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", nodeName);
            reportArg.put("type", "error");
            reportArg.put("data", message);
            multiplexer.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Combines stdout and stderr into a single output string.
     */
    private String combineOutput(Node.CommandResult result) {
        return ActorHelper.combineOutput(result);
    }

    /**
     * Extracts a meaningful error message from an ExecutionException.
     */
    private String extractRootCauseMessage(ExecutionException e) {
        return ActorHelper.extractRootCauseMessage(e);
    }

    /**
     * Extracts a command string from JSON array arguments.
     */
    private String extractCommandFromArgs(String arg) {
        return ActorHelper.extractCommandFromArgs(arg);
    }
}
