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

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;


/**
 * An actor reference for {@link NodeInterpreter} that executes workflow control logic and remote shell commands.
 *
 * <p>The following actions are available to a workflow:
 * <ul>
 * <li>{@code execCode} - executes the loaded workflow code</li>
 * <li>{@code readYaml} - loads a YAML workflow from a file path</li>
 * <li>{@code readJson} - loads a JSON workflow from a file path</li>
 * <li>{@code readXml} - loads an XML workflow from a file path</li>
 * <li>{@code reset} - clears the interpreter state and loaded code</li>
 * <li>{@code runUntilEnd} - runs the loaded workflow until completion or a specified iteration limit</li>
 * <li>{@code call} - loads and prepares a sub-workflow from a file</li>
 * <li>{@code apply} - applies a JSON transformation to the current state</li>
 * <li>{@code executeCommand} - runs a command and streams output to the accumulator</li>
 * <li>{@code executeCommandQuiet} - runs a command and returns the exit code and output as a string without
 * streaming</li>
 * <li>{@code executeSudoCommand} - runs a command with sudo privileges and streams output to the accumulator</li>
 * <li>{@code executeSudoCommandQuiet} - runs a command with sudo privileges and returns the exit code and output as a
 * string without streaming</li>
 * <li>{@code sleep} - pauses execution for a specified number of milliseconds</li>
 * <li>{@code print} - prints a text message to standard output</li>
 * <li>{@code doNothing} - returns the input argument unchanged</li>
 * <li>{@code detectDocumentChanges} - identifies changed documents via git comparison</li>
 * <li>{@code cloneChangedDocuments} - clones the documents identified by {@code detectDocumentChanges}</li>
 * <li>{@code buildChangedDocuments} - builds the documents identified by {@code detectDocumentChanges}</li>
 * <li>{@code deployChangedDocuments} - copies the builds of changed documents to the public directory</li>
 * <li>{@code printJson} - sends the current state as a formatted JSON string to the accumulator</li>
 * <li>{@code printYaml} - sends the current state as a formatted YAML string to the accumulator</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once, as actions are executed one at a time via the underlying
 * interpreter.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 */
public class NodeActor extends IIActorRef<NodeInterpreter> {

    /** A logger instance used to record the actor's activities and errors. */
    Logger logger = null;

    /**
     * Initializes a new actor reference for a {@link NodeInterpreter} with a specific name.
     *
     * @param actorName the name used to call this actor in a workflow
     * @param object the {@link NodeInterpreter} instance this actor controls
     */
    public NodeActor(String actorName, NodeInterpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /** Initializes a new {@code NodeActor} with a name, a {@link NodeInterpreter} instance, and an actor system. */
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
    /**
     * Represents a path in the file system.
     *
     * @param path the path to read
     */
    public record PathArgs(@NotNull String path) {}

    /**
     * Represents a piece of text.
     *
     * @param text the text content
     */
    public record TextArgs(@NotNull String text) {}

    /**
     * Pauses execution for the specified duration.
     *
     * @param millis the number of milliseconds to wait
     */
    public record SleepArgs(@NotNull Long millis) {}

    /**
     * How many times the interpreter may step before giving up.
     *
     * @param maxIterations the limit; omit for 10000
     */
    public record MaxIterationsArgs(Integer maxIterations) {}

    /**
     * The path to a workflow file.
     *
     * @param workflowFile the path to the workflow file
     */
    public record WorkflowFileArgs(@NotNull String workflowFile) {}

    /**
     * Specifies a workflow file to run and the maximum number of steps allowed.
     *
     * @param workflowFile the path of the workflow file
     * @param maxIterations the limit of steps; if null, defaults to 10000
     */
    public record RunWorkflowArgs(@NotNull String workflowFile, Integer maxIterations) {}

    /**
     * Applies a JSON transformation to the interpreter's state.
     *
     * @param overlay the JSON transformation to apply
     */
    public record OverlayArgs(@NotNull String overlay) {}

    /**
     * The path to a file containing a list of documents.
     *
     * @param documentList the path to the file listing the documents
     */
    public record DocumentListArgs(@NotNull String documentList) {}

    /**
     * Represents the command-line string to be executed on the node.
     *
     * @param command the command to run
     */
    public record CommandArgs(@NotNull String command) {}

    @Action("execCode")
    public ActionResult execCode(String args) {
        try {
            return this.ask(n -> n.execCode()).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Loads a YAML workflow definition from a file path.
     *
     * <p>If an overlay directory is configured on the interpreter, the YAML is loaded with overlay variable
     * substitution applied.</p>
     *
     * <p>JSON args format: {@code ["path/to/workflow.yaml"]}</p>
     *
     * @param args a {@link PathArgs} containing the file path to read
     * @return an {@link ActionResult} indicating success or failure with error details
     */
    @Action(value = "readYaml", argsType = PathArgs.class)
    public ActionResult readYaml(PathArgs args) {
        String arg = args.path();
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
     * Clears the interpreter state, including the loaded workflow code and the current state.
     *
     * @param args a JSON array string (unused)
     * @return an ActionResult indicating success or failure
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
     * Runs the loaded workflow until it reaches an end state or exceeds the specified iteration limit.
     *
     * <p>The result is an {@link ActionResult} indicating whether the workflow completed successfully or failed, with
     * the message containing the execution outcome.</p>
     *
     * <p>If the first argument in the JSON array is omitted, the limit is 10000 iterations.</p>
     *
     * @param args a JSON array containing the optional maximum number of iterations as its first element.
     * @return an {@link ActionResult} describing the workflow's execution outcome.
     */
    @Action(value = "runUntilEnd", argsType = MaxIterationsArgs.class)
    public ActionResult runUntilEnd(MaxIterationsArgs args) {
        try {
            int maxIterations = args.maxIterations() == null ? 10000 : args.maxIterations();
            return this.ask(n -> n.runUntilEnd(maxIterations)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Loads and prepares a sub-workflow from a file.
     *
     * <p>Returns an {@link ActionResult} indicating the outcome of the sub-workflow execution.</p>
     *
     * <p>JSON args format: {@code ["path/to/sub-workflow.yaml"]}</p>
     *
     * @param args a {@link WorkflowFileArgs} containing the path to the workflow file
     */
    @Action(value = "call", argsType = WorkflowFileArgs.class)
    public ActionResult call(WorkflowFileArgs args) {
        try {
            String callWorkflowFile = args.workflowFile();
            return this.ask(n -> n.call(callWorkflowFile)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    /**
     * Loads and runs a workflow file to completion in a single action.
     *
     * <p>The action returns an {@link ActionResult} indicating the outcome of the workflow execution. If the workflow
     * fails, the result message contains the error details.</p>
     *
     * <p>JSON args format: {@code ["path/to/workflow.yaml"]} or {@code ["path/to/workflow.yaml", maxIterations]}.</p>
     *
     * @param args a record containing the path to the workflow file and an optional maximum number of iterations
     *
     * @return an ActionResult with the workflow execution outcome
     */
    @Action(value = "runWorkflow", argsType = RunWorkflowArgs.class)
    public ActionResult runWorkflow(RunWorkflowArgs args) {
        try {
            String runWorkflowFile = args.workflowFile();
            int runMaxIterations = args.maxIterations() == null ? 10000 : args.maxIterations();
            logger.fine(String.format("Running workflow: %s (maxIterations=%d)", runWorkflowFile, runMaxIterations));
            ActionResult result = this.object.runWorkflow(runWorkflowFile, runMaxIterations);
            logger.fine(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
            return result;
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Applies a JSON transformation to the interpreter's state.
     *
     * <p>Returns an ActionResult indicating whether the transformation was successfully applied to the current
     * state.</p>
     *
     * @param args a single argument containing the JSON transformation (as a patch or string) to apply
     */
    @Action(value = "apply", argsType = OverlayArgs.class)
    public ActionResult apply(OverlayArgs args) {
        try {
            return this.ask(n -> n.apply(args.overlay())).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // Command Execution Actions
    // ========================================================================

    /**
     * Executes a command on the remote node and streams its output to the accumulator.
     *
     * <p>Output is streamed in real-time to the {@code outputMultiplexer} if available. After execution, the combined
     * stdout and stderr are reported to the accumulator and returned in the result message.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args a {@link CommandArgs} containing the command string to run
     * @return an {@link ActionResult} that is a success if the command exit code is 0, with the combined stdout and
     * stderr as the message; otherwise, a failure with the combined output as the message
     */
    @Action(value = "executeCommand", argsType = CommandArgs.class)
    public ActionResult executeCommand(CommandArgs args) {
        try {
            String command = args.command();
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
     * Executes a command on the remote node and returns the exit code and output as a single string.
     *
     * <p>Unlike {@code executeCommand}, this action does not stream output to the accumulator or report results to the
     * {@code outputMultiplexer}.</p>
     *
     * @param args a JSON array containing the command as the first element
     * @return an ActionResult that is a success if the command ran, with a message containing the exit code, stdout,
     * and stderr; otherwise, a failure result with the error details
     */
    @Action(value = "executeCommandQuiet", argsType = CommandArgs.class)
    public ActionResult executeCommandQuiet(CommandArgs args) {
        try {
            String command = args.command();
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
     * Executes a command with sudo privileges and reports the combined stdout and stderr to the outputMultiplexer.
     *
     * <p>Requires the {@code SUDO_PASSWORD} environment variable to be set. Output is sent to the accumulator in
     * real-time as it is produced.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args a JSON array containing the command as the first element
     * @return an ActionResult that is a success if the command exited with code 0, with a message containing the
     * combined stdout and stderr; otherwise, a failure result with an error message
     */
    @Action(value = "executeSudoCommand", argsType = CommandArgs.class)
    public ActionResult executeSudoCommand(CommandArgs args) {
        try {
            String command = args.command();
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
     * Executes a command with sudo privileges without reporting output to the accumulator.
     *
     * <p>Requires the {@code SUDO_PASSWORD} environment variable to be set. Unlike {@code executeSudoCommand}, this
     * action does not stream output to the outputMultiplexer.</p>
     *
     * <p>JSON args format: {@code ["command-to-execute"]}</p>
     *
     * @param args a JSON array containing the command as the first element
     * @return an ActionResult that is a success if the command exits with code 0, with a message containing the exit
     * code, stdout, and stderr; otherwise, a failure result with the error details
     */
    @Action(value = "executeSudoCommandQuiet", argsType = CommandArgs.class)
    public ActionResult executeSudoCommandQuiet(CommandArgs args) {
        try {
            String command = args.command();
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
     * Pauses the current actor's execution for a specified duration.
     *
     * <p>Returns an ActionResult that is a success if the sleep completed, or a failure if the thread was interrupted.
     *
     * <p>The duration is the number of milliseconds specified in the first element of the JSON array argument.
     *
     * @param args a SleepArgs object containing the number of milliseconds to wait
     *
     * @return an ActionResult that is a success with a message stating the duration slept, or a failure if the sleep
     * was interrupted
     */
    @Action(value = "sleep", argsType = SleepArgs.class)
    public ActionResult sleep(SleepArgs args) {
        try {
            long millis = args.millis();
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
     * Writes a text message to the standard output.
     *
     * @param args the object containing the text to be printed
     * @return an ActionResult that is a success and contains the printed text as its message
     */
    @Action(value = "print", argsType = TextArgs.class)
    public ActionResult print(TextArgs args) {
        String text = args.text();
        System.out.println(text);
        return new ActionResult(true, "Printed: " + text);
    }

    /**
     * Returns the provided text as a successful action result.
     *
     * <p>The result is the text passed in the argument.</p>
     *
     * @param args the text to return
     * @return an ActionResult that is a success and holds the input text as its message
     */
    @Action(value = "doNothing", argsType = TextArgs.class)
    public ActionResult doNothing(TextArgs args) {
        return new ActionResult(true, args.text());
    }

    // ========================================================================
    // Document Workflow Actions
    // ========================================================================

    /**
     * Detects which documents have changed by comparing local and remote git state.
     *
     * <p>Returns an ActionResult where a success indicates the comparison completed, and the message holds the number
     * of changed documents detected.</p>
     *
     * @param args a record containing the path to the file listing the documents to check
     */
    @Action(value = "detectDocumentChanges", argsType = DocumentListArgs.class)
    public ActionResult detectDocumentChanges(DocumentListArgs args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = args.documentList();
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
     * Clones documents that were detected as changed from their git repositories, returning the number of documents
     * successfully cloned.
     *
     * <p>This action only processes documents previously identified by {@code detectDocumentChanges}. It performs a
     * fresh clone by removing any existing directory for a document before pulling the new version to avoid
     * conflicts.</p>
     *
     * <p>JSON args format: {@code ["path/to/doc-list.txt"]}</p>
     *
     * @param args a JSON array containing the path to the file listing the documents as the first element
     * @return an ActionResult that is a success with the number of cloned documents as the message, or a failure with
     * an error message if an IO error occurs or the process is interrupted
     */
    @Action(value = "cloneChangedDocuments", argsType = DocumentListArgs.class)
    public ActionResult cloneChangedDocuments(DocumentListArgs args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = args.documentList();
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
     * Builds Docusaurus documents that were previously identified as changed.
     *
     * <p>Only processes documents listed in the file provided by the argument.</p>
     *
     * @param args a record containing the path to the file listing the documents to build
     * @return an ActionResult that is a success with a message containing the number of documents built, or a failure
     * with an error message if the build fails
     */
    @Action(value = "buildChangedDocuments", argsType = DocumentListArgs.class)
    public ActionResult buildChangedDocuments(DocumentListArgs args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = args.documentList();
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
     * Deploys the build output of changed documents to the {@code ~/public_html/} directory.
     *
     * <p>Only processes documents previously identified by {@code detectDocumentChanges}.
     *
     * @param args a JSON array containing the path to the file listing the documents
     * @return an action result indicating whether the deployment succeeded and the result of the operation
     */
    @Action(value = "deployChangedDocuments", argsType = DocumentListArgs.class)
    public ActionResult deployChangedDocuments(DocumentListArgs args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = args.documentList();
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
     * Returns a pretty-printed JSON string of the current state at the specified path.
     *
     * @param args a single-element array containing the file path to the output destination
     * @return an ActionResult that is a success and holds the formatted JSON string, or a failure if the state could
     * not be serialized or the path is invalid
     */
    @Action(value = "printJson", argsType = PathArgs.class)
    public ActionResult printJson(PathArgs args) {
        String path = args.path();
        String formatted = toStringOfJson(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Sends the current workflow state as a YAML string to the {@code outputMultiplexer} at the specified file path.
     *
     * @param args a {@link PathArgs} containing the file path to the output destination
     * @return an {@link ActionResult} that is a success and holds the formatted YAML string, or a failure if the state
     * could not be serialized or the path is invalid
     */
    @Action(value = "printYaml", argsType = PathArgs.class)
    public ActionResult printYaml(PathArgs args) {
        String path = args.path();
        logger.info(String.format("printYaml called: path='%s'", path));
        String formatted = toStringOfYaml(path);
        logger.info(String.format("printYaml output length: %d", formatted.length()));
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /** Sends a formatted string to the {@code outputMultiplexer} actor. */
    private void sendToMultiplexer(String formatted) {
        ActorHelper.sendToMultiplexer((IIActorSystem) this.system(), this.getName(), formatted);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Returns an unsuccessful {@link ActionResult} containing a descriptive error message when an exception occurs
     * during an action.
     *
     * <p>The result message is the message from the exception, or the root cause message if the exception is an {@link
     * ExecutionException}.</p>
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


    /**
     * Returns a callback that sends command output to the {@code outputMultiplexer} actor.
     *
     * <p>Each line of stdout or stderr received by the callback is wrapped in a JSON object containing the source
     * actor's name, the output type, and the line text, then sent to the {@code outputMultiplexer} via its {@code add}
     * action. If no {@code outputMultiplexer} is found in the actor system, the returned callback has no effect.</p>
     */
    private Node.OutputCallback createOutputCallback(String nodeName) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME);

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
     * Sends the combined stdout and stderr of a command execution to the {@code outputMultiplexer} actor.
     *
     * <p>If an actor named {@code outputMultiplexer} is present in the system, it sends a JSON object containing the
     * source actor's name, the current transition YAML type, and the combined output string. If no such actor exists,
     * the method does nothing.</p>
     */
    private void reportToAccumulator(Node.CommandResult result) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME);
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", this.getName());
            reportArg.put("type", this.object.getCurrentTransitionYaml());
            reportArg.put("data", combineOutput(result));
            multiplexer.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Sends an error message to the {@code outputMultiplexer} actor.
     *
     * <p>The method checks if an actor named {@code outputMultiplexer} is available in the system; if so, it
     * constructs a JSON object containing the source node name, the type {@code "error"}, and the provided message,
     * then calls the {@code add} action on the multiplexer with this JSON string.</p>
     *
     * @param nodeName the name of the node reporting the error
     * @param message the error text to be sent
     */
    private void reportOutputToMultiplexer(String nodeName, String message) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor(MultiplexerAccumulatorIIAR.ACTOR_NAME);
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", nodeName);
            reportArg.put("type", "error");
            reportArg.put("data", message);
            multiplexer.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Returns a single string containing both the standard output and the standard error from a command execution
     * result.
     */
    private String combineOutput(Node.CommandResult result) {
        return ActorHelper.combineOutput(result);
    }

    /** Returns a string containing the root cause message from the provided {@code ExecutionException}. */
    private String extractRootCauseMessage(ExecutionException e) {
        return ActorHelper.extractRootCauseMessage(e);
    }

    /**
     * Extracts a command string from JSON array arguments.
     */
}
