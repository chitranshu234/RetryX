package dev.retryx.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Generates API keys and turns them into the hash we store.
 *
 * <p>Keys look like {@code rtx_sk_<32 url-safe base64 chars>} — 24 bytes of
 * {@link SecureRandom}, so 192 bits of entropy.
 *
 * <p><strong>Why SHA-256 and not bcrypt?</strong> A slow KDF buys you time against someone
 * brute-forcing a <em>guessable</em> secret: users pick "hunter2", so you make each guess cost
 * 100ms. Nobody guesses 192 random bits, at any cost per guess. What a slow hash would buy here is
 * 100ms of CPU on the authentication path of every single API request — a denial of service you
 * built yourself. SHA-256 of a high-entropy random key is the correct primitive. (The property that
 * matters is still preserved: a stolen database gives an attacker hashes, not usable keys.)
 */
@Component
public class ApiKeyHasher {

    /** Marks a secret key, as opposed to a future publishable key. */
    public static final String KEY_PREFIX = "rtx_sk_";

    /** Characters of the raw key kept in clear for display, including the prefix. */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final int RANDOM_BYTES = 24;

    private final SecureRandom random = new SecureRandom();

    /**
     * Mints a new key. The return value is the only time the raw key exists; the caller must show
     * it to the user and then forget it.
     *
     * @return a fresh raw API key, e.g. {@code rtx_sk_Zm9vYmFyYmF6…}
     */
    public String generateRawKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hashes a raw key for storage and lookup.
     *
     * @param rawKey the key as presented by the client
     * @return lowercase hex SHA-256
     */
    public String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM; if it is missing, nothing here can work.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The part of a raw key that is safe to store and display.
     *
     * @param rawKey the key as presented by the client
     * @return the leading characters, e.g. {@code rtx_sk_Zm9v}
     */
    public String displayPrefix(String rawKey) {
        return rawKey.length() <= DISPLAY_PREFIX_LENGTH
                ? rawKey
                : rawKey.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /**
     * Cheap shape check, so a malformed header never reaches the database.
     *
     * @param candidate value from the request header
     * @return true if it could plausibly be one of our keys
     */
    public boolean looksLikeKey(String candidate) {
        return candidate != null
                && candidate.startsWith(KEY_PREFIX)
                && candidate.length() >= KEY_PREFIX.length() + 16
                && candidate.length() <= 128;
    }
}
