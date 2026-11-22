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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.Transition;
import com.scivicslab.turingworkflow.plugins.logoutput.WorkflowStreamingAccumulator;

/**
 * Executes workflows that control remote nodes via SSH and manages document change detection and deployment.
 *
 * <ul>
 * <li>`executeCommand(String command)` or `executeCommand(String command, Node.OutputCallback callback)` runs a
 * command on the remote node and returns a {@link Node.CommandResult} containing the exit code and output.</li>
 * <li>`executeSudoCommand(String command)` or `executeSudoCommand(String command, Node.OutputCallback callback)` runs
 * a command with sudo privileges using the {@code SUDO_PASSWORD} environment variable and returns a {@link
 * Node.CommandResult}.</li>
 * <li>`runWorkflow(String workflowFile, int maxIterations)` loads and executes a workflow file, optionally applying
 * variables from an overlay directory, and returns an {@link ActionResult} indicating completion success.</li>
 * <li>`detectDocumentChanges(String docListPath)` identifies changed documents by checking git status for each path in
 * the provided list and returns an {@link ActionResult} with a summary.</li>
 * <li>`cloneChangedDocuments(String docListPath)` performs a fresh git clone for each changed document identified in
 * the list and returns an {@link ActionResult} with the count of cloned documents.</li>
 * <li>`buildChangedDocuments(String docListPath)` runs build commands for changed documents and returns an {@link
 * ActionResult} with the count of successfully built documents.</li>
 * <li>`deployChangedDocuments(String docListPath)` copies the build directories of changed documents to a public HTML
 * directory and returns an {@link ActionResult} with the count of deployed documents.</li>
 * </ul>
 *
 * <p>The class holds a set of detected document names and the current transition's YAML snippet for reporting.</p>
 *
 * <p>One instance serves a single workflow execution at a time.</p>
 *
 * <pre>{@code
 * nodeInterpreter.executeCommand("ls -la");
 * }</pre>
 *
 * <p>If a command fails or an SSH connection cannot be established, the actions return a failure {@link ActionResult}
 * with an error message.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 */
public class NodeInterpreter extends Interpreter {

    private static final Logger logger = Logger.getLogger(NodeInterpreter.class.getName());

    /**
     * System property: seconds one git command run by {@code detectDocumentChanges} ({@code git fetch}, {@code git
     * rev-parse}) may take before it is killed and the document is reported as {@code [UNKNOWN]}, which counts as
     * changed. Default {@value #DEFAULT_GIT_TIMEOUT_SECONDS}.
     */
    public static final String GIT_TIMEOUT_PROPERTY = "turingworkflow.ssh.gitTimeoutSeconds";

    /** Default of {@value #GIT_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_GIT_TIMEOUT_SECONDS = 300;

    /** The {@link Node} instance that performs the actual SSH operations and command execution. */
    private final Node node;

    /** The path to the directory containing {@code overlay-conf.yaml} for the YAML overlay feature. */
    private String overlayDir;

    /** Holds the first 10 lines of the current transition in YAML format for reporting. */
    private String currentTransitionYaml = "";

    /** Holds the names of documents identified as changed during the current workflow execution. */
    private final Set<String> changedDocuments = new HashSet<>();

    /**
     * Holds the {@link WorkflowStreamingAccumulator} used to render workflow step transitions as cowsay messages.
     */
    private WorkflowStreamingAccumulator accumulator = null;

    /**
     * Initializes a new workflow interpreter that executes steps on the specified {@link Node} using the given actor
     * system.
     *
     * @param node the {@link Node} instance to use for SSH and command execution
     * @param system the actor system used to coordinate workflow execution and communication between actors
     */
    public NodeInterpreter(Node node, IIActorSystem system) {
        super();
        this.node = node;
        this.system = system;
        // Initialize parent's logger (used by Interpreter.runWorkflow error handling)
        super.logger = logger;
    }

    /**
     * Executes a command on the remote node via SSH.
     *
     * Returns a {@link Node.CommandResult} containing the exit code, stdout, and stderr of the execution.
     *
     * An {@link IOException} is thrown if the SSH connection fails.
     *
     * @param command the command string to execute on the remote host
     * @return the result of the command execution, including its exit status and output
     */
    public Node.CommandResult executeCommand(String command) throws IOException {
        return node.executeCommand(command);
    }

    /**
     * Executes a command on the remote node via SSH and provides real-time access to its output through a callback.
     *
     * @param command the command to execute
     * @param callback the callback for real-time output, which may be null if no streaming is desired
     * @return the result of the command execution, containing the exit code and captured stdout/stderr
     * @throws IOException if the SSH connection fails or an I/O error occurs during execution
     */
    public Node.CommandResult executeCommand(String command, Node.OutputCallback callback) throws IOException {
        return node.executeCommand(command, callback);
    }

    /**
     * Executes a command with sudo privileges on the remote node.
     *
     * Returns a {@link Node.CommandResult} containing the exit code and output of the command.
     *
     * An {@link IOException} is thrown if the SSH connection fails or if the {@code SUDO_PASSWORD} environment
     * variable is not set.
     *
     * @param command the command to execute with sudo
     * @return the result of the command execution
     * @throws IOException if the SSH connection fails or if {@code SUDO_PASSWORD} is not set
     */
    public Node.CommandResult executeSudoCommand(String command) throws IOException {
        return node.executeSudoCommand(command);
    }

    /**
     * Executes a command with sudo privileges on the remote node and sends its output to the specified callback.
     *
     * Returns a {@link Node.CommandResult} containing the exit code and the combined stdout and stderr of the command.
     *
     * Requires the {@code SUDO_PASSWORD} environment variable to be set.
     *
     * @param command the command to execute with sudo
     * @param callback the callback for real-time output, which may be null
     * @return a result containing the exit code and output of the command
     * @throws IOException if the SSH connection fails or the {@code SUDO_PASSWORD} environment variable is not set
     */
    public Node.CommandResult executeSudoCommand(String command, Node.OutputCallback callback) throws IOException {
        return node.executeSudoCommand(command, callback);
    }

    /** Returns the hostname of the remote node. */
    public String getHostname() {
        return node.getHostname();
    }

    /** Returns the username used for SSH connections. */
    public String getUser() {
        return node.getUser();
    }

    /** Returns the SSH port number used for the connection. */
    public int getPort() {
        return node.getPort();
    }

    /**
     * Returns the underlying {@link Node} instance.
     *
     * <p>Use this to access the core SSH functionality directly when workflow-level features are not required.</p>
     *
     * @return the wrapped {@link Node} instance
     */
    public Node getNode() {
        return node;
    }

    /**
     * Sets the directory path used for the YAML overlay feature.
     *
     * @param overlayDir the path to the directory containing {@code overlay-conf.yaml}
     */
    public void setOverlayDir(String overlayDir) {
        this.overlayDir = overlayDir;
    }

    /**
     * Returns the path to the directory containing the YAML overlay configuration, or {@code null} if no overlay is
     * configured.
     */
    public String getOverlayDir() {
        return overlayDir;
    }

    /**
     * Sets the accumulator that renders cowsay-style visual transitions during workflow execution.
     *
     * @param accumulator the object that renders the cowsay output
     */
    public void setAccumulator(WorkflowStreamingAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    /**
     * Returns the {@link WorkflowStreamingAccumulator} used to render workflow transitions as cowsay art, or {@code
     * null} if no accumulator is set.
     */
    public WorkflowStreamingAccumulator getAccumulator() {
        return accumulator;
    }

    /**
     * Triggers a visual notification to the output multiplexer when a workflow enters a new transition.
     *
     * <p>The notification contains the workflow name and the first 10 lines of the transition definition in YAML
     * format, rendered as a cowsay message if a {@link WorkflowStreamingAccumulator} is set. If no accumulator is set,
     * the notification is sent as plain text. The output is sent to the {@code outputMultiplexer} actor.</p>
     *
     * @param transition the transition being entered
     */
    @Override
    protected void onEnterTransition(Transition transition) {
        // Get workflow name
        String workflowName = (getCode() != null && getCode().getName() != null)
                ? getCode().getName()
                : "unknown-workflow";

        // Get YAML-formatted output (first 10 lines)
        String yamlText = transition.toYamlString(10).trim();
        this.currentTransitionYaml = yamlText;

        // Render cowsay output
        String cowsayOutput;
        if (accumulator != null) {
            cowsayOutput = accumulator.renderCowsay(workflowName, yamlText);
        } else {
            // Fallback to simple text if no accumulator
            cowsayOutput = "[" + workflowName + "]\n" + yamlText;
        }

        // Send cowsay output to outputMultiplexer (loose coupling via ActorSystem)
        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject arg = new JSONObject();
            // Use actor name as source (consistent across all output)
            arg.put("source", selfActorRef != null ? selfActorRef.getName() : "unknown");
            arg.put("type", "cowsay");
            arg.put("data", cowsayOutput);
            multiplexer.callByActionName("add", arg.toString());
        }
    }

    /**
     * Sends a transition report to the {@code outputMultiplexer} after a transition finishes.
     *
     * <p>The report contains the transition label (or the state names if no label is present), the status (SUCCESS or
     * FAILED), any transition note (truncated to 60 characters), and the result message (truncated to 500 characters).
     * The report is sent as a JSON object with the type {@code transition-success} or {@code transition-failed}.</p>
     *
     * @param transition the transition that was attempted
     * @param success whether the transition succeeded
     * @param result the result of the transition's actions, which provides the message for the report
     */
    @Override
    protected void onExitTransition(Transition transition, boolean success, ActionResult result) {
        String label = transition.getLabel();
        if (label == null && transition.getStates() != null && transition.getStates().size() >= 2) {
            label = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
        }

        // Get note (up to 60 chars or first line)
        String note = getTransitionNote(transition);

        String status = success ? "SUCCESS" : "FAILED";
        String resultMsg = result != null ? result.getResult() : "";
        // Truncate long result messages
        if (resultMsg.length() > 500) {
            resultMsg = resultMsg.substring(0, 500) + "...";
        }
        // Include note in message if present
        String message = "Transition " + status + ": " + label +
                (note.isEmpty() ? "" : " [" + note + "]") +
                (resultMsg.isEmpty() ? "" : " - " + resultMsg);

        // Send to outputMultiplexer
        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject arg = new JSONObject();
            arg.put("source", selfActorRef != null ? selfActorRef.getName() : "unknown");
            arg.put("type", success ? "transition-success" : "transition-failed");
            arg.put("label", label);
            arg.put("data", message);
            multiplexer.callByActionName("add", arg.toString());
        }
    }

    /**
     * Returns a string that is the first line of the transition's note, truncated to 60 characters.
     *
     * An empty string is returned if the note is null or empty. If the note exceeds 60 characters, the result ends
     * with "...".
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

    /** Returns a string that is the first 10 lines of the current transition in YAML format. */
    public String getCurrentTransitionYaml() {
        return currentTransitionYaml;
    }

    /**
     * Runs a workflow file to completion, optionally applying variables from an overlay directory.
     *
     * Returns an {@link ActionResult} that is a success if the workflow reaches the "end" state, or a failure with an
     * error message if an exception occurs or the workflow fails to complete.
     *
     * If an overlay directory is set via {@link #setOverlayDir(String)}, the workflow is loaded with variables from
     * the {@code overlay-conf.yaml} in that directory substituted into the workflow definition before execution.
     *
     * @param workflowFile the path to the workflow file (YAML or JSON)
     * @param maxIterations the maximum number of state transitions allowed before stopping
     * @return an ActionResult indicating whether the workflow completed successfully and any error messages
     * encountered
     */
    @Override
    public ActionResult runWorkflow(String workflowFile, int maxIterations) {
        // If no overlay is set, use parent implementation
        if (overlayDir == null) {
            return super.runWorkflow(workflowFile, maxIterations);
        }

        try {
            // Reset state for fresh execution
            reset();

            // Resolve workflow file path
            Path workflowPath;
            if (workflowBaseDir != null) {
                workflowPath = Path.of(workflowBaseDir, workflowFile);
            } else {
                workflowPath = Path.of(workflowFile);
            }

            // Load workflow with overlay applied
            Path overlayPath = Path.of(overlayDir);
            readYaml(workflowPath, overlayPath);

            // Run until end
            return runUntilEnd(maxIterations);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error running workflow with overlay: " + workflowFile, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Document Change Detection API (replaces /tmp file-based approach)
    // ========================================================================

    /**
     * Identifies which documents in a list require processing by checking for new files or remote git changes, and
     * stores their names in the actor's state.
     *
     * Returns an action result that is a success with a message containing the count of detected documents if the list
     * was found and processed, and a failure if the file does not exist or an error occurs.
     *
     * If the environment variable {@code FORCE_FULL_BUILD} is set to {@code true}, all documents in the list are
     * marked as changed regardless of their actual status.
     *
     * @param docListPath the path to the file containing the list of document paths and optional git URLs, which may
     * use {@code ~} to represent the home directory
     *
     * @return an action result that is a success with a message stating the number of detected documents, or a failure
     * if the file is missing or an error occurs
     *
     * @throws IOException if an error occurs during file reading or git operations
     */
    public ActionResult detectDocumentChanges(String docListPath) throws IOException {
        // Clear previous results
        changedDocuments.clear();

        // Expand ~ to home directory
        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        if (!Files.exists(listPath)) {
            return new ActionResult(false, "Document list not found: " + docListPath);
        }

        // Check for FORCE_FULL_BUILD environment variable
        boolean forceBuild = "true".equalsIgnoreCase(System.getenv("FORCE_FULL_BUILD"));

        StringBuilder summary = new StringBuilder();

        if (forceBuild) {
            summary.append("=== FORCE_FULL_BUILD enabled: processing all documents ===\n");
        } else {
            summary.append("=== Detecting changes via git fetch ===\n");
        }

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse: path git_url
                String[] parts = line.split("\\s+", 2);
                String path = parts[0].replace("~", System.getProperty("user.home"));
                String docName = Path.of(path).getFileName().toString();

                if (forceBuild) {
                    changedDocuments.add(docName);
                    summary.append("  [FORCE] ").append(docName).append("\n");
                    continue;
                }

                // Check document status
                Path docPath = Path.of(path);
                Path gitPath = docPath.resolve(".git");

                if (!Files.exists(docPath)) {
                    // New document
                    changedDocuments.add(docName);
                    summary.append("  [NEW] ").append(docName).append("\n");
                } else if (!Files.exists(gitPath)) {
                    // Not a git repository
                    changedDocuments.add(docName);
                    summary.append("  [NO-GIT] ").append(docName).append("\n");
                } else {
                    // Check for remote changes using git
                    String status = checkGitStatus(docPath);
                    if (status.startsWith("[CHANGED]") || status.startsWith("[UNKNOWN]")) {
                        changedDocuments.add(docName);
                    }
                    summary.append("  ").append(status).append(" ").append(docName).append("\n");
                }
            }
        }

        summary.append("\n=== Change detection summary ===\n");
        if (changedDocuments.isEmpty()) {
            summary.append("No changes detected. All documents are up to date.\n");
        } else {
            summary.append("Documents to process: ").append(changedDocuments.size()).append("\n");
            for (String doc : changedDocuments) {
                summary.append(doc).append("\n");
            }
        }

        // Print summary (like the original shell script did)
        System.out.println(summary);

        return new ActionResult(true, "Detected " + changedDocuments.size() + " changed documents");
    }

    /**
     * Returns a status string indicating whether the document's remote repository has changes relative to the local
     * version.
     *
     * The result is one of "[CHANGED]", "[UP-TO-DATE]", or "[UNKNOWN]".
     *
     * @param docPath the path to the document directory
     */
    private String checkGitStatus(Path docPath) {
        try {
            // A fetch that failed or did not finish leaves origin/* stale, so the comparison below would mean nothing
            ProcessWithLimit.Outcome fetch = runGit(docPath, "git", "fetch", "origin");
            if (!fetch.finished()) {
                return "[UNKNOWN] (git fetch in " + docPath + " did not finish within " + gitLimitSeconds()
                        + " s and was killed (" + GIT_TIMEOUT_PROPERTY + "))";
            }
            if (fetch.exitCode() != 0) {
                return "[UNKNOWN] (git fetch in " + docPath + " failed with exit " + fetch.exitCode() + ": "
                        + fetch.stderr() + ")";
            }

            // Get local HEAD
            String local = runGitCommand(docPath, "git", "rev-parse", "HEAD");

            // Get remote HEAD (try main first, then master)
            String remote = runGitCommand(docPath, "git", "rev-parse", "origin/main");
            if (remote == null || remote.equals("unknown")) {
                remote = runGitCommand(docPath, "git", "rev-parse", "origin/master");
            }

            if (local == null || remote == null || local.equals("unknown") || remote.equals("unknown")) {
                return "[UNKNOWN] (cannot determine state)";
            }

            if (!local.equals(remote)) {
                String localShort = local.length() > 7 ? local.substring(0, 7) : local;
                String remoteShort = remote.length() > 7 ? remote.substring(0, 7) : remote;
                return "[CHANGED] (local: " + localShort + ", remote: " + remoteShort + ")";
            }

            return "[UP-TO-DATE]";

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking git status for " + docPath, e);
            return "[UNKNOWN] (error: " + e.getMessage() + ")";
        }
    }

    /**
     * Returns the first line of output from a git command executed in the specified directory.
     *
     * @param workDir the directory in which the command is executed
     * @param command the command and its arguments to run
     * @return the first line of the command's standard output, trimmed of whitespace, or "unknown" if the command
     * fails or produces no output
     */
    private String runGitCommand(Path workDir, String... command) {
        try {
            ProcessWithLimit.Outcome outcome = runGit(workDir, command);
            if (!outcome.finished() || outcome.exitCode() != 0 || outcome.stdout().isEmpty()) {
                return "unknown";
            }
            return outcome.stdout().lines().findFirst().orElse("unknown").trim();
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /**
     * Returns the outcome of a git command run in {@code workDir}, which may run for {@value #GIT_TIMEOUT_PROPERTY}
     * seconds before it is killed; its stdout and stderr are read while it runs.
     */
    private static ProcessWithLimit.Outcome runGit(Path workDir, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).start();
        // git never reads stdin here; closing it stops a credential prompt from waiting for input
        process.getOutputStream().close();
        return ProcessWithLimit.waitFor(process, gitLimitSeconds(), null, null);
    }

    private static long gitLimitSeconds() {
        return SystemPropertyLimit.read(GIT_TIMEOUT_PROPERTY, DEFAULT_GIT_TIMEOUT_SECONDS);
    }

    /**
     * Returns true if the specified document name is among those detected as changed during the last document change
     * detection.
     *
     * @param docName the name of the document to check
     */
    public boolean isDocumentChanged(String docName) {
        return changedDocuments.contains(docName);
    }

    /**
     * Returns the number of documents currently marked as changed.
     *
     * @return the count of changed documents
     */
    public int getChangedDocumentsCount() {
        return changedDocuments.size();
    }

    /**
     * Returns an unmodifiable set containing the names of the documents that were detected as changed.
     *
     * @return a set of the names of the changed documents
     */
    public Set<String> getChangedDocuments() {
        return Set.copyOf(changedDocuments);
    }

    /**
     * Returns true if the set of documents detected as changed by {@link #detectDocumentChanges(String)} is not empty.
     */
    public boolean hasChangedDocuments() {
        return !changedDocuments.isEmpty();
    }

    /** Removes all document names currently held in the changed documents list. */
    public void clearChangedDocuments() {
        changedDocuments.clear();
    }

    /**
     * Adds a document name to the set of documents marked for processing.
     *
     * @param docName the name of the document to add
     */
    public void addChangedDocument(String docName) {
        changedDocuments.add(docName);
    }

    /**
     * Performs a fresh git clone of all documents identified as changed in the current session.
     *
     * Returns an action result that is a success with a message containing the number of cloned documents if at least
     * one document was cloned or if no documents were marked as changed; it is a failure if the document list file is
     * not found or if an I/O error occurs.
     *
     * If a document is in the changed list, it removes the existing directory at the specified path and executes a
     * fresh git clone using the URL provided in the document list.
     *
     * @param docListPath the path to the file containing the list of document paths and their corresponding git URLs,
     * where {@code ~} may be used to represent the home directory
     *
     * @return an action result indicating whether the cloning process completed, with a message stating the number of
     * documents cloned
     */
    public ActionResult cloneChangedDocuments(String docListPath) throws IOException {
        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to clone (all up to date) ===");
            return new ActionResult(true, "No documents to clone");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        // Ensure ~/works exists
        node.executeCommand("mkdir -p ~/works");

        System.out.println("=== Cloning changed documents ===");
        int clonedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String gitUrl = parts.length > 1 ? parts[1] : null;
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                if (gitUrl != null && !gitUrl.isEmpty()) {
                    // Remove old and clone fresh
                    System.out.println("=== Cloning: " + gitUrl + " -> " + path + " ===");
                    node.executeCommand("rm -rf " + path);
                    Node.CommandResult result = node.executeCommand("git clone " + gitUrl + " " + path);
                    if (result.getExitCode() == 0) {
                        clonedCount++;
                    } else {
                        System.err.println("Clone failed: " + result.getStderr());
                    }
                } else {
                    System.out.println("=== No git URL specified for: " + path + " ===");
                }
            }
        }

        return new ActionResult(true, "Cloned " + clonedCount + " documents");
    }

    /**
     * Runs {@code yarn install} and {@code yarn build} in the directories of documents identified as changed.
     *
     * <p>Returns an ActionResult that is a success if the build process for the documents was attempted, with a
     * message containing the count of successfully built documents; it is not a success if an {@link IOException}
     * occurs or if the document list cannot be read.</p>
     *
     * @param docListPath the path to the file containing the list of document directories
     * @return an ActionResult where success is true and the message holds the number of built documents
     * @throws IOException if the document list file cannot be read or accessed
     */
    public ActionResult buildChangedDocuments(String docListPath) throws IOException {
        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to build (all up to date) ===");
            return new ActionResult(true, "No documents to build");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        System.out.println("=== Building changed documents ===");
        int builtCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                System.out.println("=== Building: " + path + " ===");
                Node.CommandResult result = node.executeCommand(
                    "cd " + path + " && yarn install && yarn build"
                );
                if (result.getExitCode() == 0) {
                    builtCount++;
                } else {
                    System.err.println("Build failed for " + docName + ": " + result.getStderr());
                }
            }
        }

        return new ActionResult(true, "Built " + builtCount + " documents");
    }

    /**
     * Copies the built versions of changed documents to the {@code ~/public_html} directory on the remote node.
     *
     * <p>Returns an ActionResult that is a success if the deployment process completes, with a message containing the
     * number of documents copied. If no documents are marked as changed, it returns a success result stating that no
     * documents were copied. If an I/O error occurs, it throws an IOException.</p>
     *
     * @param docListPath the path to the file listing the document directories, which may include {@code ~} for the
     * home directory
     *
     * @return an ActionResult that is a success if the deployment process completes, with a message containing the
     * number of documents copied; otherwise, a failure result with an error message
     */
    public ActionResult deployChangedDocuments(String docListPath) throws IOException {
        // Ensure public_html exists
        node.executeCommand("mkdir -p ~/public_html");

        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to copy (all up to date) ===");
            node.executeCommand("ls -la ~/public_html/");
            return new ActionResult(true, "No documents to copy");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        System.out.println("=== Copying changed documents to public_html ===");
        int copiedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                String buildPath = path + "/build";
                String destPath = "~/public_html/" + docName;

                System.out.println("=== Copying " + docName + " to public_html ===");
                node.executeCommand("rm -rf " + destPath);
                Node.CommandResult result = node.executeCommand(
                    "cp -r " + buildPath + " " + destPath
                );
                if (result.getExitCode() == 0) {
                    copiedCount++;
                } else {
                    System.err.println("Copy failed for " + docName + ": " + result.getStderr());
                }
            }
        }

        System.out.println("=== public_html contents ===");
        node.executeCommand("ls -la ~/public_html/");

        return new ActionResult(true, "Deployed " + copiedCount + " documents");
    }
}
