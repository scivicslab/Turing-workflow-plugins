package com.scivicslab.turingworkflow.plugins.ssh;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for Node (SSH execution). Requires a reachable SSH host.
 *
 * Environment variables:
 *   E2E_SSH_HOST  — hostname or IP  (default: 192.168.5.13)
 *   E2E_SSH_USER  — SSH username    (default: devteam)
 *
 * Run with: mvn test -pl plugin-ssh -Dgroups=e2e
 */
@Tag("e2e")
class NodeE2eTest {

    private static final String HOST = System.getenv().getOrDefault("E2E_SSH_HOST", "192.168.5.13");
    private static final String USER = System.getenv().getOrDefault("E2E_SSH_USER", "devteam");

    private static Node node;

    @BeforeAll
    static void connect() {
        System.out.println("[E2E] SSH target: " + USER + "@" + HOST);
        node = new Node(HOST, USER);
    }

    @AfterAll
    static void disconnect() {
        if (node != null) node.cleanup();
    }

    @Test
    void executeCommand_echoOk() throws Exception {
        Node.CommandResult result = node.executeCommand("echo ok");
        System.out.println("[E2E] stdout: " + result.getStdout());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout().trim()).isEqualTo("ok");
    }

    @Test
    void executeCommand_hostname_returnsNonBlank() throws Exception {
        Node.CommandResult result = node.executeCommand("hostname");
        System.out.println("[E2E] hostname: " + result.getStdout());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout().trim()).isNotBlank();
    }

    @Test
    void executeCommand_failingCommand_returnsNonZeroExitCode() throws Exception {
        Node.CommandResult result = node.executeCommand("false");
        System.out.println("[E2E] exit code: " + result.getExitCode());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExitCode()).isNotEqualTo(0);
    }

    @Test
    void executeCommand_sessionReuse_secondCallSucceeds() throws Exception {
        node.executeCommand("echo first");
        Node.CommandResult result = node.executeCommand("echo second");
        assertThat(result.getStdout().trim()).isEqualTo("second");
    }

    @Test
    void executeCommand_multilineOutput_preservedInStdout() throws Exception {
        Node.CommandResult result = node.executeCommand("printf 'line1\\nline2\\nline3'");
        System.out.println("[E2E] multiline:\n" + result.getStdout());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStdout()).contains("line1");
        assertThat(result.getStdout()).contains("line3");
    }
}
