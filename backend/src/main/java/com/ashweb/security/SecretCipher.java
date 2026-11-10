package com.ashweb.security;

import com.ashweb.config.AshWebProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Encrypts the passwords of monitored databases with AES-256-GCM.
 * The key lives in a file outside the storage database so a database dump alone does not reveal them.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AshWebProperties properties) throws IOException {
        this.key = new SecretKeySpec(loadOrCreateKey(Path.of(properties.security().masterKeyFile())), "AES");
    }

    private byte[] loadOrCreateKey(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] bytes = Base64.getDecoder().decode(Files.readString(file).trim());
            if (bytes.length != 32) {
                throw new IllegalStateException("Master key file " + file + " must hold a base64 encoded 256-bit key");
            }
            return bytes;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        Path absolute = file.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.writeString(absolute, Base64.getEncoder().encodeToString(bytes));
        try {
            Files.setPosixFilePermissions(absolute, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system (Windows): rely on directory permissions
        }
        log.warn("Created a new master key at {}. Back it up: without it stored database passwords cannot be read.",
                absolute);
        return bytes;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Could not decrypt a stored password. Was the master key file replaced?", e);
        }
    }
}
