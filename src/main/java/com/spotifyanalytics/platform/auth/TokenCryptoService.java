package com.spotifyanalytics.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TokenCryptoService {

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public TokenCryptoService(@Value("${encryption.key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            key = null;
            return;
        }

        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must be base64", exception);
        }

        if (key.length != 32) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to 32 bytes");
        }
    }

    public String encrypt(String plaintext) {
        try {
            requireKey();
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt token", exception);
        }
    }

    public String decrypt(String storedValue) {
        try {
            requireKey();
            String[] parts = storedValue.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(
                            128,
                            Base64.getDecoder().decode(parts[0])));

            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[1])),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt token", exception);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is required");
        }
    }
}
