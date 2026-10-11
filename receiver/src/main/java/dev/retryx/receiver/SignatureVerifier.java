package dev.retryx.receiver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies a Retryx webhook signature. <strong>This is the code you copy into your own
 * service.</strong>
 *
 * <p>Retryx sends:
 *
 * <pre>
 *   X-Retryx-Signature: t=1760000000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
 *   X-Retryx-Timestamp: 1760000000
 *   X-Retryx-Event-Id:  7a1c…
 * </pre>
 *
 * and the signature is computed over {@code "<t>" + "." + <raw body bytes>} with HMAC-SHA256 keyed
 * on the endpoint's signing secret.
 *
 * <h2>Three ways to get this wrong</h2>
 *
 * <ol>
 *   <li><strong>Verifying a re-serialised body.</strong> If your framework parses JSON into an
 *       object and you then re-serialise it to verify, the bytes are not the bytes that were signed —
 *       key order, whitespace and number formatting will differ — and nothing will ever verify. You
 *       must capture the <em>raw</em> body. In Spring MVC that means {@code @RequestBody byte[]}; in
 *       Express, {@code express.raw()}; in Flask, {@code request.get_data()}.
 *   <li><strong>Skipping the timestamp check.</strong> The MAC alone proves the message came from
 *       someone with the secret, not that it is recent. Without a freshness window, anyone who
 *       captured one request can replay it forever and every replay verifies perfectly.
 *   <li><strong>Comparing with {@code ==} or {@code equals}.</strong> Those return as soon as two
 *       bytes differ, so the time they take reveals how many leading characters of a guess were
 *       correct — enough to recover a valid signature byte by byte. Use a constant-time compare.
 * </ol>
 */
@Component
public class SignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final String secret;
    private final long toleranceSeconds;

    /**
     * @param secret           shared signing secret for this endpoint
     * @param toleranceSeconds how old a timestamp may be before it is rejected
     */
    public SignatureVerifier(
            @Value("${receiver.signing-secret:}") String secret,
            @Value("${receiver.signature-tolerance-seconds:300}") long toleranceSeconds) {
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
    }

    /** Why a signature was rejected, or that it was accepted. */
    public enum Result {
        /** Signature valid and timestamp fresh. */
        VALID,
        /** No secret configured on this receiver, so nothing was checked. */
        NOT_CONFIGURED,
        /** Header absent or unparseable. */
        MALFORMED,
        /** Timestamp outside the tolerance window — a possible replay. */
        STALE,
        /** HMAC did not match. Wrong secret, or tampering. */
        MISMATCH
    }

    /**
     * @param signatureHeader raw {@code X-Retryx-Signature} value
     * @param body            the exact bytes received, not re-serialised
     * @return the verification result
     */
    public Result verify(String signatureHeader, byte[] body) {
        if (secret == null || secret.isBlank()) {
            return Result.NOT_CONFIGURED;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return Result.MALFORMED;
        }

        Long timestamp = null;
        String provided = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(kv[1]);
                    } catch (NumberFormatException e) {
                        return Result.MALFORMED;
                    }
                }
                case "v1" -> provided = kv[1];
                default -> {
                    // Unknown scheme version. Ignore it rather than fail, so that a future v2
                    // signature sent alongside v1 does not break this receiver.
                }
            }
        }
        if (timestamp == null || provided == null) {
            return Result.MALFORMED;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > toleranceSeconds) {
            return Result.STALE;
        }

        String expected = computeSignature(timestamp, body);

        // Constant-time: compares every byte regardless of where the first difference is.
        boolean matches =
                MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        provided.getBytes(StandardCharsets.US_ASCII));

        return matches ? Result.VALID : Result.MISMATCH;
    }

    /**
     * @param timestamp unix seconds from the header
     * @param body      raw request body
     * @return lowercase hex HMAC-SHA256 over {@code timestamp + "." + body}
     */
    private String computeSignature(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
