package dev.retryx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.retryx.config.RetryxProperties;
import java.util.Base64;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AES-GCM encryption of endpoint signing secrets. */
class AesGcmSecretCipherTest {

    private static AesGcmSecretCipher cipher(String base64Key) {
        RetryxProperties properties =
                new RetryxProperties(
                        null, null, null, null, null, new RetryxProperties.Crypto(base64Key), null, null, null);
        return new AesGcmSecretCipher(properties);
    }

    private static String key256() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private final AesGcmSecretCipher cipher = cipher(key256());

    @Test
    @DisplayName("round-trips a secret")
    void roundTrip() {
        String secret = "whsec_abcdefghijklmnopqrstuvwxyz";

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    @DisplayName("produces a versioned three-part envelope")
    void envelopeFormat() {
        String envelope = cipher.encrypt("whsec_test");

        String[] parts = envelope.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("v1");
        // 12-byte nonce, base64url without padding → 16 characters.
        assertThat(parts[1]).hasSize(16);
    }

    @Test
    @DisplayName("uses a fresh nonce every time")
    void uniqueNoncePerEncryption() {
        // The single most important property of a GCM implementation. Reusing a nonce under one key
        // does not weaken GCM, it breaks it: two messages under one nonce leak their XOR and allow
        // recovery of the authentication subkey, after which tags can be forged at will.
        Set<String> nonces =
                IntStream.range(0, 500)
                        .mapToObj(i -> cipher.encrypt("same plaintext every time").split("\\.")[1])
                        .collect(java.util.stream.Collectors.toSet());

        assertThat(nonces).hasSize(500);
    }

    @Test
    @DisplayName("the same plaintext encrypts to different ciphertext")
    void notDeterministic() {
        // Deterministic encryption would let anyone with database access see which endpoints share
        // a secret, without decrypting anything.
        assertThat(cipher.encrypt("whsec_same")).isNotEqualTo(cipher.encrypt("whsec_same"));
    }

    @Test
    @DisplayName("refuses a tampered ciphertext instead of returning garbage")
    void detectsTampering() {
        String envelope = cipher.encrypt("whsec_original");
        String[] parts = envelope.split("\\.");

        // Flip one character of the ciphertext. This is the attack GCM's tag exists to stop; with
        // unauthenticated CBC it would decrypt to attacker-influenced plaintext.
        char[] ciphertext = parts[2].toCharArray();
        ciphertext[0] = ciphertext[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(ciphertext);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to decrypt secret");
    }

    @Test
    @DisplayName("refuses a ciphertext encrypted under a different key")
    void wrongKey() {
        byte[] otherRaw = new byte[32];
        otherRaw[0] = 7;
        AesGcmSecretCipher other = cipher(Base64.getEncoder().encodeToString(otherRaw));

        String envelope = cipher.encrypt("whsec_original");

        assertThatThrownBy(() -> other.decrypt(envelope)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects an unrecognised envelope")
    void malformedEnvelope() {
        assertThatThrownBy(() -> cipher.decrypt("not-an-envelope"))
                .hasMessage("unrecognised secret envelope format");
        assertThatThrownBy(() -> cipher.decrypt("v2.aaaa.bbbb"))
                .hasMessage("unrecognised secret envelope format");
    }

    @Test
    @DisplayName("fails fast on a key that is not a valid AES length")
    void rejectsBadKeyLength() {
        // Starting with a 7-byte key and discovering it at the first encryption is worse than not
        // starting at all.
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[7])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16, 24 or 32 bytes");
    }

    @Test
    @DisplayName("fails fast on a missing or non-base64 key")
    void rejectsMissingKey() {
        assertThatThrownBy(() -> cipher("")).hasMessageContaining("required");
        assertThatThrownBy(() -> cipher("!!! not base64 !!!")).hasMessageContaining("base64");
    }

    @Test
    @DisplayName("accepts 128- and 192-bit keys as well as 256")
    void acceptsAllAesLengths() {
        assertThat(cipher(Base64.getEncoder().encodeToString(new byte[16]))).isNotNull();
        assertThat(cipher(Base64.getEncoder().encodeToString(new byte[24]))).isNotNull();
    }

    @Test
    @DisplayName("generates signing secrets that are long and distinct")
    void generatesSigningSecrets() {
        Set<String> secrets =
                IntStream.range(0, 200)
                        .mapToObj(i -> cipher.generateSigningSecret())
                        .collect(java.util.stream.Collectors.toSet());

        assertThat(secrets).hasSize(200);
        assertThat(secrets).allSatisfy(secret -> assertThat(secret).startsWith("whsec_").hasSizeGreaterThan(40));
    }

    @Test
    @DisplayName("the preview cannot be used as a secret")
    void previewIsTruncated() {
        String secret = cipher.generateSigningSecret();

        String preview = cipher.preview(secret);

        assertThat(preview).hasSizeLessThan(secret.length());
        assertThat(preview).endsWith("…");
    }

    @Test
    @DisplayName("handles unicode and long plaintext")
    void unicodeAndLength() {
        String awkward = "whsec_→ñ日本語_" + "x".repeat(150);

        assertThat(cipher.decrypt(cipher.encrypt(awkward))).isEqualTo(awkward);
    }
}
