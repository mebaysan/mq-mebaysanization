package com.baysansoft.mqmanager.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for stored broker passwords.
 *
 * <p>Plain JCE rather than Jasypt: Jasypt's Spring Boot integration is a PropertySource decryptor for
 * {@code ENC(...)} placeholders in configuration, not a column cipher, so using it here would mean three
 * extra dependencies and still hand-rolling this class around its {@code StringEncryptor}.
 *
 * <p>Storage format is {@code v1:} + Base64(IV ‖ ciphertext ‖ tag). The version prefix costs three bytes
 * and buys a per-row migration path if the scheme ever changes. A fresh random IV is generated for every
 * encryption, which is what makes GCM safe — reusing an IV with the same key destroys the guarantee.
 */
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(EncryptionKeyProvider keyProvider) {
        this.key = new SecretKeySpec(keyProvider.getKey(), "AES");
    }

    /** @return null for null input, so callers can pass an absent password straight through. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the plaintext in the message.
            throw new IllegalStateException("Failed to encrypt a stored secret.", e);
        }
    }

    /**
     * @return null for null input.
     * @throws UndecryptableSecretException if the key no longer matches or the data was altered
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new UndecryptableSecretException(
                    "Stored secret is not in the expected format.", null);
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_BYTES) {
                throw new UndecryptableSecretException("Stored secret is truncated.", null);
            }

            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // The authentication tag failed: wrong key, or the ciphertext was modified.
            throw new UndecryptableSecretException(
                    "Stored password could not be decrypted with the current encryption key.", e);
        } catch (UndecryptableSecretException e) {
            throw e;
        } catch (Exception e) {
            throw new UndecryptableSecretException("Stored password could not be decrypted.", e);
        }
    }

    /** Tells the API whether a profile's stored password is still usable, without decrypting it aloud. */
    public boolean isReadable(String stored) {
        if (stored == null) {
            return true;
        }
        try {
            decrypt(stored);
            return true;
        } catch (UndecryptableSecretException e) {
            return false;
        }
    }
}
