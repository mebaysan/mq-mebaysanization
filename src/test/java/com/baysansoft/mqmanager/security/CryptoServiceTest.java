package com.baysansoft.mqmanager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.baysansoft.mqmanager.config.MqManagerProperties;

class CryptoServiceTest {

    private static MqManagerProperties propertiesFor(Path dataDir, String base64Key) {
        MqManagerProperties properties = new MqManagerProperties();
        properties.setDataDir(dataDir);
        properties.setEncryptionKey(base64Key);
        return properties;
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static CryptoService serviceWith(Path dataDir, String base64Key) {
        return new CryptoService(new EncryptionKeyProvider(propertiesFor(dataDir, base64Key)));
    }

    @Test
    void roundTripsAPassword(@TempDir Path dataDir) {
        CryptoService crypto = serviceWith(dataDir, randomKey());

        String cipher = crypto.encrypt("s3cr3t-p4ss");

        assertThat(cipher).startsWith("v1:").doesNotContain("s3cr3t-p4ss");
        assertThat(crypto.decrypt(cipher)).isEqualTo("s3cr3t-p4ss");
    }

    @Test
    void passesNullStraightThrough(@TempDir Path dataDir) {
        CryptoService crypto = serviceWith(dataDir, randomKey());

        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("the same plaintext encrypts differently every time (fresh IV per call)")
    void usesAFreshIvEachTime(@TempDir Path dataDir) {
        CryptoService crypto = serviceWith(dataDir, randomKey());

        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    @DisplayName("a tampered ciphertext is rejected rather than silently returning garbage")
    void detectsTampering(@TempDir Path dataDir) {
        CryptoService crypto = serviceWith(dataDir, randomKey());
        String cipher = crypto.encrypt("original");

        // Flip one character of the Base64 payload.
        char[] chars = cipher.toCharArray();
        int last = chars.length - 2;
        chars[last] = chars[last] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> crypto.decrypt(new String(chars)))
                .isInstanceOf(UndecryptableSecretException.class);
    }

    @Test
    @DisplayName("ciphertext from a different key fails cleanly instead of crashing the app")
    void rejectsCiphertextFromAnotherKey(@TempDir Path dataDir, @TempDir Path otherDir) {
        String cipher = serviceWith(dataDir, randomKey()).encrypt("original");
        CryptoService otherKeyService = serviceWith(otherDir, randomKey());

        assertThatThrownBy(() -> otherKeyService.decrypt(cipher))
                .isInstanceOf(UndecryptableSecretException.class);
        assertThat(otherKeyService.isReadable(cipher)).isFalse();
    }

    @Test
    void reportsReadabilityWithoutThrowing(@TempDir Path dataDir) {
        CryptoService crypto = serviceWith(dataDir, randomKey());

        assertThat(crypto.isReadable(crypto.encrypt("fine"))).isTrue();
        assertThat(crypto.isReadable(null)).isTrue();
        assertThat(crypto.isReadable("not-even-versioned")).isFalse();
    }

    @Test
    @DisplayName("with no configured key one is generated, owner-readable only, and reused on restart")
    void generatesAndReusesAKeyFile(@TempDir Path dataDir) throws Exception {
        EncryptionKeyProvider first = new EncryptionKeyProvider(propertiesFor(dataDir, ""));
        Path keyFile = dataDir.resolve(EncryptionKeyProvider.KEY_FILE_NAME);

        assertThat(first.getSource()).isEqualTo(EncryptionKeyProvider.Source.GENERATED);
        assertThat(keyFile).exists();

        if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
                    .isEqualTo("rw-------");
        }

        // A restart must find the same key, or every stored password becomes unreadable.
        EncryptionKeyProvider second = new EncryptionKeyProvider(propertiesFor(dataDir, ""));
        assertThat(second.getSource()).isEqualTo(EncryptionKeyProvider.Source.KEY_FILE);
        assertThat(second.getKey()).isEqualTo(first.getKey());
    }

    @Test
    void rejectsAKeyOfTheWrongLength(@TempDir Path dataDir) {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EncryptionKeyProvider(propertiesFor(dataDir, tooShort)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void createsTheDataDirectoryIfMissing(@TempDir Path parent) {
        Path missing = parent.resolve("nested").resolve("data");

        new EncryptionKeyProvider(propertiesFor(missing, randomKey()));

        assertThat(missing).isDirectory();
    }
}
