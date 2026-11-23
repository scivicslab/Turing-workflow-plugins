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

package com.scivicslab.turingworkflow.plugins.vault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches secrets from HashiCorp Vault by executing {@code kubectl exec} into a specific Kubernetes pod.
 *
 * <p>Use this when Vault is only accessible via ClusterIP within a cluster and cannot be reached via HTTP.
 *
 * <ul>
 * <li>{@code get(path, fieldName)} returns the value of the specified field from the KV v2 secret at the given
 * path.</li>
 * </ul>
 *
 * <p>The class holds the target namespace and pod name.
 *
 * <p>One instance may serve several workflows or threads at once.
 *
 * <p>An action fails if the {@code kubectl} command returns a non-zero exit code or if the execution fails, returning
 * a failure result with the error message.
 *
 * <p>Setting: the system property {@value #TIMEOUT_PROPERTY} (default {@value #DEFAULT_TIMEOUT_SECONDS}) is how many
 * seconds one {@code kubectl exec} may run before it is killed and the call fails.
 */
public class KubectlVaultBackend implements VaultBackend {

    /** System property: seconds one {@code kubectl exec} may run. */
    public static final String TIMEOUT_PROPERTY = "turingworkflow.vault.kubectlTimeoutSeconds";

    /** Default of {@value #TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;

    /** How long the output readers may still take after the process has exited. */
    private static final long READER_GRACE_SECONDS = 5;

    private final String namespace;
    private final String pod;

    public KubectlVaultBackend(String namespace, String pod) {
        this.namespace = namespace;
        this.pod       = pod;
    }

    @Override
    public String get(String path, String fieldName) throws VaultException {
        String refusal = firstNonNull(
                VaultNames.kubernetesNameRefusal("namespace", namespace),
                VaultNames.kubernetesNameRefusal("pod name", pod),
                VaultNames.pathRefusal(path),
                VaultNames.fieldRefusal(fieldName));
        if (refusal != null) throw new VaultException("Refused: " + refusal);
        return secretPrintedBy(List.of(
                "kubectl", "exec", "-n", namespace, pod,
                "--", "vault", "kv", "get", "-field=" + fieldName, path));
    }

    private static String firstNonNull(String... refusals) {
        for (String r : refusals) if (r != null) return r;
        return null;
    }

    /**
     * Returns what the command printed on stdout, trimmed; stderr is read apart and never becomes part of the value.
     *
     * <p>The command may run for {@value #TIMEOUT_PROPERTY} seconds; past that it and the processes it started are
     * killed. A non-zero exit fails with the exit code and stderr; the stdout, which may hold the secret, is never put
     * in a failure message.</p>
     *
     * @param command the command line, normally {@code kubectl exec ... vault kv get -field=<f> <path>}
     * @throws VaultException if the command cannot start, does not finish in time, or exits non-zero
     */
    static String secretPrintedBy(List<String> command) throws VaultException {
        long limit = SystemPropertyLimit.read(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new VaultException("kubectl exec error: " + e.getMessage(), e);
        }
        CompletableFuture<String> stdout = readAll(process.getInputStream());
        CompletableFuture<String> stderr = readAll(process.getErrorStream());
        try {
            if (!process.waitFor(limit, TimeUnit.SECONDS)) {
                kill(process);
                throw new VaultException("kubectl exec in " + describe(command) + " did not finish within " + limit
                        + " s and was killed (" + TIMEOUT_PROPERTY + ")");
            }
            int exitCode = process.exitValue();
            String errors = stderr.get(READER_GRACE_SECONDS, TimeUnit.SECONDS).trim();
            if (exitCode != 0)
                throw new VaultException("kubectl exec failed (exit " + exitCode + "): " + errors);
            return stdout.get(READER_GRACE_SECONDS, TimeUnit.SECONDS).trim();
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new VaultException("kubectl exec interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            kill(process);
            throw new VaultException("kubectl exec output could not be read: " + e, e);
        }
    }

    /** Names the pod the command execs into, or the program when the command is not a kubectl exec. */
    private static String describe(List<String> command) {
        int separator = command.indexOf("--");
        return separator > 0 ? "pod " + command.get(separator - 1) : command.get(0);
    }

    private static CompletableFuture<String> readAll(InputStream in) {
        CompletableFuture<String> text = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                text.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                text.completeExceptionally(e);
            }
        });
        return text;
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
