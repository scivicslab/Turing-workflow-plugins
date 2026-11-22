package com.scivicslab.turingworkflow.plugins.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/**
 * A host whose key is not known is refused unless the program says to accept it (chat-86, HIGH 8).
 *
 * <p>With no {@code ~/.ssh/known_hosts}, the node set {@code StrictHostKeyChecking} to {@code accept-new} and kept no
 * record of the key, so every key from every host was accepted on every connection: anyone on the path could answer
 * as the host and receive the password or the commands. Each case here connects to a real SSH server (Apache MINA
 * SSHD, with a password check that accepts any password) whose key is in no known_hosts.</p>
 */
@Tag("SshHostKeyChecking_261123_oo01")
@DisplayName("Node - a host key that is not in known_hosts is refused")
@Timeout(120)
class NodeHostKeyTest {

    @TempDir
    Path home;

    private SshServer server;
    private String savedHome;

    @BeforeEach
    void startServer() throws Exception {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(home.resolve("server-host-key")));
        server.setPasswordAuthenticator((user, password, session) -> true);
        // a command that does nothing and exits 0, so that a connection that is let through ends at once
        server.setCommandFactory((channel, command) -> new org.apache.sshd.server.command.AbstractCommandSupport(command, null) {
            @Override
            public void run() {
                onExit(0);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        System.clearProperty(Node.ACCEPT_UNKNOWN_HOST_KEYS_PROPERTY);
        System.setProperty("user.home", savedHome);
        server.stop(true);
    }

    private Node node() {
        return new Node("127.0.0.1", "someone", server.getPort(), false, "any-password");
    }

    @Test
    @DisplayName("with no known_hosts file, the connection is refused")
    void noKnownHostsFile_isRefused() {
        assertThatThrownBy(() -> node().executeCommand("true")).hasStackTraceContaining("reject HostKey");
    }

    @Test
    @DisplayName("with a known_hosts file that does not list the host, the connection is refused")
    void aKnownHostsFileWithoutTheHost_isRefused() throws Exception {
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/known_hosts"),
                "other.example ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\n");

        assertThatThrownBy(() -> node().executeCommand("true")).hasStackTraceContaining("reject HostKey");
    }

    @Test
    @DisplayName("naming the property that accepts unknown host keys lets the connection through")
    void theExplicitProperty_acceptsAnUnknownKey() {
        System.setProperty(Node.ACCEPT_UNKNOWN_HOST_KEYS_PROPERTY, "true");

        try {
            node().executeCommand("true");
        } catch (Exception e) {
            // the server has no command to run; only the host key must not be what failed
            assertThat(stackTrace(e)).doesNotContain("reject HostKey");
        }
    }

    @Test
    @DisplayName("the policy is strict by default, and accepting unknown keys is logged as a warning")
    void thePolicy() throws Exception {
        Session strict = new JSch().getSession("u", "h", 22);
        Node.applyHostKeyPolicy(strict);
        assertThat(strict.getConfig("StrictHostKeyChecking")).isEqualTo("yes");

        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger log = Logger.getLogger(Node.class.getName());
        log.addHandler(capture);
        try {
            System.setProperty(Node.ACCEPT_UNKNOWN_HOST_KEYS_PROPERTY, "true");
            Session lax = new JSch().getSession("u", "h", 22);
            Node.applyHostKeyPolicy(lax);
            assertThat(lax.getConfig("StrictHostKeyChecking")).isEqualTo("no");
            assertThat(records).anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("man-in-the-middle"));
        } finally {
            log.removeHandler(capture);
        }
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter out = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
