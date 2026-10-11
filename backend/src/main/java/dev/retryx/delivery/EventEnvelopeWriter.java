package dev.retryx.delivery;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import dev.retryx.queue.DeliveryJob;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Builds the request body sent to a receiver.
 *
 * <p>Shape:
 *
 * <pre>
 *   {"id":"&lt;event uuid&gt;","type":"order.created","created_at":"2026-10-11T09:14:02Z","data":{…}}
 * </pre>
 *
 * <h2>Two properties this class exists to guarantee</h2>
 *
 * <p><strong>1. The body is byte-identical across every retry of a delivery.</strong> Only the
 * headers change between attempts — the attempt number and the timestamp live there, deliberately,
 * and not in the body. That means a receiver can deduplicate on a hash of the body, or compare two
 * suspicious requests byte for byte, and it means a replay six months later reproduces exactly what
 * was sent the first time. Putting {@code attempt} in the body would quietly destroy all of that.
 *
 * <p><strong>2. The payload is never parsed.</strong> The producer's JSON comes out of
 * {@code jsonb} as text and is spliced in as raw JSON, not deserialised into a tree and
 * re-serialised. Round-tripping through a parser is where byte-stability goes to die: key order,
 * number formatting ({@code 1.0} vs {@code 1}), unicode escaping and whitespace are all at the
 * mercy of the serialiser's configuration, and a signature computed over the "same" JSON after a
 * round trip is a different signature. Not parsing is also simply faster — no tree allocation per
 * attempt, on the hot path of every delivery.
 *
 * <p>Postgres normalises {@code jsonb} on write (it is a parsed binary form), so the text that
 * comes back is already canonical and stable for the life of the row. That normalisation happening
 * <em>once, at ingest</em> is exactly what makes splicing safe.
 */
@Component
public class EventEnvelopeWriter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

    /**
     * @param job the delivery about to be attempted
     * @return the exact bytes to sign and send
     */
    public byte[] write(DeliveryJob job) {
        String createdAt = TIMESTAMP_FORMAT.format(job.eventCreatedAt().toInstant());

        // Hand-built so the payload text passes through untouched. Only the fields Retryx controls
        // need escaping, and event_type is the one that could contain anything.
        StringBuilder json =
                new StringBuilder(job.payloadJson().length() + 160)
                        .append("{\"id\":\"")
                        .append(job.eventId())
                        .append("\",\"type\":\"")
                        .append(escape(job.eventType()))
                        .append("\",\"created_at\":\"")
                        .append(createdAt)
                        .append("\",\"data\":")
                        .append(job.payloadJson())
                        .append('}');

        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * JSON-escapes a string value.
     *
     * <p>Uses Jackson's own encoder rather than hand-rolled replacement, because the set of
     * characters that must be escaped in JSON is larger and stranger than it looks — control
     * characters, {@code U+2028}/{@code U+2029}, lone surrogates — and getting it wrong produces a
     * body that parses on your machine and not on the receiver's.
     *
     * @param value raw string
     * @return the same string, safe to place between double quotes
     */
    private String escape(String value) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }
}
