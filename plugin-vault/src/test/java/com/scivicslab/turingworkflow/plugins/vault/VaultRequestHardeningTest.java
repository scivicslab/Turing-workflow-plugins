package com.scivicslab.turingworkflow.plugins.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * What a workflow gives the Vault plugin cannot redirect the token or add options to the command (HIGH 9 and 10,
 * scope set by chat-98).
 *
 * <p>HIGH 9: the token went in a header to any {@code http} address, and the secret path went into the URL as it
 * was, so {@code ..}, {@code ?} and {@code #} reached another endpoint with the token. HIGH 10: the namespace, the
 * pod name and the path became arguments of {@code kubectl exec ... vault kv get}, where a text that begins with
 * {@code -} is an option such as {@code -address=}.</p>
 */
@Tag("VaultRequestHardening_261123_oo01")
@DisplayName("Vault plugin - the token stays on https or loopback, names cannot become URL parts or options")
class VaultRequestHardeningTest {

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().toString());
            byte[] body = "{\"data\":{\"data\":{\"f\":\"v\"}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        System.clearProperty(VaultConfig.ALLOW_PLAIN_HTTP_PROPERTY);
    }

    private VaultClient client() {
        return new VaultClient(new VaultConfig("http://127.0.0.1:" + server.getAddress().getPort(), "token"));
    }

    @Test
    @DisplayName("an ordinary path is read")
    void ordinaryPath_isRead() throws Exception {
        assertThat(client().readField("secret/app", "f")).isEqualTo("v");
        assertThat(requested).containsExactly("/v1/secret/data/app");
    }

    @Test
    @DisplayName("a path with .., ?, #, %, a leading / or an empty segment sends no request")
    void pathThatReachesAnotherEndpoint_sendsNothing() {
        for (String path : List.of("secret/../sys/seal-status", "secret/app?list=true", "secret/app#x",
                "secret/%2e%2e/sys", "/secret/app", "secret//app", "secret/ app")) {
            assertThrows(VaultClient.VaultException.class, () -> client().readField(path, "f"), path);
            assertThrows(VaultClient.VaultException.class, () -> client().readSecret(path), path);
        }
        assertThat(requested).as("requests that reached the server").isEmpty();
    }

    @Test
    @DisplayName("plain http to a host that is not this machine is refused, https and loopback are not")
    void tokenOverPlainHttp_isRefusedExceptOnLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new VaultConfig("http://vault.example.org:8200", "t"));
        assertThrows(IllegalArgumentException.class, () -> new VaultConfig("http://10.0.0.5:8200", "t"));
        assertThrows(IllegalArgumentException.class, () -> new VaultConfig("ftp://vault.example.org", "t"));
        new VaultConfig("https://vault.example.org:8200", "t");
        new VaultConfig("http://127.0.0.1:8200", "t");
        new VaultConfig("http://localhost:8200", "t");
        new VaultConfig("http://[::1]:8200", "t");
    }

    @Test
    @DisplayName("the program can accept plain http by setting the property")
    void plainHttp_canBeAcceptedByName() {
        System.setProperty(VaultConfig.ALLOW_PLAIN_HTTP_PROPERTY, "true");
        new VaultConfig("http://vault.infra.svc:8200", "t");
    }

    @Test
    @DisplayName("kubectl: a path, namespace or pod that begins with - is refused before any command runs")
    void kubectl_optionLookingNames_areRefused() {
        for (KubectlVaultBackend backend : List.of(
                new KubectlVaultBackend("ns", "pod"),
                new KubectlVaultBackend("--kubeconfig=/tmp/evil", "pod"),
                new KubectlVaultBackend("ns", "-n"),
                new KubectlVaultBackend("ns; id", "pod"))) {
            VaultException e = assertThrows(VaultException.class, () -> backend.get("-address=http://evil.example", "f"));
            assertThat(e.getMessage()).startsWith("Refused");
        }
        VaultException e = assertThrows(VaultException.class,
                () -> new KubectlVaultBackend("--kubeconfig=/tmp/evil", "pod").get("secret/app", "f"));
        assertThat(e.getMessage()).startsWith("Refused").contains("namespace");
        e = assertThrows(VaultException.class, () -> new KubectlVaultBackend("ns", "-n").get("secret/app", "f"));
        assertThat(e.getMessage()).startsWith("Refused").contains("pod");
        e = assertThrows(VaultException.class, () -> new KubectlVaultBackend("ns", "pod").get("secret/../x", "f"));
        assertThat(e.getMessage()).startsWith("Refused");
    }
}
