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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import static com.scivicslab.pojoactor.core.ActionArgs.getFirst;
import static com.scivicslab.pojoactor.core.ActionArgs.getString;

/**
 * Workflow actor for reading secrets from HashiCorp Vault KV v2.
 *
 * <p>Default mode is HTTP (requires {@code VAULT_ADDR} and {@code VAULT_TOKEN} env vars).
 * Call {@code setKubectl} to switch to kubectl mode for k8s-internal Vault (ClusterIP only).</p>
 *
 * <pre>
 * - actor: vault
 *   method: setKubectl
 *   arguments: ["infra-vault", "vault-0"]
 *
 * - actor: vault
 *   method: get
 *   arguments: ["keycloak-local-llm/test-users", "testadmin-password"]
 * </pre>
 */
public class VaultActor extends IIActorRef<VaultActor.State> {

    public static class State {
        VaultBackend backend;
    }

    public VaultActor(String actorName, IIActorSystem system) {
        super(actorName, new State(), system);
    }

    /** Switch to kubectl mode: subsequent get calls use kubectl exec into the named pod. */
    @Action("setKubectl")
    public ActionResult setKubectl(String args) {
        String namespace = getFirst(args);
        String pod       = getString(args, 1);
        if (namespace == null || namespace.isBlank())
            return new ActionResult(false, "setKubectl: namespace must not be blank");
        if (pod == null || pod.isBlank())
            return new ActionResult(false, "setKubectl: pod name must not be blank");
        object.backend = new KubectlVaultBackend(namespace, pod);
        return new ActionResult(true, "Vault kubectl mode: " + namespace + "/" + pod);
    }

    /**
     * Reads a named field from a Vault KV v2 secret.
     *
     * <p>Arguments: {@code ["mount/key", "fieldName"]}
     * e.g. {@code ["keycloak-local-llm/test-users", "testadmin-password"]}</p>
     *
     * <p>On success, {@code ${result}} holds the field value.</p>
     */
    @Action("get")
    public ActionResult get(String args) {
        String path      = getFirst(args);
        String fieldName = getString(args, 1);
        if (path == null || path.isBlank())
            return new ActionResult(false, "get: path must not be blank");
        if (fieldName == null || fieldName.isBlank())
            return new ActionResult(false, "get: fieldName must not be blank");
        try {
            VaultBackend backend = resolveBackend();
            return new ActionResult(true, backend.get(path, fieldName));
        } catch (VaultException e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    private VaultBackend resolveBackend() throws VaultException {
        if (object.backend == null)
            object.backend = new HttpVaultBackend();
        return object.backend;
    }
}
