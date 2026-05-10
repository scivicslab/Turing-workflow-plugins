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

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Client for HashiCorp Vault API communication.
 * Supports reading secrets from Vault KV v2 engine.
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class VaultClient {

    /** The Vault connection configuration (address and token). */
    private final VaultConfig config;

    /** Reusable HTTP client with a 10-second connection timeout. */
    private final HttpClient httpClient;

    /**
     * Creates a new VaultClient with the given configuration.
     *
     * @param config Vault configuration
     */
    public VaultClient(VaultConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Reads a named field from a Vault KV v2 secret.
     *
     * The path uses CLI-style notation ({@code mount/key}); {@code data/} is
     * inserted automatically between the mount and the key.
     * For example, {@code "keycloak-local-llm/test-users"} becomes the API path
     * {@code /v1/keycloak-local-llm/data/test-users}.
     *
     * @param kvPath    KV path in CLI notation (e.g., "keycloak-local-llm/test-users")
     * @param fieldName name of the field inside the secret's data map
     * @return field value as String
     * @throws VaultException if Vault communication fails, secret not found, or field missing
     */
    public String readField(String kvPath, String fieldName) throws VaultException {
        int slash = kvPath.indexOf('/');
        String apiPath = (slash < 0)
                ? kvPath + "/data"
                : kvPath.substring(0, slash) + "/data" + kvPath.substring(slash);
        String body = fetch(apiPath);
        return extractField(body, fieldName);
    }

    /**
     * Reads a secret from Vault using the full API path (legacy; reads the {@code value} field).
     *
     * @param path full KV v2 API path (e.g., "secret/data/ssh/iacuser/private_key")
     * @return value of the {@code value} field
     * @throws VaultException if Vault communication fails or secret not found
     */
    public String readSecret(String path) throws VaultException {
        return extractField(fetch(path), "value");
    }

    private String fetch(String path) throws VaultException {
        try {
            String url = config.getAddress() + "/v1/" + path;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vault-Token", config.getToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new VaultException("Secret not found at path: " + path);
            }

            if (response.statusCode() != 200) {
                throw new VaultException("Vault returned status " + response.statusCode()
                        + ": " + response.body());
            }

            return response.body();

        } catch (IOException | InterruptedException e) {
            throw new VaultException("Failed to read secret from Vault: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a named field from the {@code data.data} map of a Vault KV v2 response.
     *
     * @param jsonResponse JSON response body from Vault
     * @param fieldName    name of the field to extract
     * @return field value as String
     * @throws VaultException if the response is malformed or the field is absent
     */
    @SuppressWarnings("unchecked")
    private String extractField(String jsonResponse, String fieldName) throws VaultException {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> response = yaml.load(jsonResponse);

            Object dataObj = response.get("data");
            if (!(dataObj instanceof Map)) {
                throw new VaultException("Invalid Vault response: 'data' field missing or not a map");
            }

            Map<String, Object> data = (Map<String, Object>) dataObj;
            Object innerDataObj = data.get("data");
            if (!(innerDataObj instanceof Map)) {
                throw new VaultException("Invalid Vault response: nested 'data' field missing or not a map");
            }

            Map<String, Object> innerData = (Map<String, Object>) innerDataObj;
            Object value = innerData.get(fieldName);
            if (value == null) {
                throw new VaultException("Field '" + fieldName + "' not found in secret. "
                        + "Available fields: " + innerData.keySet());
            }

            return value.toString();

        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("Failed to parse Vault response: " + e.getMessage(), e);
        }
    }

    /** Exception thrown when Vault HTTP operations fail. */
    public static class VaultException extends Exception {
        public VaultException(String message) { super(message); }
        public VaultException(String message, Throwable cause) { super(message, cause); }
    }
}
