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
 * {@link VaultBackend} that calls the Vault HTTP API directly.
 * Reads {@code VAULT_ADDR} and {@code VAULT_TOKEN} from environment variables.
 */
public class HttpVaultBackend implements VaultBackend {

    private final VaultClient client;

    public HttpVaultBackend() throws VaultException {
        String addr  = System.getenv("VAULT_ADDR");
        String token = System.getenv("VAULT_TOKEN");
        if (addr == null || addr.isBlank())
            throw new VaultException("VAULT_ADDR environment variable is not set");
        if (token == null || token.isBlank())
            throw new VaultException("VAULT_TOKEN environment variable is not set");
        this.client = new VaultClient(new VaultConfig(addr, token));
    }

    @Override
    public String get(String path, String fieldName) throws VaultException {
        try {
            return client.readField(path, fieldName);
        } catch (VaultClient.VaultException e) {
            throw new VaultException(e.getMessage(), e);
        }
    }
}
