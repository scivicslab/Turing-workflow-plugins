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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import static com.scivicslab.pojoactor.action.ActionArgs.getFirst;
import static com.scivicslab.pojoactor.action.ActionArgs.getString;

/**
 * Fetches secrets from HashiCorp Vault KV v2 via HTTP or {@code kubectl exec}.
 *
 * <ul>
 * <li>{@code setKubectl} switches the actor from HTTP mode to {@code kubectl} mode using the provided namespace and
 * pod name.</li>
 * <li>{@code get} retrieves a specific field from a secret at the given path, returning the field's value as the
 * result.</li>
 * </ul>
 *
 * <p>The actor starts in HTTP mode, which requires the {@code VAULT_ADDR} and {@code VAULT_TOKEN} environment
 * variables. Once {@code setKubectl} is called, all subsequent {@code get} calls use {@code kubectl exec} until the
 * actor is re-instantiated.</p>
 *
 * <p>One instance of this actor may serve several workflows at once, but its actions run one at a time.</p>
 *
 * <pre>{@code
 * - actor: vault
 *   method: setKubectl
 *   arguments: ["infra-vault", "vault-0"]
 *
 * - actor: vault
 *   method: get
 *   arguments: ["keycloak-local-llm/test-users", "testadmin-password"]
 * }</pre>
 *
 * <p>If a path or field name is blank, or if the backend fails to retrieve the data, the action returns a failure
 * result with an error message.</p>
 */
public class VaultActor extends IIActorRef<VaultActor.State> {

    public static class State {
        VaultBackend backend;
    }

    public VaultActor(String actorName, IIActorSystem system) {
        super(actorName, new State(), system);
    }

    /** Switch to kubectl mode: subsequent get calls use kubectl exec into the named pod. */
    /**
     * Holds the Kubernetes location for {@code kubectl exec} access.
     *
     * @param namespace the Kubernetes namespace where the Vault pod is running
     * @param pod the name of the Vault pod
     */
    public record KubectlArgs(@NotNull String namespace, @NotNull String pod) {}

    /**
     * The secret's path and the specific field name to be retrieved.
     *
     * @param path the secret's path in Vault, following the format {@code <mount>/<key>}
     * @param field the name of the field within that secret
     */
    public record SecretArgs(@NotNull String path, @NotNull String field) {}

    @Action(value = "setKubectl", argsType = KubectlArgs.class)
    public ActionResult setKubectl(KubectlArgs args) {
        String namespace = args.namespace();
        String pod       = args.pod();
        if (namespace == null || namespace.isBlank())
            return new ActionResult(false, "setKubectl: namespace must not be blank");
        if (pod == null || pod.isBlank())
            return new ActionResult(false, "setKubectl: pod name must not be blank");
        object.backend = new KubectlVaultBackend(namespace, pod);
        return new ActionResult(true, "Vault kubectl mode: " + namespace + "/" + pod);
    }

    /**
     * Returns a string that is the value of the specified field from a Vault KV v2 secret.
     *
     * <p>The result is the value of the field if the secret exists and the field is found; otherwise, the action fails
     * and the message holds the error description. If no backend has been configured via {@code setKubectl}, the call
     * is made via HTTP using the {@code VAULT_ADDR} and {@code VAULT_TOKEN} environment variables.
     *
     * <pre>{@code
     * - actor: vault
     *   method: get
     *   arguments: ["keycloak-local-llm/test-users", "testadmin-password"]
     * }</pre>
     *
     * @param args a record containing the path to the secret and the name of the field to retrieve
     */
    @Action(value = "get", argsType = SecretArgs.class)
    public ActionResult get(SecretArgs args) {
        String path      = args.path();
        String fieldName = args.field();
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
