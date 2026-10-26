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

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Performs AES-256-GCM authenticated encryption and decryption on strings to protect secrets.
 *
 * <p>The class offers the following operations:
 * <ul>
 * <li>{@link #generateKey()} returns a new Base64-encoded 256-bit symmetric encryption key.</li>
 * <li>{@link #encrypt(String, String)} takes a plaintext string and a Base64-encoded key, returning a Base64-encoded
 * string containing the 96-bit IV, the ciphertext, and the 128-bit authentication tag.</li>
 * <li>{@link #decrypt(String, String)} takes a Base64-encoded encrypted string and a Base64-encoded key, returning the
 * original plaintext string.</li>
 * </ul>
 *
 * <p>An {@link EncryptionException} is thrown if key generation, encryption, or decryption fails, such as when an
 * incorrect key is used for decryption.
 *
 * <p>Example:
 * <pre>{@code
 * String key = SecretEncryptor.generateKey();
 * String encrypted = SecretEncryptor.encrypt("my-secret", key);
 * String decrypted = SecretEncryptor.decrypt(encrypted, key);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class SecretEncryptor {

    /** The symmetric algorithm name used for key generation. */
    private static final String ALGORITHM = "AES";

    /** The cipher transformation string used for AES in GCM mode with no padding. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** The AES key length in bits, set to 256 for AES-256 encryption. */
    private static final int KEY_SIZE = 256;

    /** The length in bytes of the GCM initialization vector (IV) used for each encryption operation. */
    private static final int GCM_IV_LENGTH = 12;

    /** The length in bits of the GCM authentication tag used to ensure data integrity. */
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * Returns a Base64-encoded 256-bit AES encryption key.
     *
     * @return a Base64-encoded string representing the new symmetric key
     * @throws EncryptionException if the key generation fails
     */
    public static String generateKey() throws EncryptionException {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
            keyGenerator.init(KEY_SIZE, new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (Exception e) {
            throw new EncryptionException("Failed to generate encryption key", e);
        }
    }

    /**
     * Returns a Base64-encoded string containing a 12-byte random IV, the ciphertext, and a 16-byte authentication tag
     * produced by encrypting the plaintext with the given key using AES-256-GCM.
     *
     * @param plaintext the text to be encrypted
     * @param base64Key the Base64-encoded 256-bit AES key
     * @return a Base64-encoded string consisting of the 12-byte IV, the ciphertext, and the 16-byte GCM authentication
     * tag
     *
     * @throws EncryptionException if the encryption process fails
     */
    public static String encrypt(String plaintext, String base64Key) throws EncryptionException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            SecretKey secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // Encrypt
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            // Combine IV + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            // Return Base64-encoded result
            return Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Returns a string containing the original plaintext from the encrypted data.
     *
     * @param encryptedBase64 the Base64-encoded string consisting of the 12-byte IV, the ciphertext, and the 16-byte
     * authentication tag
     *
     * @param base64Key the Base64-encoded 256-bit AES encryption key
     * @return the decrypted plaintext as a UTF-8 string
     * @throws EncryptionException if decryption or authentication fails
     */
    public static String decrypt(String encryptedBase64, String base64Key) throws EncryptionException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            SecretKey secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            // Decode encrypted data
            byte[] encryptedData = Base64.getDecoder().decode(encryptedBase64);

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // Decrypt
            byte[] plaintextBytes = cipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt data", e);
        }
    }

    /**
     * Indicates a failure in encryption or decryption operations.
     *
     * <p>An instance is thrown when a cryptographic operation fails, such as when a GCM authentication tag does not
     * match or an invalid key is provided. It wraps the underlying cause to provide a descriptive error message.</p>
     */
    public static class EncryptionException extends Exception {

        /**
         * Initializes a new {@code EncryptionException} with a description of the failure and the underlying cause.
         *
         * @param message a description of what went wrong
         * @param cause the underlying exception that caused this failure
         */
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
