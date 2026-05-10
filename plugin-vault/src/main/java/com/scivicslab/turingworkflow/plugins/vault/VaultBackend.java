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

/**
 * Strategy for reading a field from a Vault KV v2 secret.
 * Two implementations exist: {@link HttpVaultBackend} and {@link KubectlVaultBackend}.
 */
public interface VaultBackend {

    /**
     * Reads a named field from a Vault KV v2 secret.
     *
     * @param path      KV path in CLI notation (e.g., "keycloak-local-llm/test-users")
     * @param fieldName name of the field inside the secret's data map
     * @return field value as String
     * @throws VaultException if the secret cannot be read or the field is absent
     */
    String get(String path, String fieldName) throws VaultException;
}
