package com.scivicslab.turingworkflow.plugins.vault;

import com.scivicslab.pojoactor.core.ActionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2E tests for VaultActor. Supports two modes:
 *
 * HTTP mode (requires VAULT_ADDR + VAULT_TOKEN):
 *   mvn test -pl plugin-vault -Dgroups=e2e
 *
 * kubectl mode (requires E2E_VAULT_K8S_NAMESPACE + optional E2E_VAULT_K8S_POD, E2E_VAULT_K8S_MOUNT):
 *   E2E_VAULT_K8S_NAMESPACE=infra-vault E2E_VAULT_K8S_POD=vault-0 E2E_VAULT_K8S_MOUNT=devtools \
 *     mvn test -pl plugin-vault -Dgroups=e2e
 */
@Tag("e2e")
class VaultActorE2eTest {

    private static final String VAULT_ADDR  = System.getenv("VAULT_ADDR");
    private static final String VAULT_TOKEN = System.getenv("VAULT_TOKEN");

    private static final String K8S_NAMESPACE = System.getenv("E2E_VAULT_K8S_NAMESPACE");
    private static final String K8S_POD       = System.getenv().getOrDefault("E2E_VAULT_K8S_POD", "vault-0");

    private static final String HTTP_MOUNT      = System.getenv().getOrDefault("E2E_VAULT_HTTP_MOUNT", "secret");
    private static final String HTTP_TEST_PATH  = HTTP_MOUNT + "/e2e-vault-actor";
    private static final String HTTP_TEST_FIELD = "e2e-field";
    private static final String HTTP_TEST_VALUE = "hello-from-http-e2e";

    private static final String KUBECTL_MOUNT      = System.getenv().getOrDefault("E2E_VAULT_K8S_MOUNT", "devtools");
    private static final String KUBECTL_TEST_PATH  = KUBECTL_MOUNT + "/e2e-vault-kubectl";
    private static final String KUBECTL_TEST_FIELD = "e2e-field";
    private static final String KUBECTL_TEST_VALUE = "hello-from-kubectl-e2e";

    private static boolean httpAvailable;
    private static boolean kubectlAvailable;

    @BeforeAll
    static void setup() throws Exception {
        httpAvailable = VAULT_ADDR != null && !VAULT_ADDR.isBlank()
                     && VAULT_TOKEN != null && !VAULT_TOKEN.isBlank();
        kubectlAvailable = K8S_NAMESPACE != null && !K8S_NAMESPACE.isBlank();

        if (httpAvailable) {
            System.out.println("[E2E] Vault HTTP mode: " + VAULT_ADDR);
            vaultHttpPut(HTTP_TEST_PATH, HTTP_TEST_FIELD, HTTP_TEST_VALUE);
        }

        if (kubectlAvailable) {
            System.out.println("[E2E] Vault kubectl mode: " + K8S_NAMESPACE + "/" + K8S_POD);
            kubectlVaultPut(K8S_NAMESPACE, K8S_POD, KUBECTL_TEST_PATH, KUBECTL_TEST_FIELD, KUBECTL_TEST_VALUE);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (httpAvailable) {
            vaultHttpDelete(HTTP_TEST_PATH);
        }
        if (kubectlAvailable) {
            kubectlVaultDelete(K8S_NAMESPACE, K8S_POD, KUBECTL_TEST_PATH);
        }
    }

    @Test
    void get_httpMode_readsFieldFromVault() throws Exception {
        assumeTrue(httpAvailable, "VAULT_ADDR/VAULT_TOKEN not set; skipping HTTP mode test");
        VaultActor actor = new VaultActor("vault-test", null);
        ActionResult result = actor.get("[\"" + HTTP_TEST_PATH + "\",\"" + HTTP_TEST_FIELD + "\"]");
        System.out.println("[E2E] HTTP result: " + result.getResult());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(HTTP_TEST_VALUE);
    }

    @Test
    void get_kubectlMode_readsFieldFromVault() throws Exception {
        assumeTrue(kubectlAvailable, "E2E_VAULT_K8S_NAMESPACE not set; skipping kubectl mode test");
        VaultActor actor = new VaultActor("vault-test", null);
        ActionResult setResult = actor.setKubectl("[\"" + K8S_NAMESPACE + "\",\"" + K8S_POD + "\"]");
        assertThat(setResult.isSuccess()).isTrue();
        ActionResult result = actor.get("[\"" + KUBECTL_TEST_PATH + "\",\"" + KUBECTL_TEST_FIELD + "\"]");
        System.out.println("[E2E] kubectl result: " + result.getResult());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo(KUBECTL_TEST_VALUE);
    }

    @Test
    void get_missingPath_returnsFailure() throws Exception {
        assumeTrue(httpAvailable, "VAULT_ADDR/VAULT_TOKEN not set; skipping");
        VaultActor actor = new VaultActor("vault-test", null);
        ActionResult result = actor.get("[\"secret/nonexistent-e2e-path-xyz\",\"field\"]");
        System.out.println("[E2E] missing path result: " + result.getResult());
        assertThat(result.isSuccess()).isFalse();
    }

    // -------------------------------------------------------------------------

    private static void vaultHttpPut(String kvPath, String field, String value) throws Exception {
        String apiPath = toDataApiPath(kvPath);
        String url  = VAULT_ADDR + "/v1/" + apiPath;
        String body = "{\"data\":{\"" + field + "\":\"" + value + "\"}}";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Vault-Token", VAULT_TOKEN)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 204)
            throw new RuntimeException("Failed to write test secret (HTTP " + resp.statusCode() + "): " + resp.body());
    }

    private static void vaultHttpDelete(String kvPath) throws Exception {
        String metaPath = toMetadataApiPath(kvPath);
        String url = VAULT_ADDR + "/v1/" + metaPath;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Vault-Token", VAULT_TOKEN)
                .DELETE()
                .build();
        client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void kubectlVaultPut(String namespace, String pod, String kvPath, String field, String value)
            throws Exception {
        runKubectl(namespace, pod, "vault", "kv", "put", kvPath, field + "=" + value);
    }

    private static void kubectlVaultDelete(String namespace, String pod, String kvPath) throws Exception {
        runKubectl(namespace, pod, "vault", "kv", "metadata", "delete", kvPath);
    }

    private static void runKubectl(String namespace, String pod, String... vaultCmd) throws Exception {
        String[] prefix = {"kubectl", "exec", "-n", namespace, pod, "--"};
        String[] cmd = new String[prefix.length + vaultCmd.length];
        System.arraycopy(prefix, 0, cmd, 0, prefix.length);
        System.arraycopy(vaultCmd, 0, cmd, prefix.length, vaultCmd.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        int exitCode = p.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("kubectl exec failed (exit " + exitCode + "): " + out);
    }

    private static String toDataApiPath(String kvPath) {
        int slash = kvPath.indexOf('/');
        if (slash < 0) return kvPath + "/data";
        return kvPath.substring(0, slash) + "/data" + kvPath.substring(slash);
    }

    private static String toMetadataApiPath(String kvPath) {
        int slash = kvPath.indexOf('/');
        if (slash < 0) return kvPath + "/metadata";
        return kvPath.substring(0, slash) + "/metadata" + kvPath.substring(slash);
    }
}
