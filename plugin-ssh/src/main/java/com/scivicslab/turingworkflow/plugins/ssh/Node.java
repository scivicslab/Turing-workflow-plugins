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
 * Executes commands on a remote or local machine via SSH or a local shell.
 *
 * <ul>
 * <li>{@code executeCommand(String command, OutputCallback callback)} runs a command and returns a {@link
 * CommandResult} containing the stdout, stderr, and exit code, optionally streaming lines to the callback as they are
 * produced.</li>
 * <li>{@code executeSudoCommand(String command, OutputCallback callback)} runs a command with sudo privileges by
 * writing the password from the {@code SUDO_PASSWORD} environment variable to the command's stdin and returns a {@link
 * CommandResult}.</li>
 * <li>{@code cleanup()} disconnects the cached SSH session and any jump host sessions.</li>
 * </ul>
 *
 * <p>The class maintains a cached SSH session and a jump host session to allow multiple commands to be executed over
 * the same TCP connection.</p>
 *
 * <p>One instance may serve several threads at once, as the session creation is synchronized and the underlying SSH
 * channels are created per command.</p>
 *
 * <pre>{@code
 * Node node = new Node("192.168.1.10", "admin");
 * Node.CommandResult result = node.executeCommand("ls -la");
 * node.cleanup();
 * }</pre>
 *
 * <p>If authentication fails or the host is unreachable, the methods throw an {@link IOException}. If a command times
 * out during local execution, it returns a {@link CommandResult} with an exit code of -1 and a message indicating the
 * timeout.</p>
 *
 * <p>Time limits on waits for another machine or process, read from system properties when they are used:</p>
 * <ul>
 * <li>{@value #CONNECT_TIMEOUT_PROPERTY} (default {@value #DEFAULT_CONNECT_TIMEOUT_SECONDS}): seconds the SSH
 * connect to the node, and to a {@code ProxyJump} host, may take; a server that accepts the connection and never
 * answers fails the command with an {@link IOException}.</li>
 * <li>{@value #SERVER_ALIVE_INTERVAL_PROPERTY} (default {@value #DEFAULT_SERVER_ALIVE_INTERVAL_SECONDS}) and
 * {@value #SERVER_ALIVE_COUNT_MAX_PROPERTY} (default {@value #DEFAULT_SERVER_ALIVE_COUNT_MAX}): an open session sends
 * a keepalive after that many silent seconds and is dropped after that many unanswered keepalives, so a remote command
 * whose peer died fails with an {@link IOException} instead of waiting for output forever.</li>
 * <li>{@value #LOCAL_COMMAND_TIMEOUT_PROPERTY} (default {@value #DEFAULT_LOCAL_COMMAND_TIMEOUT_SECONDS}): seconds a
 * local command, with or without sudo, may run before it and the processes it started are killed.</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.0.0
 */
public class Node {

    /** System property: seconds the SSH connect may take. */
    public static final String CONNECT_TIMEOUT_PROPERTY = "turingworkflow.ssh.connectTimeoutSeconds";

    /** System property: seconds of silence after which an open SSH session sends a keepalive. */
    public static final String SERVER_ALIVE_INTERVAL_PROPERTY = "turingworkflow.ssh.serverAliveIntervalSeconds";

    /** System property: unanswered keepalives after which an SSH session is dropped. */
    public static final String SERVER_ALIVE_COUNT_MAX_PROPERTY = "turingworkflow.ssh.serverAliveCountMax";

    /** System property: seconds a local command may run. */
    public static final String LOCAL_COMMAND_TIMEOUT_PROPERTY = "turingworkflow.ssh.localCommandTimeoutSeconds";

    /** Default of {@value #CONNECT_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;

    /** Default of {@value #SERVER_ALIVE_INTERVAL_PROPERTY}. */
    public static final long DEFAULT_SERVER_ALIVE_INTERVAL_SECONDS = 30;

    /** Default of {@value #SERVER_ALIVE_COUNT_MAX_PROPERTY}. */
    public static final long DEFAULT_SERVER_ALIVE_COUNT_MAX = 3;

    /** Default of {@value #LOCAL_COMMAND_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_LOCAL_COMMAND_TIMEOUT_SECONDS = 300;

    /** The hostname or IP address of the remote node. */
    private final String hostname;

    /** The SSH username used for authentication. */
    private final String user;

    /** The SSH port number. */
    private final int port;

    /** Indicates whether commands are executed on the local machine instead of via SSH. */
    private final boolean localMode;

    /** The SSH password, or {@code null} to use key-based authentication. */
    private final String password;

    /** The cached SSH session used to avoid re-authenticating for every command execution. */
    private Session cachedSession = null;

    /** The SSH session for a jump host, kept open for the duration of a proxied connection. */
    private Session jumpHostSession = null;

    /** Indicates whether the {@code ~/.ssh/config} file has been parsed and its settings are cached. */
    private boolean sshConfigLoaded = false;

    /** The parsed contents of {@code ~/.ssh/config} used to resolve connection settings. */
    private com.jcraft.jsch.ConfigRepository cachedConfigRepository = null;

    /**
     * The path to the SSH identity file specified by the {@code IdentityFile} directive in the {@code ~/.ssh/config}
     * file.
     */
    private String cachedIdentityFile = null;

    /**
     * The {@code ProxyJump} directive parsed from the SSH configuration, used to establish a connection through a jump
     * host.
     */
    private String cachedProxyJump = null;

    /** The username used for the SSH connection after applying overrides from {@code ~/.ssh/config}. */
    private String effectiveUser;

    /** The hostname or IP address used for the connection after applying overrides from {@code ~/.ssh/config}. */
    private String effectiveHostname;

    /** The port number used for the connection after applying overrides from {@code ~/.ssh/config}. */
    private int effectivePort;

    /**
     * Initializes a {@code Node} for a specific host and user using the standard SSH port 22.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     */
    public Node(String hostname, String user, int port) {
        this(hostname, user, port, false, null);
    }

    /**
     * Initializes a {@code Node} with the specified connection parameters and determines whether to execute commands
     * via SSH or locally.
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
     * Initializes a Node with specific connection parameters, including an optional password for authentication.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port number
     * @param localMode if true, executes commands locally instead of via SSH
     * @param password the SSH password, or {@code null} to use key-based authentication via ssh-agent or identity
     * files
     */
    public Node(String hostname, String user, int port, boolean localMode, String password) {
        this.hostname = hostname;
        this.user = user;
        this.port = port;
        this.localMode = localMode;
        this.password = password;
    }

    /**
     * Initializes a Node using the specified hostname and user, with the default SSH port 22 and no password.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     */
    public Node(String hostname, String user) {
        this(hostname, user, 22, false, null);
    }

    /** Returns whether this node is configured to execute commands on the local machine instead of via SSH. */
    public boolean isLocalMode() {
        return localMode;
    }

    /**
     * Executes a command on the node.
     *
     * Returns a {@link CommandResult} containing the captured stdout, stderr, and the exit code of the command.
     *
     * @param command the command string to execute
     * @return a {@link CommandResult} containing the stdout, stderr, and exit code
     * @throws IOException if the command execution fails or the SSH connection cannot be established
     */
    public CommandResult executeCommand(String command) throws IOException {
        return executeCommand(command, null);
    }

    /**
     * Executes a command on the node, optionally streaming output to a callback in real-time.
     *
     * Returns a {@link CommandResult} containing the trimmed stdout, trimmed stderr, and the exit code of the command.
     *
     * If a {@code callback} is provided, its {@link OutputCallback#onStdout(String)} and {@link
     * OutputCallback#onStderr(String)} methods are called for every line produced during execution.
     *
     * Throws an {@link IOException} if the command fails to execute, the SSH connection fails, or the execution is
     * interrupted.
     *
     * @param command the command string to execute
     * @param callback a listener for real-time output lines, or {@code null} to only capture the final result
     */
    public CommandResult executeCommand(String command, OutputCallback callback) throws IOException {
        if (localMode) {
            return executeLocalCommand(command, callback);
        }
        return executeRemoteCommand(command, callback);
    }

    /**
     * Runs a command on the local machine via a shell and returns the captured output and exit code.
     *
     * @param command the command string to execute
     * @param callback a listener that receives lines of stdout and stderr as they are produced, or {@code null} to
     * only capture the final result
     *
     * @return a {@link CommandResult} containing the trimmed stdout, trimmed stderr, and the process exit code
     * @throws IOException if the process fails to start or the execution is interrupted
     */
    private CommandResult executeLocalCommand(String command, OutputCallback callback) throws IOException {
        Process process = new ProcessBuilder("bash", "-c", command).start();
        try {
            return resultOfLocal(command, process, callback);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        }
    }

    /**
     * Returns the result of a started local process, which may run for {@value #LOCAL_COMMAND_TIMEOUT_PROPERTY}
     * seconds; past that it is killed and the result has exit code -1 and a stderr naming the command and the limit.
     */
    private static CommandResult resultOfLocal(String command, Process process, OutputCallback callback)
            throws InterruptedException {
        long limit = SystemPropertyLimit.read(LOCAL_COMMAND_TIMEOUT_PROPERTY, DEFAULT_LOCAL_COMMAND_TIMEOUT_SECONDS);
        ProcessWithLimit.Outcome outcome = ProcessWithLimit.waitFor(process, limit,
                callback == null ? null : callback::onStdout,
                callback == null ? null : callback::onStderr);
        if (!outcome.finished()) {
            return new CommandResult(outcome.stdout(),
                    "local command did not finish within " + limit + " s and was killed (" + LOCAL_COMMAND_TIMEOUT_PROPERTY
                            + "): " + command, -1);
        }
        return new CommandResult(outcome.stdout(), outcome.stderr(), outcome.exitCode());
    }

    /**
     * Returns a connected SSH session that is reused across multiple command executions.
     *
     * <p>If the existing cached session is still connected, it returns that session. Otherwise, it disconnects any
     * existing jump host session, creates a new session, and connects it.</p>
     *
     * <p>One instance of {@code Node} may serve several threads at once as this method is synchronized.</p>
     *
     * @return the connected {@link Session} to be used for command execution
     * @throws IOException if a connection error occurs or the session cannot be established
     * @throws JSchException if the underlying SSH protocol fails
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
        cachedSession = null;
        Session session = createSession();
        connectWithinLimit(session, effectiveHostname, effectivePort);
        cachedSession = session;
        return cachedSession;
    }

    /**
     * Connects the session within {@value #CONNECT_TIMEOUT_PROPERTY} seconds and sets its keepalive.
     *
     * @param session the session to connect
     * @param host the host named in the failure message
     * @param port the port named in the failure message
     * @throws IOException if the connect does not complete within the limit
     * @throws JSchException if the connect fails for another reason
     */
    private static void connectWithinLimit(Session session, String host, int port) throws IOException, JSchException {
        long limit = SystemPropertyLimit.read(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_SECONDS);
        long interval = SystemPropertyLimit.read(SERVER_ALIVE_INTERVAL_PROPERTY, DEFAULT_SERVER_ALIVE_INTERVAL_SECONDS);
        long countMax = SystemPropertyLimit.read(SERVER_ALIVE_COUNT_MAX_PROPERTY, DEFAULT_SERVER_ALIVE_COUNT_MAX);
        session.setServerAliveInterval((int) Math.min(Integer.MAX_VALUE, interval * 1000));
        session.setServerAliveCountMax((int) Math.min(Integer.MAX_VALUE, countMax));
        try {
            session.connect((int) Math.min(Integer.MAX_VALUE, limit * 1000));
        } catch (JSchException e) {
            session.disconnect();
            if (isTimeout(e)) {
                throw new IOException("SSH connection to " + host + ":" + port + " did not complete within " + limit
                        + " s (" + CONNECT_TIMEOUT_PROPERTY + "): " + e.getMessage(), e);
            }
            throw e;
        }
    }

    /** Returns whether the exception, or one of its causes, reports a timeout. */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && (message.contains("timeout") || message.contains("timed out"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the exit status of a closed channel, or throws when the session was lost before the command ended.
     *
     * <p>A dropped session (its keepalives went unanswered) closes its channels, so the output read ends and the
     * channel has no exit status; that is reported as a failure, not as an exit code.</p>
     */
    private int exitStatusOf(ChannelExec channel, Session session) throws IOException {
        int exitCode = channel.getExitStatus();
        if (exitCode == -1 && !session.isConnected()) {
            synchronized (this) {
                if (cachedSession == session) {
                    cachedSession = null;
                }
            }
            throw new IOException("SSH session to " + effectiveHostname + ":" + effectivePort
                    + " was lost before the command finished (no answer to "
                    + SystemPropertyLimit.read(SERVER_ALIVE_COUNT_MAX_PROPERTY, DEFAULT_SERVER_ALIVE_COUNT_MAX)
                    + " keepalives sent every "
                    + SystemPropertyLimit.read(SERVER_ALIVE_INTERVAL_PROPERTY, DEFAULT_SERVER_ALIVE_INTERVAL_SECONDS)
                    + " s)");
        }
        return exitCode;
    }

    /** Waits until the channel is closed, or until its session is gone, which closes it as well. */
    private static void awaitChannelClose(ChannelExec channel, Session session) throws IOException {
        while (!channel.isClosed() && session.isConnected()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Command execution interrupted", e);
            }
        }
    }

    /**
     * Executes a command on a remote node via SSH and returns the result containing the exit code, stdout, and stderr.
     *
     * <p>If a callback is provided, it streams lines of stdout and stderr to the callback as they are produced. The
     * method reuses the existing SSH session to avoid the overhead of establishing a new connection for every
     * call.</p>
     *
     * @param command the command string to execute on the remote host
     * @param callback a listener to receive real-time lines of stdout and stderr, or null to only capture the final
     * output
     *
     * @return a {@link CommandResult} containing the trimmed stdout, trimmed stderr, and the integer exit code
     * @throws IOException if the SSH connection fails, the command execution is interrupted, or an authentication
     * error occurs
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

            // Wait for stderr thread; both streams end when the channel closes or the session is dropped
            stderrThread.join();

            awaitChannelClose(channel, session);
            int exitCode = exitStatusOf(channel, session);

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

    /** The name of the environment variable used to retrieve the password for {@code sudo} commands. */
    private static final String SUDO_PASSWORD_ENV = "SUDO_PASSWORD";

    /**
     * Executes a command with sudo privileges on the node.
     *
     * @param command the command to execute
     * @return a {@link CommandResult} containing the stdout, stderr, and exit code of the command
     * @throws IOException if the SSH connection fails or the {@code SUDO_PASSWORD} environment variable is not set
     */
    public CommandResult executeSudoCommand(String command) throws IOException {
        return executeSudoCommand(command, null);
    }

    /**
     * Executes a command with sudo privileges on the node, using the password from the {@code SUDO_PASSWORD}
     * environment variable.
     *
     * Returns a {@link CommandResult} containing the captured stdout, stderr, and the exit code of the command.
     *
     * If the {@code SUDO_PASSWORD} environment variable is not set or is empty, an {@code IOException} is thrown.
     *
     * An {@code IOException} is thrown if the SSH connection fails or if the command execution is interrupted.
     *
     * @param command the command string to execute with sudo privileges
     * @param callback a callback to receive stdout and stderr lines in real-time, or {@code null} to only capture the
     * final output
     *
     * @return a {@link CommandResult} containing the command's output and exit status
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
     * Executes a command with sudo privileges on a remote node by writing the password to the channel's stdin.
     *
     * @param command the command to execute
     * @param sudoPassword the password to be sent to the sudo prompt
     * @param callback the callback for real-time output (may be null)
     * @return a {@link CommandResult} containing the stdout, stderr, and exit code
     * @throws IOException if the SSH connection fails or the command execution is interrupted
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

            awaitChannelClose(channel, session);
            int exitCode = exitStatusOf(channel, session);
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
     * Executes a command with sudo privileges on the local machine.
     *
     * @param command the command to execute
     * @param sudoPassword the password to provide to the sudo prompt
     * @param callback the callback for real-time output (may be null)
     * @return a CommandResult containing the stdout, stderr, and exit code
     * @throws IOException if the command execution fails or the process is interrupted
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

        try {
            return resultOfLocal(command, process, callback);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult("", "Interrupted: " + e.getMessage(), -1);
        }
    }

    /**
     * Updates the connection parameters using settings from the local SSH configuration file. Subsequent calls to this
     * method have no effect.
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
     * Establishes a new SSH connection to the node using the configured credentials and connection settings.
     *
     * <p>The connection may be established through a jump host if a {@code ProxyJump} directive is present in the
     * {@code ~/.ssh/config} file. Host key verification follows the existing {@code ~/.ssh/known_hosts} file if
     * present, otherwise it accepts new host keys.</p>
     *
     * @return a {@link com.jcraft.jsch.Session} representing the established connection
     * @throws com.jcraft.jsch.JSchException if the connection or authentication fails
     * @throws java.io.IOException if an I/O error occurs during connection setup
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
     * Configures the authentication methods for the SSH connection by selecting a credential from a prioritized list.
     *
     * <p>The method attempts to authenticate using the following order:
     * <ul>
     * <li>An active {@code ssh-agent} via {@code SSH_AUTH_SOCK}.</li>
     * <li>The identity file specified by the {@code IdentityFile} directive in {@code ~/.ssh/config}.</li>
     * <li>Default key files located in {@code ~/.ssh/}, specifically {@code id_ed25519}, {@code id_rsa}, or {@code
     * id_ecdsa}.</li>
     * </ul>
     * If a password was provided during {@link Node} construction, this method does nothing and relies on password
     * authentication.</p>
     *
     * @param jsch the JSch instance to configure
     * @param identityFileFromConfig the path to the identity file parsed from the SSH configuration
     * @throws IOException if no usable authentication method is found or if the identity file cannot be read
     * @throws JSchException if an error occurs during the authentication setup process
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
     * Establishes an SSH connection to a target host by routing through a jump host.
     *
     * @param jsch the JSch instance to use for session creation
     * @param proxyJump the jump host specification, optionally including a username and port (e.g., "user@host:port"
     * or "host:port")
     *
     * @param targetUser the username for the final target host
     * @param targetHost the hostname or IP address of the target host
     * @param targetPort the port number of the target host
     * @return a Session object connected to the target host via a local port forward through the jump host
     * @throws JSchException if an error occurs during SSH session creation or port forwarding
     * @throws IOException if an error occurs during connection or if the jump host session cannot be established
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
        connectWithinLimit(jumpHostSession, jumpHost, jumpPort);

        int localPort = jumpHostSession.setPortForwardingL(0, targetHost, targetPort);

        Session targetSession = jsch.getSession(targetUser, "127.0.0.1", localPort);

        return targetSession;
    }

    /** Disconnects the cached SSH session and any jump host sessions used by this node. */
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
     * Returns the hostname or IP address of this node.
     *
     * @return the hostname or IP address
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Returns the SSH username used for authentication.
     *
     * @return the username
     */
    public String getUser() {
        return user;
    }

    /**
     * Returns the SSH port number used for the connection.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns a string that describes the node's connection parameters.
     *
     * The result is a formatted string in the form {@code Node{hostname='...', user='...', port=...}}.
     */
    @Override
    public String toString() {
        return String.format("Node{hostname='%s', user='%s', port=%d}",
            hostname, user, port);
    }

    /**
     * Receives lines of standard output and standard error as they are produced during a command's execution.
     *
     * <p>Implementations are called by {@link Node#executeCommand(String, OutputCallback)} to allow real-time
     * processing of command output.
     *
     * <ul>
     * <li>{@link #onStdout(String)} receives a line of text from the command's standard output.</li>
     * <li>{@link #onStderr(String)} receives a line of text from the command's standard error.</li>
     * </ul>
     *
     * <p>Each line passed to the methods does not include a trailing newline character.
     */
    public interface OutputCallback {

        /**
         * Receives a single line of text from the command's standard output.
         *
         * @param line the text of the line without a trailing newline
         */
        void onStdout(String line);

        /**
         * Receives a single line of text from the standard error stream.
         *
         * @param line the text of the error line without a trailing newline
         */
        void onStderr(String line);
    }

    /**
     * Holds the captured output and exit status of a command execution.
     *
     * <ul><li>{@link #getStdout()} returns the standard output as a string.</li><li>{@link #getStderr()} returns the
     * standard error as a string.</li><li>{@link #getExitCode()} returns the integer exit code.</li><li>{@link
     * #isSuccess()} returns true if the exit code is 0, and false otherwise.</li></ul>
     */
    public static class CommandResult {

        /** The standard output captured from the command. */
        private final String stdout;

        /** The standard error captured from the command. */
        private final String stderr;

        /** The exit code returned by the command execution. */
        private final int exitCode;

        /**
         * Creates a result containing the captured output and exit status of a command execution.
         *
         * @param stdout the standard output captured from the command
         * @param stderr the standard error captured from the command
         * @param exitCode the exit code returned by the command (0 typically indicates success)
         */
        public CommandResult(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        /**
         * Returns the standard output captured from the command execution.
         *
         * @return the stdout string
         */
        public String getStdout() {
            return stdout;
        }

        /**
         * Returns the standard error output captured from the command execution.
         *
         * @return the stderr string
         */
        public String getStderr() {
            return stderr;
        }

        /**
         * Returns the exit code of the command.
         *
         * @return the integer exit code, where 0 typically indicates success
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * Returns whether the command completed with an exit code of 0.
         *
         * @return {@code true} if the exit code is 0, {@code false} otherwise
         */
        public boolean isSuccess() {
            return exitCode == 0;
        }

        /**
         * Returns a string that contains the exit code, the standard output, and the standard error of the command
         * execution.
         *
         * @return a formatted string in the form {@code CommandResult{exitCode=..., stdout='...', stderr='...'}}
         */
        @Override
        public String toString() {
            return String.format("CommandResult{exitCode=%d, stdout='%s', stderr='%s'}",
                exitCode, stdout, stderr);
        }
    }
}
