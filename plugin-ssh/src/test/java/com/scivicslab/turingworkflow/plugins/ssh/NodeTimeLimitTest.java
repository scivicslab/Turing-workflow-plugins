package com.scivicslab.turingworkflow.plugins.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link Node} does not wait without limit for another machine or process: an SSH server that accepts
 * the TCP connection and never sends its version line, and a local command that does not finish.
 */
@DisplayName("Node - waits on another machine or process end within their limits")
class NodeTimeLimitTest {

    @AfterEach
    void clearLimits() {
        System.clearProperty(Node.CONNECT_TIMEOUT_PROPERTY);
        System.clearProperty(Node.LOCAL_COMMAND_TIMEOUT_PROPERTY);
    }

    @Test
    void executeCommand_serverAcceptsAndSendsNothing_failsWithinConnectTimeout() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    accepted.add(server.accept());
                } catch (IOException e) {
                    // the server socket was closed at the end of the test
                }
            });
            System.setProperty(Node.CONNECT_TIMEOUT_PROPERTY, "1");
            // a password skips the key lookup, so the connect itself is what is tested
            Node node = new Node("127.0.0.1", "nobody", server.getLocalPort(), false, "unused");

            IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> assertThrows(IOException.class, () -> node.executeCommand("true")));

            assertThat(failure.getMessage()).contains("127.0.0.1:" + server.getLocalPort()).contains("within 1 s");
        } finally {
            for (Socket s : accepted) s.close();
        }
    }

    @Test
    void executeCommand_localCommandOutlivesLimit_failsAndKillsIt() throws Exception {
        System.setProperty(Node.LOCAL_COMMAND_TIMEOUT_PROPERTY, "1");
        Node node = new Node("localhost", "nobody", 22, true);

        // "; echo late" keeps bash as the parent of sleep, so the kill must reach the child too
        Node.CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> node.executeCommand("sleep 60; echo late"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExitCode()).isEqualTo(-1);
        assertThat(result.getStderr()).contains("within 1 s");
        assertThat(result.getStdout()).doesNotContain("late");
    }

    @Test
    void executeCommand_localCommandWithinLimit_returnsOutputAndExitCode() throws Exception {
        Node node = new Node("localhost", "nobody", 22, true);
        StringBuilder streamed = new StringBuilder();

        Node.CommandResult result = node.executeCommand("echo out; echo err >&2; exit 3", new Node.OutputCallback() {
            @Override public void onStdout(String line) { synchronized (streamed) { streamed.append("o:").append(line); } }
            @Override public void onStderr(String line) { synchronized (streamed) { streamed.append("e:").append(line); } }
        });

        assertThat(result.getStdout()).isEqualTo("out");
        assertThat(result.getStderr()).isEqualTo("err");
        assertThat(result.getExitCode()).isEqualTo(3);
        assertThat(streamed.toString()).contains("o:out").contains("e:err");
    }
}
