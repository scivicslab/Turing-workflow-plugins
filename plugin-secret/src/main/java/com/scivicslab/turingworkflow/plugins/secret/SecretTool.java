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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line tool for managing encrypted secrets.
 *
 * <h2>Usage</h2>
 * <pre>
 * # Generate a new encryption key
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool generate-key
 *
 * # Encrypt a secrets file
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool encrypt secrets.ini secrets.enc &lt;key&gt;
 *
 * # Decrypt a secrets file (for verification)
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool decrypt secrets.enc &lt;key&gt;
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class SecretTool {

    /**
     * Entry point for the secret management CLI.
     *
     * <p>Supported commands:</p>
     * <ul>
     *   <li>{@code generate-key} - Generate a new AES-256 encryption key</li>
     *   <li>{@code encrypt <input> <output> <key>} - Encrypt a plaintext secrets file</li>
     *   <li>{@code decrypt <input> <key>} - Decrypt and display an encrypted secrets file</li>
     * </ul>
     *
     * @param args command-line arguments; first argument is the command name
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        try {
            switch (command) {
                case "generate-key":
                    generateKey();
                    break;

                case "encrypt":
                    if (args.length != 4) {
                        System.err.println("Error: encrypt requires 3 arguments: <input> <output> <key>");
                        printUsage();
                        System.exit(1);
                    }
                    encrypt(args[1], args[2], args[3]);
                    break;

                case "decrypt":
                    if (args.length != 3) {
                        System.err.println("Error: decrypt requires 2 arguments: <input> <key>");
                        printUsage();
                        System.exit(1);
                    }
                    decrypt(args[1], args[2]);
                    break;

                default:
                    System.err.println("Error: Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void generateKey() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        System.out.println("Generated encryption key:");
        System.out.println(key);
        System.out.println();
        System.out.println("Save this key securely and set it as an environment variable:");
        System.out.println("export TURING_SECRET_KEY=\"" + key + "\"");
    }

    private static void encrypt(String inputPath, String outputPath, String key) throws Exception {
        // Read plaintext
        String plaintext = Files.readString(Path.of(inputPath));

        // Encrypt
        String encrypted = SecretEncryptor.encrypt(plaintext, key);

        // Write encrypted data
        Files.writeString(Path.of(outputPath), encrypted);

        System.out.println("Successfully encrypted " + inputPath + " -> " + outputPath);
    }

    private static void decrypt(String inputPath, String key) throws Exception {
        // Read encrypted data
        String encrypted = Files.readString(Path.of(inputPath));

        // Decrypt
        String decrypted = SecretEncryptor.decrypt(encrypted, key);

        // Print decrypted content
        System.out.println("Decrypted content:");
        System.out.println("---");
        System.out.println(decrypted);
        System.out.println("---");
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool <command> [args]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  generate-key                    Generate a new encryption key");
        System.out.println("  encrypt <input> <output> <key>  Encrypt a secrets file");
        System.out.println("  decrypt <input> <key>           Decrypt a secrets file (for verification)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Generate key");
        System.out.println("  java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool generate-key");
        System.out.println();
        System.out.println("  # Encrypt");
        System.out.println("  java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool encrypt secrets.ini secrets.enc \"$TURING_SECRET_KEY\"");
        System.out.println();
        System.out.println("  # Decrypt (verify)");
        System.out.println("  java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool decrypt secrets.enc \"$TURING_SECRET_KEY\"");
    }
}
