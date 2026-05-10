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

/**
 * {@link VaultBackend} that reads secrets via {@code kubectl exec} into a Vault pod.
 * Used when Vault is ClusterIP-only inside a k8s cluster.
 */
public class KubectlVaultBackend implements VaultBackend {

    private final String namespace;
    private final String pod;

    public KubectlVaultBackend(String namespace, String pod) {
        this.namespace = namespace;
        this.pod       = pod;
    }

    @Override
    public String get(String path, String fieldName) throws VaultException {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "kubectl", "exec", "-n", namespace, pod,
                    "--", "vault", "kv", "get", "-field=" + fieldName, path);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new VaultException("kubectl exec failed (exit " + exitCode + "): " + output);
            return output;
        } catch (IOException | InterruptedException e) {
            throw new VaultException("kubectl exec error: " + e.getMessage(), e);
        }
    }
}
