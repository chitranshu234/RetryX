package dev.retryx.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.AbstractIntegrationTest;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.WebhookEndpoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Ingestion, idempotency and fan-out, through the real HTTP API.
 *
 * <p>Through the API rather than the service, because the pieces being tested live at that boundary:
 * the {@code Idempotency-Key} header, the 200-versus-201 distinction, the authentication filter and
 * the owner scoping.
 */
class EventIngestionIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate http;

    private HttpHeaders headers(String apiKey, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private ResponseEntity<Map> postEvent(String apiKey, String idempotencyKey, String body) {
        return http.exchange(
                "/api/events", HttpMethod.POST, new HttpEntity<>(body, headers(apiKey, idempotencyKey)), Map.class);
    }

    private static final String EVENT_BODY =
            "{\"eventType\":\"order.created\",\"payload\":{\"orderId\":\"A-1\",\"total\":42.5}}";

    @Test
    @DisplayName("stores the event and queues one delivery per active endpoint")
    void fansOutToActiveEndpoints() {
        seedEndpoint(OWNER, "One", "http://localhost:9876/a", "whsec_1");
        seedEndpoint(OWNER, "Two", "http://localhost:9876/b", "whsec_2");

        WebhookEndpoint disabled = seedEndpoint(OWNER, "Off", "http://localhost:9876/c", "whsec_3");
        disabled.setActive(false);
        endpoints.save(disabled);

        ResponseEntity<Map> response = postEvent(API_KEY, "order-1", EVENT_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        UUID eventId = UUID.fromString((String) response.getBody().get("id"));
        // Two deliveries, not three — the disabled endpoint is skipped.
        assertThat(deliveries.countByEventId(eventId)).isEqualTo(2);

        List<Delivery> queued = deliveries.findAllByEventIdAndOwnerIdOrderByCreatedAtAsc(eventId, OWNER);
        assertThat(queued)
                .allSatisfy(
                        delivery -> {
                            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
                            assertThat(delivery.getAttemptCount()).isZero();
                            assertThat(delivery.getOwnerId()).isEqualTo(OWNER);
                        });
    }

    @Test
    @DisplayName("a repeated Idempotency-Key returns the original event and creates nothing")
    void duplicateIsIgnored() {
        seedEndpoint(OWNER, "One", "http://localhost:9876/a", "whsec_1");

        ResponseEntity<Map> first = postEvent(API_KEY, "order-42", EVENT_BODY);
        ResponseEntity<Map> second = postEvent(API_KEY, "order-42", EVENT_BODY);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 200, not 409. The producer asked for "this event exists", and it does — making a retrying
        // client handle an error for a successful outcome would be perverse.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));

        assertThat(events.countByOwnerId(OWNER)).isEqualTo(1);
        // The critical part: no second fan-out, so the receiver is not sent the event twice.
        assertThat(deliveries.countByEventId(UUID.fromString((String) first.getBody().get("id"))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent duplicates all resolve to one event")
    void concurrentDuplicatesRaceSafely() throws Exception {
        seedEndpoint(OWNER, "One", "http://localhost:9876/a", "whsec_1");

        // The case a check-then-insert gets wrong. Twelve requests with the same key, fired at
        // once: all twelve can pass an existence check before any of them commits. What actually
        // prevents a double insert is the partial unique index, and this test is the proof.
        int concurrency = 12;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<ResponseEntity<Map>>> calls =
                    IntStream.range(0, concurrency)
                            .<Callable<ResponseEntity<Map>>>mapToObj(
                                    i -> () -> postEvent(API_KEY, "race-key", EVENT_BODY))
                            .toList();

            List<Future<ResponseEntity<Map>>> results = pool.invokeAll(calls);

            for (Future<ResponseEntity<Map>> result : results) {
                ResponseEntity<Map> response = result.get();
                assertThat(response.getStatusCode())
                        .as("every concurrent duplicate must succeed, as either the winner or a replay")
                        .isIn(HttpStatus.OK, HttpStatus.CREATED);
            }

            // Exactly one event and one delivery, whatever order the twelve arrived in.
            assertThat(events.countByOwnerId(OWNER)).isEqualTo(1);
            assertThat(deliveries.findAll()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("idempotency keys are scoped per owner")
    void keysDoNotCollideAcrossOwners() {
        seedEndpoint(OWNER, "A", "http://localhost:9876/a", "whsec_1");
        seedEndpoint(OTHER_OWNER, "B", "http://localhost:9876/b", "whsec_2");

        ResponseEntity<Map> mine = postEvent(API_KEY, "shared-key", EVENT_BODY);
        ResponseEntity<Map> theirs = postEvent(OTHER_API_KEY, "shared-key", EVENT_BODY);

        // Two tenants using "order-1" is entirely normal. A global constraint would have one
        // tenant's traffic silently deduplicating against another's.
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mine.getBody().get("id")).isNotEqualTo(theirs.getBody().get("id"));
    }

    @Test
    @DisplayName("an event without an Idempotency-Key is always stored")
    void keyIsOptional() {
        seedEndpoint(OWNER, "A", "http://localhost:9876/a", "whsec_1");

        assertThat(postEvent(API_KEY, null, EVENT_BODY).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postEvent(API_KEY, null, EVENT_BODY).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The unique index is partial — rows with no key are not forced to be unique.
        assertThat(events.countByOwnerId(OWNER)).isEqualTo(2);
    }

    @Test
    @DisplayName("an event with no active endpoints is still stored")
    void storedWithoutEndpoints() {
        ResponseEntity<Map> response = postEvent(API_KEY, null, EVENT_BODY);

        // Not an error. The event is a durable fact, and replay is impossible for an event that was
        // never written — so register an endpoint later and the history is still there.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(events.countByOwnerId(OWNER)).isEqualTo(1);
        assertThat(deliveries.findAll()).isEmpty();
    }

    @Test
    @DisplayName("rejects a malformed event type and an invalid payload")
    void validatesInput() {
        assertThat(
                        postEvent(API_KEY, null, "{\"eventType\":\"has spaces\",\"payload\":{}}")
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(postEvent(API_KEY, null, "{\"payload\":{}}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(postEvent(API_KEY, null, "{\"eventType\":\"a.b\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // A scalar is valid JSON but not a sensible event body.
        assertThat(postEvent(API_KEY, null, "{\"eventType\":\"a.b\",\"payload\":42}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(postEvent(API_KEY, null, "not json at all").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("requires a valid API key")
    void requiresAuthentication() {
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);

        assertThat(
                        http.exchange(
                                        "/api/events",
                                        HttpMethod.POST,
                                        new HttpEntity<>(EVENT_BODY, anonymous),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(postEvent("rtx_sk_not_a_real_key_000000000", null, EVENT_BODY).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A key-shaped string that is not a key, and a non-key-shaped string, must be
        // indistinguishable in the response.
        assertThat(postEvent("garbage", null, EVENT_BODY).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("stores the payload in jsonb and returns it unchanged")
    void payloadRoundTrips() {
        String body =
                "{\"eventType\":\"order.created\",\"payload\":"
                        + "{\"nested\":{\"deep\":[1,2,{\"x\":true}]},\"unicode\":\"日本語\",\"nil\":null}}";

        ResponseEntity<Map> response = postEvent(API_KEY, null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody().get("payload");
        assertThat(payload).containsKey("nested");
        assertThat(payload.get("unicode")).isEqualTo("日本語");
    }
}
