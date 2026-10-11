package dev.retryx.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retryx.queue.DeliveryJob;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Envelope construction.
 *
 * <p>The property under test is <strong>byte-stability</strong>. Everything about the signing scheme
 * depends on the same bytes being produced for the same delivery, every attempt, forever — and the
 * easiest way to break that is to parse and re-serialise the payload somewhere in the middle.
 */
class EventEnvelopeWriterTest {

    private final EventEnvelopeWriter writer = new EventEnvelopeWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    private static DeliveryJob job(String eventType, String payloadJson, int attemptCount) {
        return new DeliveryJob(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "acme",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Orders",
                eventType,
                payloadJson,
                OffsetDateTime.of(2026, 10, 11, 9, 14, 2, 0, ZoneOffset.UTC),
                "https://example.com/hook",
                "v1.nonce.ciphertext",
                attemptCount,
                6);
    }

    @Test
    @DisplayName("wraps the payload in an envelope with id, type and created_at")
    void envelopeShape() throws Exception {
        byte[] body = writer.write(job("order.created", "{\"orderId\": \"A-1\"}", 0));

        var parsed = mapper.readTree(body);

        assertThat(parsed.get("id").asText()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(parsed.get("type").asText()).isEqualTo("order.created");
        assertThat(parsed.get("created_at").asText()).isEqualTo("2026-10-11T09:14:02Z");
        assertThat(parsed.get("data").get("orderId").asText()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("is byte-identical across attempts of the same delivery")
    void stableAcrossAttempts() {
        // The attempt number lives in a header, not the body, precisely so this holds. If it were
        // in the body, every retry would have different bytes and a receiver could not dedupe on a
        // body hash, nor could a replay reproduce the original request.
        byte[] firstAttempt = writer.write(job("order.created", "{\"a\":1}", 0));
        byte[] sixthAttempt = writer.write(job("order.created", "{\"a\":1}", 5));

        assertThat(firstAttempt).isEqualTo(sixthAttempt);
    }

    @Test
    @DisplayName("passes the payload through without reformatting it")
    void payloadIsNotReserialised() {
        // Postgres normalises jsonb on write, so the text coming out of the column is already
        // canonical. Splicing it in verbatim means that canonical form is what gets signed and
        // sent. A parse/print round trip here could change number formatting, key order or unicode
        // escaping — and the signature with it.
        String payload = "{\"z\":1,\"a\":2,\"n\":1.50,\"deep\":{\"x\":[1,2,3]}}";

        String body = new String(writer.write(job("t", payload, 0)), StandardCharsets.UTF_8);

        assertThat(body).contains("\"data\":" + payload);
    }

    @Test
    @DisplayName("escapes an event type that contains JSON metacharacters")
    void escapesEventType() throws Exception {
        // eventType is validated at the API boundary, but the envelope must not rely on that:
        // defence in depth, and a thrown parse error at the receiver is a terrible way to find out.
        byte[] body = writer.write(job("order\"created\\x", "{\"a\":1}", 0));

        assertThat(mapper.readTree(body).get("type").asText()).isEqualTo("order\"created\\x");
    }

    @Test
    @DisplayName("escapes control characters and unicode correctly")
    void escapesAwkwardCharacters() throws Exception {
        byte[] body = writer.write(job("tab\there and\u0001", "{\"a\":1}", 0));

        // The point is that it still parses. Hand-rolled escaping routinely misses U+2028 and the
        // C0 controls, producing a body that parses in one language and not another.
        assertThat(mapper.readTree(body).get("type").asText()).contains("tab\there");
    }

    @Test
    @DisplayName("handles an array payload")
    void arrayPayload() throws Exception {
        byte[] body = writer.write(job("batch.done", "[1,2,3]", 0));

        assertThat(mapper.readTree(body).get("data").isArray()).isTrue();
    }

    @Test
    @DisplayName("produces valid UTF-8 for a payload containing non-ASCII text")
    void utf8Payload() throws Exception {
        byte[] body = writer.write(job("order.created", "{\"name\":\"日本語\"}", 0));

        assertThat(mapper.readTree(body).get("data").get("name").asText()).isEqualTo("日本語");
    }
}
