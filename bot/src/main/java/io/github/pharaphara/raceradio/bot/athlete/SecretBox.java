package io.github.pharaphara.raceradio.bot.athlete;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seals the keys athletes give at check-in, so that the database alone never yields one: AES-256
 * in GCM mode, a fresh 12-byte nonce each time, under a master key that lives only in the service's
 * environment ({@code RACE_RADIO_SECRET_KEY}, 32 random bytes in base64, made with
 * {@code openssl rand -base64 32}). A sealed value reads {@code v1:} then base64 of nonce and
 * ciphertext; tampering with it fails the authentication tag.
 */
public final class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private SecretBox(byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    /**
     * @throws IllegalArgumentException when the master key is not 32 bytes of base64
     */
    public static SecretBox of(String base64Key) {
        byte[] k;
        try {
            k = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RACE_RADIO_SECRET_KEY is not base64: make one with openssl rand -base64 32");
        }
        if (k.length != 32) {
            throw new IllegalArgumentException("RACE_RADIO_SECRET_KEY must be 32 bytes, it is " + k.length
                    + ": make one with openssl rand -base64 32");
        }
        return new SecretBox(k);
    }

    public String seal(String secret) {
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sealing failed", e);
        }
    }

    /**
     * @throws IllegalStateException when the value was sealed under another key or was altered
     */
    public String open(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalStateException("not a sealed value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE));
            return new String(c.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("a sealed key could not be opened: another master key, or an altered value");
        }
    }
}
