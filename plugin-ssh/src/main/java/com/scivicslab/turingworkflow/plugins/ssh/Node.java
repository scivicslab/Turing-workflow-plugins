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
import java.io.InputStream;
import java.io.InputStreamReader;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Represents a single node in the infrastructure as a pure POJO.
 *
 * <p>This is a pure POJO class that provides SSH-based command execution
 * capabilities. It has NO dependency on ActorSystem or workflow components.</p>
 *
 * <p>Uses ssh-agent for SSH key authentication. Make sure ssh-agent is running
 * and your SSH key is added before using this class.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 */
public class Node {

    /** The hostname or IP address of the remote node. */
    private final String hostname;

    /** The SSH username for authentication. */
    private final String user;

    /** The SSH port number. */
    private final int port;

    /** Whether to execute commands locally instead of via SSH. */
    private final boolean localMode;

    /** The SSH password, or {@code null} to use key-based authentication. */
    private final String password;

    /** Cached SSH session, reused across multiple command executions. */
    private Session cachedSession = null;

    /** Jump host session, kept open for the duration of the proxied connection. */
    private Session jumpHostSession = null;

    /** Whether SSH config has been loaded and cached. */
    private boolean sshConfigLoaded = false;

    /** Cached SSH config repository parsed from {@code ~/.ssh/config}. */
    private com.jcraft.jsch.ConfigRepository cachedConfigRepository = null;

    /** Cached identity file path from SSH config. */
    private String cachedIdentityFile = null;

    /** Cached ProxyJump directive from SSH config. */
    private String cachedProxyJump = null;

    /** Effective username after applying SSH config overrides. */
    private String effectiveUser;

    /** Effective hostname after applying SSH config overrides. */
    private String effectiveHostname;

    /** Effective port after applying SSH config overrides. */
    private int effectivePort;

    /**
     * Constructs a Node with the specified connection parameters.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     */
    public Node(String hostname, String user, int port) {
        this(hostname, user, port, false, null);
    }

    /**
     * Constructs a Node with the specified connection parameters and local mode.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     * @param localMode if true, execute commands locally instead of via SSH
     */
    public Node(String hostname, String user, int port, boolean localMode) {
        this(hostname, user, port, localMode, null);
    }

    /**
     * Constructs a Node with all connection parameters including password.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     * @param localMode if true, execute commands locally instead of via SSH
     * @param password the SSH password (null to use ssh-agent key authentication)
     */
    public Node(String hostname, String user, int port, boolean localMode, String password) {
        this.hostname = hostname;
        this.user = user;
        this.port = port;
        this.localMode = localMode;
        this.password = password;
    }

    /**
     * Constructs a Node with default port 22.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     */
    public Node(String hostname, String user) {
        this(hostname, user, 22, false, null);
    }

    /**
     * Checks if this node is in local execution mode.
     *
     * @return true if commands are executed locally, false for SSH
     */
    public boolean isLocalMode() {
        return localMode;
    }

    /**
     * Executes a command on the node.
     *
     * @param command the command to execute
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if command execution fails
     */
    public CommandResult executeCommand(String command) throws IOException {
        return executeCommand(command, null);
    }

    /**
     * Executes a command on the node with real-time output callback.
     *
     * @param command the command to execute
     * @param callback the callback for real-time output (may be null)
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if command execution fails
     */
    public CommandResult executeCommand(String command, OutputCallback callback) throws IOException {
        if (localMode) {
            return executeLocalCommand(command, callback);
        }
        return executeRemoteCommand(command, callback);
    }

    /**
     * Executes a command locally using ProcessBuilder with real-time streaming.
     */
    private CommandResult executeLocalCommand(String command, OutputCallback callback) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        Process process = pb.start();

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        // Read stderr in virtual thread (Java 21) to avoid deadlock
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrBuilder) {
                        stderrBuilder.append(line).append("\n");
                    }
                    if (callback != null) {
                        callback.onStderr(line);
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });

        // Read stdout with real-time streaming
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdoutBuilder.append(line).append("\n");
                if (callback != null) {
                    callback.onStdout(line);
                }
            }
        }

        try {
            stderrThread.join();
            int exitCode = process.waitFor();
            return new CommandResult(
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                exitCode
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        }
    }

    /**
     * Gets or creates a reusable SSH session.
     * The session is cached and reused across multiple command executions.
     * Synchronized to prevent concurrent session creation races.
     */
    private synchronized Session getOrCreateSession() throws IOException, JSchException {
        if (cachedSession != null && cachedSession.isConnected()) {
            return cachedSession;
        }
        // Previous session died — clean up jump host too
        if (jumpHostSession != null && jumpHostSession.isConnected()) {
            jumpHostSession.disconnect();
        }
        jumpHostSession = null;
        cachedSession = createSession();
        cachedSession.connect();
        return cachedSession;
    }

    /**
     * Executes a command on the remote node via SSH using JSch with real-time streaming.
     * The SSH session is reused across commands for performance.
     */
    private CommandResult executeRemoteCommand(String command, OutputCallback callback) throws IOException {
        ChannelExec channel = null;

        try {
            Session session = getOrCreateSession();

            // Open exec channel (channels are per-command, sessions are reused)
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // Get streams before connecting
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();

            // Connect channel
            channel.connect();

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            // Read stderr in virtual thread (Java 21) to avoid deadlock
            final InputStream stderrFinal = stderr;
            Thread stderrThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderrFinal))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderrBuilder) {
                            stderrBuilder.append(line).append("\n");
                        }
                        if (callback != null) {
                            callback.onStderr(line);
                        }
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            // Read stdout with real-time streaming
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuilder.append(line).append("\n");
                    if (callback != null) {
                        callback.onStdout(line);
                    }
                }
            }

            // Wait for stderr thread
            stderrThread.join();

            // Wait for channel to close
            while (!channel.isClosed()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Command execution interrupted", e);
                }
            }

            int exitCode = channel.getExitStatus();

            return new CommandResult(
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                exitCode
            );

        } catch (JSchException e) {
            // Invalidate cached session on connection errors
            synchronized (this) { cachedSession = null; }

            String message = e.getMessage();
            if (message != null && (message.contains("USERAUTH fail") || message.contains("Auth fail"))) {
                throw new IOException(String.format(
                    "SSH authentication failed for %s@%s:%d.%n" +
                    "%n" +
                    "[~/.ssh/id_ed25519 or ~/.ssh/id_rsa]%n" +
                    "  ssh-add || { eval \"$(ssh-agent -s)\" && ssh-add; }%n" +
                    "%n" +
                    "[Custom key, e.g. ~/.ssh/mykey]%n" +
                    "  ssh-add ~/.ssh/mykey || { eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/mykey; }%n" +
                    "%n" +
                    "Test: ssh %s@%s echo OK",
                    user, hostname, port, user, hostname), e);
            } else if (message != null && (message.contains("Connection refused") || message.contains("connect timed out"))) {
                throw new IOException(String.format(
                    "SSH connection failed to %s:%d - %s. " +
                    "Verify the host is reachable and SSH service is running.",
                    hostname, port, message), e);
            } else if (message != null && message.contains("UnknownHostException")) {
                throw new IOException(String.format(
                    "SSH connection failed: Unknown host '%s'. " +
                    "Check the hostname or IP address in inventory.",
                    hostname), e);
            }
            throw new IOException("SSH connection failed to " + hostname + ": " + message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } finally {
            // Only disconnect the channel, NOT the session (session is reused)
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /** Environment variable name for the sudo password. */
    private static final String SUDO_PASSWORD_ENV = "SUDO_PASSWORD";

    /**
     * Executes a command with sudo privileges on the remote node.
     *
     * @param command the command to execute with sudo
     * @return the execution result
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public CommandResult executeSudoCommand(String command) throws IOException {
        return executeSudoCommand(command, null);
    }

    /**
     * Executes a command with sudo privileges on the remote node with real-time output callback.
     *
     * @param command the command to execute with sudo
     * @param callback the callback for real-time output (may be null)
     * @return the execution result
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public CommandResult executeSudoCommand(String command, OutputCallback callback) throws IOException {
        String sudoPassword = System.getenv(SUDO_PASSWORD_ENV);
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new IOException("SUDO_PASSWORD environment variable is not set");
        }

        if (localMode) {
            return executeLocalSudoCommand(command, sudoPassword, callback);
        }
        return executeRemoteSudoCommand(command, sudoPassword, callback);
    }

    /**
     * Executes a sudo command on a remote node by writing the password to the channel's stdin.
     */
    private CommandResult executeRemoteSudoCommand(String command, String sudoPassword, OutputCallback callback) throws IOException {
        // Escape single quotes in command for bash -c
        String escapedCommand = command.replace("'", "'\"'\"'");
        String sudoCommand = String.format("sudo -S bash -c '%s'", escapedCommand);

        ChannelExec channel = null;
        try {
            Session session = getOrCreateSession();
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(sudoCommand);

            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            java.io.OutputStream channelStdin = channel.getOutputStream();

            channel.connect();

            // Write password to stdin for sudo -S
            channelStdin.write((sudoPassword + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            channelStdin.flush();

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            final InputStream stderrFinal = stderr;
            Thread stderrThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderrFinal))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderrBuilder) {
                            stderrBuilder.append(line).append("\n");
                        }
                        if (callback != null) {
                            callback.onStderr(line);
                        }
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuilder.append(line).append("\n");
                    if (callback != null) {
                        callback.onStdout(line);
                    }
                }
            }

            stderrThread.join();

            while (!channel.isClosed()) {
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Command execution interrupted", e);
                }
            }

            int exitCode = channel.getExitStatus();
            return new CommandResult(
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                exitCode
            );
        } catch (JSchException e) {
            synchronized (this) { cachedSession = null; }
            throw new IOException("SSH error executing sudo command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * Executes a sudo command locally by writing the password to the process stdin.
     */
    private CommandResult executeLocalSudoCommand(String command, String sudoPassword, OutputCallback callback) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("sudo", "-S", "bash", "-c", command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Write password to stdin
        try (java.io.OutputStream stdin = process.getOutputStream()) {
            stdin.write((sudoPassword + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
        }

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuilder.append(line).append("\n");
                    if (callback != null) { callback.onStdout(line); }
                }
            } catch (IOException e) { /* Ignore */ }
        });

        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrBuilder) { stderrBuilder.append(line).append("\n"); }
                    if (callback != null) { callback.onStderr(line); }
                }
            } catch (IOException e) { /* Ignore */ }
        });

        try {
            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(1000);
                stderrThread.join(1000);
                return new CommandResult(stdoutBuilder.toString(), "Command timed out", -1);
            }
            stdoutThread.join(1000);
            stderrThread.join(1000);
            int exitCode = process.exitValue();
            return new CommandResult(stdoutBuilder.toString().trim(), stderrBuilder.toString().trim(), exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(stdoutBuilder.toString(), "Interrupted: " + e.getMessage(), -1);
        }
    }

    /**
     * Loads and caches SSH config from ~/.ssh/config.
     * Called once; subsequent calls use cached values.
     */
    private void loadSshConfigIfNeeded() {
        if (sshConfigLoaded) {
            return;
        }
        sshConfigLoaded = true;

        // Defaults
        effectiveUser = user;
        effectiveHostname = hostname;
        effectivePort = port;

        try {
            String sshConfigPath = System.getProperty("user.home") + "/.ssh/config";
            java.io.File configFile = new java.io.File(sshConfigPath);
            if (configFile.exists()) {
                com.jcraft.jsch.OpenSSHConfig openSSHConfig =
                    com.jcraft.jsch.OpenSSHConfig.parseFile(sshConfigPath);
                cachedConfigRepository = openSSHConfig;

                com.jcraft.jsch.ConfigRepository.Config hostConfig = openSSHConfig.getConfig(hostname);
                if (hostConfig != null) {
                    cachedIdentityFile = hostConfig.getValue("IdentityFile");
                    if (cachedIdentityFile != null && cachedIdentityFile.startsWith("~")) {
                        cachedIdentityFile = System.getProperty("user.home") +
                            cachedIdentityFile.substring(1);
                    }
                    cachedProxyJump = hostConfig.getValue("ProxyJump");

                    String configUser = hostConfig.getUser();
                    if (configUser != null) {
                        effectiveUser = configUser;
                    }
                    String configHostname = hostConfig.getHostname();
                    if (configHostname != null) {
                        effectiveHostname = configHostname;
                    }
                    String configPort = hostConfig.getValue("Port");
                    if (configPort != null) {
                        try {
                            effectivePort = Integer.parseInt(configPort);
                        } catch (NumberFormatException e) {
                            // Keep original port
                        }
                    }
                }
            }
        } catch (Exception e) {
            // If config loading fails, continue without it
        }
    }

    /**
     * Creates a JSch SSH session with configured credentials.
     * Supports ProxyJump for connections through a jump host.
     * SSH config is parsed once and cached for reuse.
     */
    private Session createSession() throws JSchException, IOException {
        JSch jsch = new JSch();

        // Load SSH config (cached after first call)
        loadSshConfigIfNeeded();

        // Load known_hosts for host key verification
        String knownHostsPath = System.getProperty("user.home") + "/.ssh/known_hosts";
        java.io.File knownHostsFile = new java.io.File(knownHostsPath);
        if (knownHostsFile.exists()) {
            jsch.setKnownHosts(knownHostsPath);
        }

        // Setup authentication
        setupAuthentication(jsch, cachedIdentityFile);

        Session session;

        // Handle ProxyJump if configured
        if (cachedProxyJump != null && !cachedProxyJump.isEmpty()) {
            session = createSessionViaProxyJump(jsch, cachedProxyJump, effectiveUser, effectiveHostname, effectivePort);
        } else {
            session = jsch.getSession(effectiveUser, effectiveHostname, effectivePort);
        }

        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        // Use "ask" mode: accept if known_hosts exists, reject unknown hosts
        // Users should populate known_hosts via: ssh-keyscan <host> >> ~/.ssh/known_hosts
        if (knownHostsFile.exists()) {
            session.setConfig("StrictHostKeyChecking", "yes");
        } else {
            // No known_hosts file — fall back to accept-new (add on first connect)
            session.setConfig("StrictHostKeyChecking", "accept-new");
        }

        return session;
    }

    /**
     * Sets up authentication for JSch.
     */
    private void setupAuthentication(JSch jsch, String identityFileFromConfig) throws IOException, JSchException {
        if (password != null && !password.isEmpty()) {
            return;
        }

        boolean authConfigured = false;

        // Priority 1: Try ssh-agent first
        try {
            com.jcraft.jsch.IdentityRepository repo =
                new com.jcraft.jsch.AgentIdentityRepository(new com.jcraft.jsch.SSHAgentConnector());
            jsch.setIdentityRepository(repo);
            authConfigured = true;
        } catch (Exception e) {
            // ssh-agent not available
        }

        // Priority 2: Use IdentityFile from ~/.ssh/config
        if (!authConfigured && identityFileFromConfig != null) {
            java.io.File keyFile = new java.io.File(identityFileFromConfig);
            if (keyFile.exists() && keyFile.canRead()) {
                try {
                    jsch.addIdentity(identityFileFromConfig);
                    authConfigured = true;
                } catch (JSchException ex) {
                    // Key file may require passphrase or be unsupported type
                }
            }
        }

        // Priority 3: Fallback to default key files
        if (!authConfigured) {
            String home = System.getProperty("user.home");
            String[] keyFiles = {
                home + "/.ssh/id_ed25519",
                home + "/.ssh/id_rsa",
                home + "/.ssh/id_ecdsa"
            };

            for (String keyFile : keyFiles) {
                java.io.File f = new java.io.File(keyFile);
                if (f.exists() && f.canRead()) {
                    try {
                        jsch.addIdentity(keyFile);
                        authConfigured = true;
                        break;
                    } catch (JSchException ex) {
                        // Key file may require passphrase, try next
                    }
                }
            }

            if (!authConfigured) {
                throw new IOException("SSH authentication failed: No usable authentication method found.\n" +
                    "\n" +
                    "[~/.ssh/id_ed25519 or ~/.ssh/id_rsa]\n" +
                    "  ssh-add || { eval \"$(ssh-agent -s)\" && ssh-add; }\n" +
                    "\n" +
                    "[Custom key, e.g. ~/.ssh/mykey]\n" +
                    "  ssh-add ~/.ssh/mykey || { eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/mykey; }\n" +
                    "\n" +
                    "[Password authentication]\n" +
                    "  Use --ask-pass option");
            }
        }
    }

    /**
     * Creates a session through a jump host using ProxyJump.
     */
    private Session createSessionViaProxyJump(JSch jsch, String proxyJump,
            String targetUser, String targetHost, int targetPort) throws JSchException, IOException {

        String jumpUser;
        String jumpHost;
        int jumpPort = 22;

        String[] atParts = proxyJump.split("@", 2);
        if (atParts.length == 2) {
            jumpUser = atParts[0];
            String hostPart = atParts[1];
            if (hostPart.contains(":")) {
                String[] hostPortParts = hostPart.split(":", 2);
                jumpHost = hostPortParts[0];
                try {
                    jumpPort = Integer.parseInt(hostPortParts[1]);
                } catch (NumberFormatException e) {
                    jumpHost = hostPart;
                }
            } else {
                jumpHost = hostPart;
            }
        } else {
            jumpUser = user;
            String hostPart = proxyJump;
            if (hostPart.contains(":")) {
                String[] hostPortParts = hostPart.split(":", 2);
                jumpHost = hostPortParts[0];
                try {
                    jumpPort = Integer.parseInt(hostPortParts[1]);
                } catch (NumberFormatException e) {
                    jumpHost = hostPart;
                }
            } else {
                jumpHost = hostPart;
            }
        }

        jumpHostSession = jsch.getSession(jumpUser, jumpHost, jumpPort);
        // Use same host key checking policy as main session
        String knownHostsPath = System.getProperty("user.home") + "/.ssh/known_hosts";
        if (new java.io.File(knownHostsPath).exists()) {
            jumpHostSession.setConfig("StrictHostKeyChecking", "yes");
        } else {
            jumpHostSession.setConfig("StrictHostKeyChecking", "accept-new");
        }
        if (password != null && !password.isEmpty()) {
            jumpHostSession.setPassword(password);
        }
        jumpHostSession.connect();

        int localPort = jumpHostSession.setPortForwardingL(0, targetHost, targetPort);

        Session targetSession = jsch.getSession(targetUser, "127.0.0.1", localPort);

        return targetSession;
    }

    /**
     * Cleans up resources used by this Node.
     * Disconnects the cached SSH session and any jump host session.
     */
    public synchronized void cleanup() {
        if (cachedSession != null && cachedSession.isConnected()) {
            cachedSession.disconnect();
            cachedSession = null;
        }
        if (jumpHostSession != null && jumpHostSession.isConnected()) {
            jumpHostSession.disconnect();
            jumpHostSession = null;
        }
    }

    /**
     * Gets the hostname or IP address of this node.
     *
     * @return the hostname
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Gets the SSH username.
     *
     * @return the username
     */
    public String getUser() {
        return user;
    }

    /**
     * Gets the SSH port number.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns a string representation of this node including hostname, user, and port.
     *
     * @return a formatted string describing this node
     */
    @Override
    public String toString() {
        return String.format("Node{hostname='%s', user='%s', port=%d}",
            hostname, user, port);
    }

    /**
     * Callback interface for real-time output streaming during command execution.
     *
     * <p>Implementations receive stdout and stderr lines as they are produced,
     * enabling real-time display or logging of command output.</p>
     */
    public interface OutputCallback {

        /**
         * Called when a line is read from standard output.
         *
         * @param line the stdout line (without trailing newline)
         */
        void onStdout(String line);

        /**
         * Called when a line is read from standard error.
         *
         * @param line the stderr line (without trailing newline)
         */
        void onStderr(String line);
    }

    /**
     * Represents the result of a command execution, containing stdout, stderr, and exit code.
     *
     * <p>Instances are immutable. Use {@link #isSuccess()} to check if the command
     * completed with exit code 0.</p>
     */
    public static class CommandResult {

        /** The standard output captured from the command. */
        private final String stdout;

        /** The standard error captured from the command. */
        private final String stderr;

        /** The exit code returned by the command (0 typically indicates success). */
        private final int exitCode;

        /**
         * Constructs a CommandResult with the given output and exit code.
         *
         * @param stdout the standard output from the command
         * @param stderr the standard error from the command
         * @param exitCode the exit code (0 for success)
         */
        public CommandResult(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        /**
         * Gets the standard output from the command.
         *
         * @return the stdout string
         */
        public String getStdout() {
            return stdout;
        }

        /**
         * Gets the standard error from the command.
         *
         * @return the stderr string
         */
        public String getStderr() {
            return stderr;
        }

        /**
         * Gets the exit code from the command.
         *
         * @return the exit code (0 typically indicates success)
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * Checks whether the command completed successfully (exit code 0).
         *
         * @return {@code true} if the exit code is 0, {@code false} otherwise
         */
        public boolean isSuccess() {
            return exitCode == 0;
        }

        /**
         * Returns a string representation of this command result.
         *
         * @return a formatted string with exit code, stdout, and stderr
         */
        @Override
        public String toString() {
            return String.format("CommandResult{exitCode=%d, stdout='%s', stderr='%s'}",
                exitCode, stdout, stderr);
        }
    }
}
