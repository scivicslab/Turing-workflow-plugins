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
 * Command-line tool for managing encrypted secrets by generating keys, encrypting plaintext INI files, or decrypting
 * encrypted files for verification.
 *
 * <ul><li>{@code generate-key} - produces a new Base64-encoded AES-256 encryption key to the standard output</li>
 * <li>{@code encrypt <input> <output> <key>} - transforms a plaintext secrets file into an encrypted file at the
 * specified output path</li>
 * <li>{@code decrypt <input> <key>} - decrypts an encrypted file and prints its plaintext content to the standard
 * output</li></ul>
 *
 * <pre>{@code
 * # Generate a new encryption key
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool generate-key
 *
 * # Encrypt a secrets file
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool encrypt secrets.ini secrets.enc <key>
 *
 * # Decrypt a secrets file (for verification)
 * java -cp plugin-secret.jar com.scivicslab.turingworkflow.plugins.secret.SecretTool decrypt secrets.enc <key>
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class SecretTool {

    /**
     * Executes a command-line interface for managing encrypted secrets.
     *
     * <p>The method processes the first argument as a command and performs the following:
     * <ul>
     * <li>{@code generate-key}: Prints a new encryption key to standard output.</li>
     * <li>{@code encrypt <input> <output> <key>}: Reads a plaintext file from {@code input}, encrypts it with {@code
     * key}, and writes the result to {@code output}.</li>
     * <li>{@code decrypt <input> <key>}: Reads an encrypted file from {@code input}, decrypts it with {@code key}, and
     * prints the resulting plaintext to standard output.</li>
     * </ul>
     *
     * <p>If the command is unknown or arguments are missing, it prints usage instructions to standard error and exits
     * with status 1. If an error occurs during encryption or decryption, it prints the error message and stack trace
     * to standard error and exits with status 1.
     *
     * @param args the command-line arguments, where the first argument is the command name and subsequent arguments
     * are the required parameters for that command.
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
