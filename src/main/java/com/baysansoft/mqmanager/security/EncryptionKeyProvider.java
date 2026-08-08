package com.baysansoft.mqmanager.security;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;

/**
 * Resolves the AES key used to encrypt stored broker passwords.
 *
 * <p>Order: the {@code MQMANAGER_ENCRYPTION_KEY} environment variable, then
 * {@code <data-dir>/encryption.key}, then generate a new key and write it there.
 */
@Component
public class EncryptionKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyProvider.class);

    static final String KEY_FILE_NAME = "encryption.key";
    private static final int KEY_BYTES = 32; // AES-256

    private final byte[] key;
    private final Source source;

    public enum Source {
        ENVIRONMENT, KEY_FILE, GENERATED
    }

    public EncryptionKeyProvider(MqManagerProperties properties) {
        Path dataDir = properties.getDataDir().toAbsolutePath().normalize();
        try {
            // Done here, not left to H2: this bean writes the key file during startup, which can happen
            // before the DataSource is ever touched. Bean creation order between the two is not
            // guaranteed either way, so this class never relies on someone else creating the directory.
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory " + dataDir, e);
        }

        String configured = properties.getEncryptionKey();
        if (StringUtils.hasText(configured)) {
            this.key = decodeKey(configured.trim(), "MQMANAGER_ENCRYPTION_KEY");
            this.source = Source.ENVIRONMENT;
            log.info("Encryption key loaded from MQMANAGER_ENCRYPTION_KEY.");
            return;
        }

        Path keyFile = dataDir.resolve(KEY_FILE_NAME);
        if (Files.exists(keyFile)) {
            try {
                this.key = decodeKey(Files.readString(keyFile, StandardCharsets.UTF_8).trim(),
                        keyFile.toString());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read encryption key file " + keyFile, e);
            }
            this.source = Source.KEY_FILE;
            log.info("Encryption key loaded from {}.", keyFile);
            return;
        }

        this.key = generateAndStore(keyFile);
        this.source = Source.GENERATED;
    }

    public byte[] getKey() {
        return key.clone();
    }

    public Source getSource() {
        return source;
    }

    private static byte[] decodeKey(String base64, String origin) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Encryption key from " + origin + " is not valid Base64.", e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("Encryption key from " + origin + " must decode to "
                    + KEY_BYTES + " bytes (got " + decoded.length + ").");
        }
        return decoded;
    }

    private static byte[] generateAndStore(Path keyFile) {
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = Base64.getEncoder().encodeToString(generated);

        try {
            writeWithRestrictedPermissions(keyFile, encoded);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write encryption key file " + keyFile, e);
        }

        log.warn("""
                Generated a new encryption key at {}.
                BACK THIS FILE UP. Without it, stored broker passwords cannot be decrypted and every \
                connection profile will need its password re-entered. To supply the key yourself \
                instead, set MQMANAGER_ENCRYPTION_KEY to a Base64-encoded 32-byte value.""", keyFile);

        return generated;
    }

    /**
     * Writes the key owner-readable only.
     *
     * <p>The POSIX support is probed rather than assumed: on Windows/NTFS
     * {@code Files.setPosixFilePermissions} throws an <em>unchecked</em>
     * {@link UnsupportedOperationException}, which would abort startup with no compile-time warning.
     * The failure is also never swallowed with a blanket {@code catch (Exception)} — that would hide a
     * real {@link IOException} and leave a world-readable key sitting on disk.
     */
    private static void writeWithRestrictedPermissions(Path keyFile, String contents) throws IOException {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);

        if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(keyFile,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(keyFile, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }

        // Windows and anything else without POSIX views.
        Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        File file = keyFile.toFile();
        boolean restricted = file.setReadable(false, false)
                & file.setWritable(false, false)
                & file.setReadable(true, true)
                & file.setWritable(true, true);
        if (!restricted) {
            log.warn("Could not restrict permissions on {}. On this filesystem the key file's "
                    + "protection depends on the directory's inherited ACLs — verify it is not "
                    + "readable by other users.", keyFile);
        }
    }
}
