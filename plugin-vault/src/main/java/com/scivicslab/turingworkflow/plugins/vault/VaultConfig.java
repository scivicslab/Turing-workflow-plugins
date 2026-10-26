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
 * Holds the connection details for a HashiCorp Vault server, including the server address and the authentication
 * token.
 *
 * <p>Returns the Vault server address.
 *
 * <p>Returns the Vault authentication token.
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class VaultConfig {

    /** The Vault server address used for HTTP connections. */
    private final String address;

    /** The Vault authentication token used to authorize requests to the Vault server. */
    private final String token;

    /**
     * Initializes a new configuration with a Vault server address and an authentication token.
     *
     * @param address the Vault server address, such as "http://127.0.0.1:8200"
     * @param token the Vault authentication token
     * @throws IllegalArgumentException if address or token is null or empty
     */
    public VaultConfig(String address, String token) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Vault address cannot be null or empty");
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Vault token cannot be null or empty");
        }
        this.address = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        this.token = token;
    }

    /**
     * Returns the Vault server address.
     *
     * @return the address of the Vault server
     */
    public String getAddress() {
        return address;
    }

    /** Returns the Vault authentication token. */
    public String getToken() {
        return token;
    }

    /** Returns a string containing the Vault server address and a masked version of the authentication token. */
    @Override
    public String toString() {
        return "VaultConfig{address='" + address + "', token='***'}";
    }
}
