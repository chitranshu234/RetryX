package dev.retryx.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs outbound webhook payloads with HMAC-SHA256, and verifies them (used by tests and by the
 * bundled receiver's reference implementation).
 *
 * <h2>The scheme</h2>
 *
 * <pre>
 *   signed string = "&lt;unix seconds&gt;" + "." + &lt;exact request body bytes&gt;
 *   signature     = hex( HMAC-SHA256( endpoint secret, signed string ) )
 *   header        = X-Retryx-Signature: t=&lt;unix seconds&gt;,v1=&lt;signature&gt;
 * </pre>
 *
 * <h2>Why each part is there</h2>
 *
 * <ul>
 *   <li><strong>The exact body bytes.</strong> Not a re-serialised object, not a parsed-then-printed
 *       JSON tree. If the signer and the sender disagree by one space, every signature is wrong, and
 *       the bug is invisible until a receiver rejects traffic in production. {@link #sign} takes a
 *       {@code byte[]} precisely so the caller is forced to hold on to the bytes it signed and send
 *       those.
 *   <li><strong>The timestamp, inside the MAC.</strong> Without it an attacker who captured one
 *       valid request can resend it forever. With it, the receiver enforces a freshness window
 *       (Retryx suggests 5 minutes) and old captures are rejected. The timestamp must be covered by
 *       the MAC — a timestamp sent alongside an unrelated signature is just a number an attacker can
 *       edit.
 *   <li><strong>The {@code v1=} label.</strong> Room to add a second algorithm later and let
 *       receivers accept both during the migration, rather than a flag day.
 *   <li><strong>A new timestamp per attempt.</strong> Retry 4 is a fresh request and gets a fresh
 *       {@code t}. The body stays byte-identical across retries, so a receiver can safely dedupe on
 *       {@code X-Retryx-Event-Id} or on a body hash.
 * </ul>
 *
 * <p>Verification uses {@link MessageDigest#isEqual} rather than {@code String.equals}, which short
 * circuits at the first differing byte and so leaks, through timing, how much of a forged signature
 * was right.
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** Prefix identifying the signature scheme version. */
    public static final String SCHEME_VERSION = "v1";

    /**
     * Computes the hex HMAC over {@code timestamp + "." + body}.
     *
     * @param secret    the endpoint's signing secret (decrypted)
     * @param timestamp unix seconds, to be sent in the same header
     * @param body      the exact bytes that will be written to the socket
     * @return lowercase hex signature
     */
    public String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable or key rejected", e);
        }
    }

    /**
     * Builds the full header value for a signed request.
     *
     * @param secret    the endpoint's signing secret (decrypted)
     * @param timestamp unix seconds
     * @param body      the exact bytes that will be sent
     * @return e.g. {@code t=1760000000,v1=9f86d08…}
     */
    public String signatureHeader(String secret, long timestamp, byte[] body) {
        return "t=" + timestamp + "," + SCHEME_VERSION + "=" + sign(secret, timestamp, body);
    }

    /**
     * Reference verification, as a receiver should implement it.
     *
     * @param secret           shared secret
     * @param headerValue      raw {@code X-Retryx-Signature} value
     * @param body             raw request body bytes, exactly as received
     * @param nowEpochSeconds  current time
     * @param toleranceSeconds how old a timestamp may be
     * @return true only if the timestamp is fresh and the MAC matches
     */
    public boolean verify(
            String secret, String headerValue, byte[] body, long nowEpochSeconds, long toleranceSeconds) {
        if (headerValue == null || headerValue.isBlank()) {
            return false;
        }

        Long timestamp = null;
        String provided = null;
        for (String part : headerValue.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            } else if (SCHEME_VERSION.equals(kv[0])) {
                provided = kv[1];
            }
        }
        if (timestamp == null || provided == null) {
            return false;
        }

        // Replay window. Both directions: a far-future timestamp is as suspicious as a stale one.
        if (Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) {
            return false;
        }

        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII));
    }
}
