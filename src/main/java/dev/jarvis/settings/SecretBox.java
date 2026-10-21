package dev.jarvis.settings;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts secret settings with AES-256-GCM. The setting name is authenticated data: a value copied under
 * another name does not decrypt. Format: {@code v1:} + Base64(IV ‖ ciphertext ‖ tag). Pure logic.
 */
public final class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretBox(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("The master key must be " + KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    public static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    public String encrypt(String name, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt setting " + name, e);
        }
    }

    /** @throws IllegalStateException if the value was not sealed with this key and name */
    public String decrypt(String name, String sealedValue) {
        if (sealedValue == null || !sealedValue.startsWith(PREFIX)) {
            throw new IllegalStateException("Setting " + name + " is not an encrypted value");
        }
        try {
            byte[] data = Base64.getDecoder().decode(sealedValue.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt setting " + name
                    + ": wrong master key or tampered value", e);
        }
    }
}
