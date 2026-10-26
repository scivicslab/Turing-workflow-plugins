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

package com.scivicslab.turingworkflow.plugins.secret;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * An encrypted secret configuration that decrypts an INI-format file and resolves secrets using a priority hierarchy
 * of host, group, and global settings.
 *
 * <ul>
 * <li>{@code parse(InputStream, String)} decrypts and parses an encrypted file into a configuration object.</li>
 * <li>{@code getSecretsForHost(String, String...)} returns a map of secrets for a specific host, where host-specific
 * values override group values, which in turn override global values.</li>
 * <li>{@code getGlobalSecrets()} returns a map of only the secrets defined in the {@code [secrets:all]} section.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * InputStream encryptedInput = new FileInputStream("secrets.enc");
 * String key = System.getenv("TURING_SECRET_KEY");
 * EncryptedSecretConfig config = EncryptedSecretConfig.parse(encryptedInput, key);
 *
 * Map<String, String> secrets = config.getSecretsForHost("web1.example.com", "webservers");
 * String sshKey = secrets.get("ssh_key");
 * }</pre>
 *
 * <p>The {@code parse} method returns an {@link IOException} if the file cannot be read or if the decryption fails due
 * to an incorrect key or corrupted data.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class EncryptedSecretConfig {

    /** Secrets defined in the {@code [secrets:all]} section, which have the lowest priority. */
    private final Map<String, String> globalSecrets = new HashMap<>();

    /** Secrets defined in {@code [secrets:<groupName>]} sections (medium priority). */
    private final Map<String, Map<String, String>> groupSecrets = new HashMap<>();

    /** Secrets defined in {@code [secrets:host:<hostname>]} sections (highest priority). */
    private final Map<String, Map<String, String>> hostSecrets = new HashMap<>();

    /**
     * Returns an {@code EncryptedSecretConfig} containing the decrypted secrets from an encrypted INI file.
     *
     * @param encryptedInput the input stream of the encrypted file
     * @param encryptionKey the Base64-encoded AES-256 key used for decryption
     * @return an instance of {@code EncryptedSecretConfig} from which secrets can be queried
     * @throws IOException if the file cannot be read or if decryption fails due to an invalid key or corrupted data
     */
    public static EncryptedSecretConfig parse(InputStream encryptedInput, String encryptionKey) throws IOException {
        try {
            // Read encrypted content
            StringBuilder encrypted = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(encryptedInput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    encrypted.append(line);
                }
            }

            // Decrypt
            String decrypted = SecretEncryptor.decrypt(encrypted.toString(), encryptionKey);

            // Parse decrypted content
            return parseDecrypted(decrypted);

        } catch (SecretEncryptor.EncryptionException e) {
            throw new IOException("Failed to decrypt secret configuration", e);
        }
    }

    /**
     * Parses the decrypted INI-format content into an {@code EncryptedSecretConfig}.
     *
     * @param content the decrypted string containing INI sections and key-value pairs
     * @return a new {@code EncryptedSecretConfig} instance populated with the parsed secrets
     * @throws IOException if the content cannot be read or the format is invalid
     */
    private static EncryptedSecretConfig parseDecrypted(String content) throws IOException {
        EncryptedSecretConfig config = new EncryptedSecretConfig();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            String currentSection = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Section header
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                // Key-value pair
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0 && currentSection != null) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();

                    // Unescape newlines in multi-line values (e.g., SSH keys)
                    value = value.replace("\\n", "\n");

                    if (currentSection.equals("secrets:all")) {
                        config.globalSecrets.put(key, value);
                    } else if (currentSection.startsWith("secrets:host:")) {
                        String hostname = currentSection.substring("secrets:host:".length());
                        config.hostSecrets.computeIfAbsent(hostname, k -> new HashMap<>()).put(key, value);
                    } else if (currentSection.startsWith("secrets:")) {
                        String groupName = currentSection.substring("secrets:".length());
                        config.groupSecrets.computeIfAbsent(groupName, k -> new HashMap<>()).put(key, value);
                    }
                }
            }
        }

        return config;
    }

    /**
     * Returns a map of secrets for a specific host, resolving conflicts by applying priority rules where host-specific
     * secrets override group-specific secrets, which in turn override global secrets.
     *
     * @param hostname the name of the host to look up
     * @param groupNames the names of the groups the host belongs to, in order of increasing priority
     * @return a map containing the resolved secrets for the host
     */
    public Map<String, String> getSecretsForHost(String hostname, String... groupNames) {
        Map<String, String> result = new HashMap<>(globalSecrets);

        // Apply group secrets (later groups override earlier ones)
        for (String groupName : groupNames) {
            Map<String, String> groupSecretMap = groupSecrets.get(groupName);
            if (groupSecretMap != null) {
                result.putAll(groupSecretMap);
            }
        }

        // Apply host-specific secrets (highest priority)
        Map<String, String> hostSecretMap = hostSecrets.get(hostname);
        if (hostSecretMap != null) {
            result.putAll(hostSecretMap);
        }

        return result;
    }

    /**
     * Returns a map containing the secrets defined in the {@code [secrets:all]} section.
     *
     * @return a map of the global secrets
     */
    public Map<String, String> getGlobalSecrets() {
        return new HashMap<>(globalSecrets);
    }
}
