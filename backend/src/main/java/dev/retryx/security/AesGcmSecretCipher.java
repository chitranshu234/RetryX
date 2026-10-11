package dev.retryx.security;

import dev.retryx.config.RetryxProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts endpoint signing secrets at rest with AES-GCM.
 *
 * <p>Envelope format, so the scheme can change without a migration:
 *
 * <pre>
 *   v1.&lt;base64url 12-byte nonce&gt;.&lt;base64url ciphertext||tag&gt;
 * </pre>
 *
 * <h2>Why GCM</h2>
 *
 * GCM is authenticated encryption: the 128-bit tag means a tampered ciphertext fails to decrypt
 * instead of yielding attacker-influenced plaintext. With plain CBC, someone with write access to
 * the database could flip bits in a stored secret and watch what happens. Here they get an
 * {@code AEADBadTagException}.
 *
 * <h2>The nonce rule</h2>
 *
 * A fresh 12-byte {@link SecureRandom} nonce per encryption, stored next to the ciphertext.
 * <strong>Reusing a nonce under the same key breaks GCM catastrophically</strong> — not "weakens",
 * breaks: two messages under one nonce leak their XOR and, worse, allow recovery of the
 * authentication subkey, after which an attacker can forge tags at will. So the nonce is never
 * derived from the record, never a counter in application code, never zero.
 *
 * <h2>What this does and does not protect</h2>
 *
 * The key comes from {@code RETRYX_ENCRYPTION_KEY}, i.e. from outside the database. That is the
 * entire point: it defends against a stolen dump, a leaked backup, or a read-only SQL injection. It
 * does <em>not</em> defend against someone who already has the running process's environment —
 * that would need a KMS or an HSM, which is listed as future work.
 */
@Component
public class AesGcmSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String VERSION = "v1";

    /** Public, well-known development key. Refused when the prod profile is active. */
    public static final String INSECURE_DEV_KEY = "ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZCE=";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    /**
     * @param properties supplies the base64 key
     * @throws IllegalStateException if the key is absent or not a valid AES length
     */
    public AesGcmSecretCipher(RetryxProperties properties) {
        String configured = properties.crypto().encryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("RETRYX_ENCRYPTION_KEY is required");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RETRYX_ENCRYPTION_KEY must be base64-encoded", e);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "RETRYX_ENCRYPTION_KEY must decode to 16, 24 or 32 bytes (got " + raw.length + ")");
        }
        this.key = new SecretKeySpec(raw, ALGORITHM);
    }

    /**
     * @param plaintext secret to protect
     * @return the versioned envelope string, safe to store in a text column
     */
    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return VERSION + "." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encrypt secret", e);
        }
    }

    /**
     * @param envelope value produced by {@link #encrypt}
     * @return the original plaintext
     * @throws IllegalStateException if the envelope is malformed, or the tag does not verify
     *     (wrong key, or tampering)
     */
    public String decrypt(String envelope) {
        String[] parts = envelope.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("unrecognised secret envelope format");
        }
        try {
            byte[] nonce = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Deliberately vague: never report whether the padding, the tag or the key was at fault.
            throw new IllegalStateException("failed to decrypt secret", e);
        }
    }

    /**
     * Mints a signing secret for a new endpoint.
     *
     * @return e.g. {@code whsec_…}, 32 random bytes, url-safe
     */
    public String generateSigningSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "whsec_" + encoder.encodeToString(bytes);
    }

    /**
     * Display hint for the UI, derived from the plaintext at creation time.
     *
     * @param secret the plaintext signing secret
     * @return e.g. {@code whsec_1f4c…}
     */
    public String preview(String secret) {
        int cut = Math.min(secret.length(), 12);
        return secret.substring(0, cut) + "…";
    }
}
