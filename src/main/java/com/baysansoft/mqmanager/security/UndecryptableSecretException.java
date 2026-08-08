package com.baysansoft.mqmanager.security;

/**
 * A stored password cannot be opened with the current key — typically because the key file was lost,
 * rotated, or the ciphertext was tampered with.
 *
 * <p>This is deliberately recoverable rather than fatal. The application still starts and still lists
 * every profile; only the operations that actually need the password fail, so the user has a working UI
 * in which to re-enter it.
 */
public class UndecryptableSecretException extends RuntimeException {

    public UndecryptableSecretException(String message, Throwable cause) {
        super(message, cause);
    }
}
