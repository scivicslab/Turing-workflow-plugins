package com.scivicslab.turingworkflow.plugins.secret;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretEncryptorTest {

    @Test
    void generateKey_returnsBase64EncodedString() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        assertThat(key).isNotBlank();
        byte[] decoded = java.util.Base64.getDecoder().decode(key);
        assertThat(decoded).hasSize(32); // 256 bits = 32 bytes
    }

    @Test
    void generateKey_returnsDifferentKeyEachTime() throws SecretEncryptor.EncryptionException {
        String key1 = SecretEncryptor.generateKey();
        String key2 = SecretEncryptor.generateKey();
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void encrypt_decrypt_roundtrip() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        String plaintext = "SECRET_KEY=abc123\nDB_PASS=hunter2";

        String encrypted = SecretEncryptor.encrypt(plaintext, key);
        assertThat(encrypted).isNotEqualTo(plaintext);

        String decrypted = SecretEncryptor.decrypt(encrypted, key);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesRandomCiphertextEachTime() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        String plaintext = "same plaintext";

        String enc1 = SecretEncryptor.encrypt(plaintext, key);
        String enc2 = SecretEncryptor.encrypt(plaintext, key);
        // GCM uses random IV, so ciphertexts differ even for the same input
        assertThat(enc1).isNotEqualTo(enc2);
    }

    @Test
    void decrypt_withWrongKey_throwsEncryptionException() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        String wrongKey = SecretEncryptor.generateKey();
        String encrypted = SecretEncryptor.encrypt("secret data", key);

        assertThatThrownBy(() -> SecretEncryptor.decrypt(encrypted, wrongKey))
                .isInstanceOf(SecretEncryptor.EncryptionException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    void encrypt_decrypt_preservesEmptyString() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        String decrypted = SecretEncryptor.decrypt(SecretEncryptor.encrypt("", key), key);
        assertThat(decrypted).isEmpty();
    }

    @Test
    void encrypt_decrypt_preservesUnicode() throws SecretEncryptor.EncryptionException {
        String key = SecretEncryptor.generateKey();
        String plaintext = "パスワード=秘密\npassword=secret";
        String decrypted = SecretEncryptor.decrypt(SecretEncryptor.encrypt(plaintext, key), key);
        assertThat(decrypted).isEqualTo(plaintext);
    }
}
