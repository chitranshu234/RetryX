package dev.retryx.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** API key generation and hashing. */
class ApiKeyHasherTest {

    private final ApiKeyHasher hasher = new ApiKeyHasher();

    @Test
    @DisplayName("generates keys with the expected shape")
    void generatesPrefixedKeys() {
        String key = hasher.generateRawKey();

        assertThat(key).startsWith("rtx_sk_");
        // 24 random bytes, base64url without padding → 32 characters after the prefix.
        assertThat(key).hasSize("rtx_sk_".length() + 32);
        assertThat(hasher.looksLikeKey(key)).isTrue();
    }

    @Test
    @DisplayName("generates distinct keys")
    void keysAreUnique() {
        Set<String> keys =
                IntStream.range(0, 1000).mapToObj(i -> hasher.generateRawKey()).collect(Collectors.toSet());

        assertThat(keys).hasSize(1000);
    }

    @Test
    @DisplayName("hashes to 64 hex characters, deterministically")
    void hashIsStable() {
        String key = "rtx_sk_abcdefghijklmnopqrstuvwxyz012345";

        String hash = hasher.hash(key);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hasher.hash(key)).isEqualTo(hash);
    }

    @Test
    @DisplayName("different keys hash differently")
    void hashesDiffer() {
        assertThat(hasher.hash("rtx_sk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isNotEqualTo(hasher.hash("rtx_sk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab"));
    }

    @Test
    @DisplayName("the display prefix cannot be used as a key")
    void prefixIsNotUsable() {
        String key = hasher.generateRawKey();

        String prefix = hasher.displayPrefix(key);

        assertThat(prefix).hasSize(12);
        assertThat(key).startsWith(prefix);
        // Only four random characters are exposed. The remaining 20 bytes of entropy are not.
        assertThat(prefix).isNotEqualTo(key);
    }

    @Test
    @DisplayName("rejects malformed candidates before touching the database")
    void shapeCheck() {
        // The point of this check is that a junk header costs nothing: no hash, no query.
        assertThat(hasher.looksLikeKey(null)).isFalse();
        assertThat(hasher.looksLikeKey("")).isFalse();
        assertThat(hasher.looksLikeKey("not-a-key")).isFalse();
        assertThat(hasher.looksLikeKey("rtx_sk_short")).isFalse();
        assertThat(hasher.looksLikeKey("rtx_sk_" + "x".repeat(200))).isFalse();
        assertThat(hasher.looksLikeKey("Bearer rtx_sk_abcdefghijklmnop")).isFalse();
    }

    @Test
    @DisplayName("handles a short key when computing the display prefix")
    void shortKeyPrefix() {
        assertThat(hasher.displayPrefix("rtx_sk_a")).isEqualTo("rtx_sk_a");
    }
}
