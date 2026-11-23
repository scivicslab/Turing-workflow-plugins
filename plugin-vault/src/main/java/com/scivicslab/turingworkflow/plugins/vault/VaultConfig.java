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
        String refusal = addressRefusal(address);
        if (refusal != null) {
            throw new IllegalArgumentException("Vault address " + address + ": " + refusal);
        }
        this.address = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        this.token = token;
    }

    /** The system property that lets the token travel over plain {@code http} to a host that is not this machine. */
    public static final String ALLOW_PLAIN_HTTP_PROPERTY = "turingworkflow.vault.allowPlainHttp";

    /**
     * Returns why the token must not be sent to {@code address}, or {@code null} when it may be.
     *
     * <p>Every request carries the token in a header. Over plain {@code http} to another host, anyone on the path
     * reads it and can use it as the program does. So {@code https} is required, except for a loopback address, or
     * when the program sets {@value #ALLOW_PLAIN_HTTP_PROPERTY} to {@code true}, which it does by name.</p>
     */
    static String addressRefusal(String address) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(address.trim());
        } catch (java.net.URISyntaxException e) {
            return "not a URL: " + e.getReason();
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null) {
            return "the address needs a scheme and a host";
        }
        if (scheme.equalsIgnoreCase("https")) {
            return null;
        }
        if (!scheme.equalsIgnoreCase("http")) {
            return "only http and https are accepted";
        }
        boolean loopback;
        try {
            String host = uri.getHost();
            String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            loopback = bare.equalsIgnoreCase("localhost") || java.net.InetAddress.getByName(bare).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            loopback = false;
        }
        if (loopback || Boolean.getBoolean(ALLOW_PLAIN_HTTP_PROPERTY)) {
            return null;
        }
        return "the token would travel in clear text; use https (or set -D" + ALLOW_PLAIN_HTTP_PROPERTY
                + "=true to accept plain http)";
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
