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
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;

/**
 * Communicates with the HashiCorp Vault HTTP API to retrieve secrets from KV v2 engines.
 *
 * <ul><li>{@code readField(kvPath, fieldName)} returns the value of a specific field from a secret at the given
 * path.</li><li>{@code readSecret(path)} returns the value of the {@code value} field from a secret at the given full
 * API path.</li></ul>
 *
 * <p>One instance may serve several threads at once.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class VaultClient {

    /** System property: seconds the TCP connect to the Vault server may take. */
    public static final String CONNECT_TIMEOUT_PROPERTY = "turingworkflow.vault.connectTimeoutSeconds";

    /** System property: seconds one request may take from sending to the end of the response body. */
    public static final String REQUEST_TIMEOUT_PROPERTY = "turingworkflow.vault.requestTimeoutSeconds";

    /** Default of {@value #CONNECT_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    /** Default of {@value #REQUEST_TIMEOUT_PROPERTY}. */
    public static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;

    /** The Vault connection configuration, consisting of the server address and the access token. */
    private final VaultConfig config;

    /** The HTTP client used to send requests to the Vault API. */
    private final HttpClient httpClient;

    /**
     * Initializes a new VaultClient that communicates with a HashiCorp Vault server using the provided configuration.
     *
     * <p>Settings, read from system properties: {@value #CONNECT_TIMEOUT_PROPERTY} (default {@value
     * #DEFAULT_CONNECT_TIMEOUT_SECONDS}), read here, and {@value #REQUEST_TIMEOUT_PROPERTY} (default {@value
     * #DEFAULT_REQUEST_TIMEOUT_SECONDS}), read on each request: past it the request is cancelled and fails.</p>
     *
     * @param config the address and token used for Vault API requests
     */
    public VaultClient(VaultConfig config) {
        this.config = config;
        // The client's work runs on virtual threads: without an executor the JDK gives each HttpClient a
        // pool of real threads of its own. The JDK still keeps one internal selector thread per client,
        // which is the client's own I/O loop, not a pool we create.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        SystemPropertyLimit.read(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Returns a string that is the value of the specified field within a Vault KV v2 secret.
     *
     * @param kvPath the KV path in CLI-style notation (e.g., "keycloak-local-llm/test-users")
     * @param fieldName the name of the field to retrieve from the secret's data
     * @return the value of the field as a string
     * @throws VaultException if the secret is not found, the field is missing, or communication with Vault fails
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
     * Returns the value of the {@code value} field from a Vault secret at the specified API path.
     *
     * @param path the full KV v2 API path (e.g., "secret/data/ssh/iacuser/private_key")
     * @throws VaultException if Vault communication fails or the secret is not found
     */
    public String readSecret(String path) throws VaultException {
        return extractField(fetch(path), "value");
    }

    private String fetch(String path) throws VaultException {
        String url = config.getAddress() + "/v1/" + path;
        long limit = SystemPropertyLimit.read(REQUEST_TIMEOUT_PROPERTY, DEFAULT_REQUEST_TIMEOUT_SECONDS);
        try {
            // HttpRequest.timeout ends the wait for the response headers only; the future bounds the body as well
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vault-Token", config.getToken())
                    .timeout(Duration.ofSeconds(limit))
                    .GET()
                    .build();

            CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                response = pending.get(limit, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new HttpTimeoutException("timed out");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException(e.getCause());
            } catch (InterruptedException e) {
                pending.cancel(true);
                throw e;
            }

            if (response.statusCode() == 404) {
                throw new VaultException("Secret not found at path: " + path);
            }

            if (response.statusCode() != 200) {
                throw new VaultException("Vault returned status " + response.statusCode()
                        + ": " + response.body());
            }

            return response.body();

        } catch (HttpConnectTimeoutException e) {
            throw new VaultException("Vault at " + config.getAddress() + " did not accept the connection within "
                    + SystemPropertyLimit.read(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_SECONDS) + " s ("
                    + CONNECT_TIMEOUT_PROPERTY + ")", e);
        } catch (HttpTimeoutException e) {
            throw new VaultException("Vault request to " + url + " did not finish within " + limit + " s ("
                    + REQUEST_TIMEOUT_PROPERTY + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("Failed to read secret from Vault: interrupted", e);
        } catch (IOException e) {
            throw new VaultException("Failed to read secret from Vault: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the value of a specific field from a Vault KV v2 JSON response.
     *
     * @param jsonResponse the JSON response body received from the Vault API
     * @param fieldName the name of the field to retrieve from the nested data map
     * @return the value of the field as a string
     * @throws VaultException if the response is not a valid Vault KV v2 structure or if the field is not present
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

    /**
     * Indicates a failure in communicating with HashiCorp Vault or a failure to find the requested secret or field.
     */
    public static class VaultException extends Exception {
        public VaultException(String message) { super(message); }
        public VaultException(String message, Throwable cause) { super(message, cause); }
    }
}
