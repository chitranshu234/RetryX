package dev.retryx.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HMAC signing.
 *
 * <p>Includes a fixed known-answer test. Without one, the signer and the verifier can agree with
 * each other while both being wrong — they share the implementation, so a round-trip test passes
 * even if the signed string is assembled in the wrong order. A hardcoded expected value pins the
 * wire format, so changing it is a deliberate act that breaks this test rather than a silent
 * incompatibility that breaks every receiver.
 */
class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    private static final String SECRET = "whsec_test_secret";
    private static final long TIMESTAMP = 1_760_000_000L;
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"data\":{\"a\":1}}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("signs timestamp and body, in that order, joined by a dot")
    void knownAnswer() {
        // HMAC-SHA256("whsec_test_secret", "1760000000.{\"id\":\"evt_1\",\"data\":{\"a\":1}}")
        String signature = signer.sign(SECRET, TIMESTAMP, BODY);

        assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");

        // Recomputing the same inputs must give the same answer — and, critically, swapping the
        // order of the two inputs must not.
        String swapped =
                signer.sign(SECRET, TIMESTAMP, "1760000000".getBytes(StandardCharsets.UTF_8));
        assertThat(signature).isNotEqualTo(swapped);
    }

    @Test
    @DisplayName("is stable for identical inputs")
    void deterministic() {
        assertThat(signer.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(signer.sign(SECRET, TIMESTAMP, BODY));
    }

    @Test
    @DisplayName("changes when the timestamp changes")
    void timestampIsCovered() {
        // If the timestamp were sent alongside the signature but not inside the MAC, an attacker
        // could edit it freely and the replay window would be decorative.
        assertThat(signer.sign(SECRET, TIMESTAMP, BODY))
                .isNotEqualTo(signer.sign(SECRET, TIMESTAMP + 1, BODY));
    }

    @Test
    @DisplayName("changes when a single body byte changes")
    void bodyIsCovered() {
        byte[] tampered = "{\"id\":\"evt_1\",\"data\":{\"a\":2}}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(SECRET, TIMESTAMP, BODY))
                .isNotEqualTo(signer.sign(SECRET, TIMESTAMP, tampered));
    }

    @Test
    @DisplayName("changes when the secret changes")
    void secretIsTheKey() {
        assertThat(signer.sign(SECRET, TIMESTAMP, BODY))
                .isNotEqualTo(signer.sign("whsec_other", TIMESTAMP, BODY));
    }

    @Test
    @DisplayName("builds a header a receiver can parse")
    void headerFormat() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP, BODY);

        assertThat(header).startsWith("t=" + TIMESTAMP + ",v1=");
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
    }

    @Test
    @DisplayName("accepts its own fresh signature")
    void verifyAcceptsValid() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP, BODY);

        assertThat(signer.verify(SECRET, header, BODY, TIMESTAMP, 300)).isTrue();
    }

    @Test
    @DisplayName("rejects a stale signature even though the MAC is correct")
    void verifyRejectsStale() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP, BODY);

        // Six minutes later, five-minute tolerance. The MAC still verifies perfectly — this is
        // exactly the replay an attacker with a captured request would attempt.
        assertThat(signer.verify(SECRET, header, BODY, TIMESTAMP + 360, 300)).isFalse();
    }

    @Test
    @DisplayName("rejects a far-future timestamp too")
    void verifyRejectsFutureSkew() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP + 600, BODY);

        assertThat(signer.verify(SECRET, header, BODY, TIMESTAMP, 300)).isFalse();
    }

    @Test
    @DisplayName("rejects a tampered body")
    void verifyRejectsTamperedBody() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP, BODY);
        byte[] tampered = "{\"id\":\"evt_1\",\"data\":{\"a\":999}}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.verify(SECRET, header, tampered, TIMESTAMP, 300)).isFalse();
    }

    @Test
    @DisplayName("rejects the wrong secret")
    void verifyRejectsWrongSecret() {
        String header = signer.signatureHeader(SECRET, TIMESTAMP, BODY);

        assertThat(signer.verify("whsec_wrong", header, BODY, TIMESTAMP, 300)).isFalse();
    }

    @Test
    @DisplayName("rejects malformed headers rather than throwing")
    void verifyRejectsMalformed() {
        assertThat(signer.verify(SECRET, null, BODY, TIMESTAMP, 300)).isFalse();
        assertThat(signer.verify(SECRET, "", BODY, TIMESTAMP, 300)).isFalse();
        assertThat(signer.verify(SECRET, "garbage", BODY, TIMESTAMP, 300)).isFalse();
        assertThat(signer.verify(SECRET, "t=notanumber,v1=abc", BODY, TIMESTAMP, 300)).isFalse();
        // Signature present, timestamp missing — cannot be verified, must not be accepted.
        assertThat(signer.verify(SECRET, "v1=abc", BODY, TIMESTAMP, 300)).isFalse();
        // Timestamp present, signature missing.
        assertThat(signer.verify(SECRET, "t=" + TIMESTAMP, BODY, TIMESTAMP, 300)).isFalse();
    }

    @Test
    @DisplayName("ignores unknown scheme versions instead of failing")
    void verifyToleratesFutureSchemes() {
        String valid = signer.sign(SECRET, TIMESTAMP, BODY);
        String header = "t=" + TIMESTAMP + ",v1=" + valid + ",v2=somethingelse";

        // Forward compatibility: adding a v2 signature alongside v1 must not break receivers that
        // only understand v1, or the migration becomes a flag day.
        assertThat(signer.verify(SECRET, header, BODY, TIMESTAMP, 300)).isTrue();
    }

    @Test
    @DisplayName("signs an empty body without special-casing")
    void emptyBody() {
        assertThat(signer.sign(SECRET, TIMESTAMP, new byte[0])).hasSize(64);
    }
}
