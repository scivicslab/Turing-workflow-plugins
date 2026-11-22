package com.scivicslab.turingworkflow.plugins.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Vault backends do not wait without limit for the Vault server or for {@code kubectl}, and that the
 * kubectl backend keeps stderr out of the secret value and the secret out of failure messages.
 *
 * <p>The kubectl tests run stand-in commands through {@link KubectlVaultBackend#secretPrintedBy}, the code path
 * {@code get} uses after it has built the {@code kubectl exec} command line.</p>
 */
@DisplayName("Vault backends - waits on the server and on kubectl end within their limits")
class VaultTimeLimitTest {

    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        System.clearProperty(VaultClient.REQUEST_TIMEOUT_PROPERTY);
        System.clearProperty(KubectlVaultBackend.TIMEOUT_PROPERTY);
        for (Socket s : accepted) s.close();
    }

    @Test
    void readField_serverNeverAnswers_failsWithinRequestTimeout() throws Exception {
        try (ServerSocket server = serverThat(socket -> { })) {
            System.setProperty(VaultClient.REQUEST_TIMEOUT_PROPERTY, "1");
            VaultClient client = new VaultClient(new VaultConfig("http://127.0.0.1:" + server.getLocalPort(), "t"));

            VaultClient.VaultException failure = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> assertThrows(VaultClient.VaultException.class, () -> client.readField("kv/app", "pw")));

            assertThat(failure.getMessage()).contains("127.0.0.1:" + server.getLocalPort()).contains("within 1 s");
        }
    }

    @Test
    void readField_serverStopsInsideBody_failsWithinRequestTimeout() throws Exception {
        try (ServerSocket server = serverThat(socket -> {
            try {
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"da"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // the client went away
            }
        })) {
            System.setProperty(VaultClient.REQUEST_TIMEOUT_PROPERTY, "1");
            VaultClient client = new VaultClient(new VaultConfig("http://127.0.0.1:" + server.getLocalPort(), "t"));

            VaultClient.VaultException failure = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> assertThrows(VaultClient.VaultException.class, () -> client.readField("kv/app", "pw")));

            assertThat(failure.getMessage()).contains("within 1 s");
        }
    }

    @Test
    void secretPrintedBy_stderrBesideValue_returnsStdoutOnly() throws Exception {
        String value = KubectlVaultBackend.secretPrintedBy(
                List.of("bash", "-c", "echo s3cret; echo 'Defaulted container \"vault\"' >&2"));

        assertThat(value).isEqualTo("s3cret");
    }

    @Test
    void secretPrintedBy_nonZeroExit_failsWithStderrButNotTheSecret() {
        VaultException failure = assertThrows(VaultException.class, () -> KubectlVaultBackend.secretPrintedBy(
                List.of("bash", "-c", "echo s3cret; echo 'pods \"vault-0\" not found' >&2; exit 2")));

        assertThat(failure.getMessage()).contains("exit 2").contains("pods \"vault-0\" not found")
                .doesNotContain("s3cret");
    }

    @Test
    void secretPrintedBy_commandOutlivesLimit_failsAndKillsIt() {
        System.setProperty(KubectlVaultBackend.TIMEOUT_PROPERTY, "1");

        VaultException failure = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> assertThrows(VaultException.class,
                        () -> KubectlVaultBackend.secretPrintedBy(List.of("bash", "-c", "sleep 60; echo late"))));

        assertThat(failure.getMessage()).contains("within 1 s");
    }

    private ServerSocket serverThat(Consumer<Socket> onAccept) throws IOException {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Socket socket = server.accept();
                    accepted.add(socket);
                    onAccept.accept(socket);
                }
            } catch (IOException e) {
                // the server socket was closed at the end of the test
            }
        });
        return server;
    }
}
