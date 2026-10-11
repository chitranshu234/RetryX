package dev.retryx.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.AbstractIntegrationTest;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryAttempt;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import java.util.List;
import java.util.Map;
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
 * Replay, and the multi-tenant isolation of every read endpoint.
 *
 * <p>The isolation tests are grouped here because they are one property with many surfaces: it is
 * not enough for most endpoints to scope by owner. One that does not is a complete breach, and the
 * only way to know is to check each one.
 */
class ReplayAndScopingIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate http;

    private HttpHeaders auth(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        return headers;
    }

    private ResponseEntity<Map> get(String path, String apiKey) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(apiKey)), Map.class);
    }

    private ResponseEntity<Map> post(String path, String apiKey, String body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, auth(apiKey)), Map.class);
    }

    /** Creates a delivery already in the given terminal state, with one recorded attempt. */
    private Delivery seedTerminalDelivery(String ownerId, DeliveryStatus status, int statusCode) {
        WebhookEndpoint endpoint =
                seedEndpoint(ownerId, "Target", "http://localhost:9876/hooks", "whsec_x");
        Event event = events.save(new Event(ownerId, "order.created", "{\"a\":1}", null, null));
        Delivery delivery = deliveries.save(new Delivery(ownerId, event.getId(), endpoint.getId(), 4));

        if (status == DeliveryStatus.FAILED) {
            delivery.onFailed(4, statusCode, "receiver responded " + statusCode);
        } else {
            delivery.onSuccess(1, statusCode);
        }
        deliveries.save(delivery);

        attempts.save(
                new DeliveryAttempt(
                        delivery.getId(),
                        1,
                        status == DeliveryStatus.FAILED
                                ? dev.retryx.domain.AttemptOutcome.RETRYABLE_ERROR
                                : dev.retryx.domain.AttemptOutcome.SUCCESS,
                        statusCode,
                        12,
                        null,
                        null,
                        "t=1,v1=abc",
                        "worker-1"));

        return delivery;
    }

    @Test
    @DisplayName("replaying a failed delivery requeues it and extends the budget")
    void replayFailed() {
        Delivery delivery = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);

        ResponseEntity<Map> response = post("/api/deliveries/" + delivery.getId() + "/replay", API_KEY, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(after.getReplayCount()).isEqualTo(1);
        assertThat(after.getCompletedAt()).isNull();
        assertThat(after.getNextAttemptAt()).isBeforeOrEqualTo(java.time.OffsetDateTime.now());

        // The budget is extended, not reset: attempt_count stays at 4 and max_attempts becomes 8.
        // That keeps (delivery_id, attempt_number) unique, so the next attempts are 5..8 and the
        // timeline of a twice-replayed delivery reads 1-4, 5-8, 9-12 instead of 1-4 three times.
        assertThat(after.getAttemptCount()).isEqualTo(4);
        assertThat(after.getMaxAttempts()).isEqualTo(8);
    }

    @Test
    @DisplayName("replaying keeps the original attempts")
    void replayPreservesHistory() {
        Delivery delivery = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);

        post("/api/deliveries/" + delivery.getId() + "/replay", API_KEY, "");

        // The whole reason attempts are a separate append-only table: a replay must not erase the
        // evidence of why it was needed.
        assertThat(attempts.countByDeliveryId(delivery.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a delivered delivery can be sent again")
    void replayDelivered() {
        Delivery delivery = seedTerminalDelivery(OWNER, DeliveryStatus.DELIVERED, 200);

        ResponseEntity<Map> response = post("/api/deliveries/" + delivery.getId() + "/replay", API_KEY, "");

        // "The receiver lost it, send it again" is a real request. It will be a duplicate from the
        // receiver's point of view, which is fine: the event id is unchanged, so a receiver that
        // deduplicates sees a repeat and one that does not gets what the operator asked for.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("replaying an active delivery is a conflict, not a silent no-op")
    void cannotReplayActive() {
        WebhookEndpoint endpoint = seedEndpoint(OWNER, "T", "http://localhost:9876/h", "whsec_x");
        Event event = events.save(new Event(OWNER, "a.b", "{}", null, null));
        Delivery pending = deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));

        ResponseEntity<Map> response = post("/api/deliveries/" + pending.getId() + "/replay", API_KEY, "");

        // It is already going to be attempted. Resetting it would either do nothing or stamp over a
        // lease a worker is relying on — so say so rather than pretending to act.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).isEqualTo("conflict");
    }

    @Test
    @DisplayName("bulk replay skips what it cannot replay and reports what it did")
    void bulkReplay() {
        Delivery failedOne = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);
        Delivery failedTwo = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 503);

        WebhookEndpoint endpoint = seedEndpoint(OWNER, "T", "http://localhost:9876/h", "whsec_x");
        Event event = events.save(new Event(OWNER, "a.b", "{}", null, null));
        Delivery pending = deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));

        String body =
                "{\"ids\":[\""
                        + failedOne.getId()
                        + "\",\""
                        + failedTwo.getId()
                        + "\",\""
                        + pending.getId()
                        + "\"]}";

        ResponseEntity<Map> response = post("/api/deliveries/replay", API_KEY, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("requested")).isEqualTo(3);
        // A partial bulk action that reports what it did beats one that refuses because of one
        // ineligible row.
        assertThat(response.getBody().get("replayed")).isEqualTo(2);
    }

    @Test
    @DisplayName("the delivery detail endpoint returns the full timeline")
    void deliveryDetail() {
        Delivery delivery = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);

        ResponseEntity<Map> response = get("/api/deliveries/" + delivery.getId(), API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> deliveryBody = (Map<String, Object>) response.getBody().get("delivery");
        assertThat(deliveryBody.get("status")).isEqualTo("FAILED");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) response.getBody().get("attempts");
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).get("statusCode")).isEqualTo(500);
        assertThat(timeline.get(0).get("workerId")).isEqualTo("worker-1");

        // Everything the detail page needs, in one request — no three-way loading waterfall.
        assertThat(response.getBody()).containsKeys("delivery", "attempts", "endpoint", "eventPayload");
    }

    @Test
    @DisplayName("one owner cannot read, replay or delete another owner's resources")
    void ownerScoping() {
        Delivery theirs = seedTerminalDelivery(OTHER_OWNER, DeliveryStatus.FAILED, 500);
        WebhookEndpoint theirEndpoint =
                endpoints.findById(theirs.getEndpointId()).orElseThrow();

        // Reads: 404, not 403. Answering 403 for "exists but is not yours" turns the API into an
        // oracle for enumerating other tenants' ids.
        assertThat(get("/api/deliveries/" + theirs.getId(), API_KEY).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/events/" + theirs.getEventId(), API_KEY).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/webhooks/" + theirEndpoint.getId(), API_KEY).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Writes, which matter more.
        assertThat(post("/api/deliveries/" + theirs.getId() + "/replay", API_KEY, "").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        http.exchange(
                                        "/api/webhooks/" + theirEndpoint.getId(),
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(auth(API_KEY)),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // And nothing was changed by the attempts.
        assertThat(deliveries.findById(theirs.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.FAILED);
        assertThat(endpoints.findById(theirEndpoint.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("list endpoints never leak across owners")
    void listsAreScoped() {
        seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);
        seedTerminalDelivery(OTHER_OWNER, DeliveryStatus.FAILED, 500);
        seedTerminalDelivery(OTHER_OWNER, DeliveryStatus.DELIVERED, 200);

        ResponseEntity<Map> mine = get("/api/deliveries?size=50", API_KEY);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().get("total")).isEqualTo(1);

        ResponseEntity<Map> theirs = get("/api/deliveries?size=50", OTHER_API_KEY);
        assertThat(theirs.getBody().get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("stats are computed per owner")
    void statsAreScoped() {
        seedTerminalDelivery(OWNER, DeliveryStatus.DELIVERED, 200);
        seedTerminalDelivery(OTHER_OWNER, DeliveryStatus.FAILED, 500);
        seedTerminalDelivery(OTHER_OWNER, DeliveryStatus.FAILED, 500);

        ResponseEntity<Map> mine = get("/api/stats", API_KEY);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().get("delivered")).isEqualTo(1);
        assertThat(mine.getBody().get("failed")).isEqualTo(0);
        assertThat(mine.getBody().get("successRate")).isEqualTo(100.0);

        ResponseEntity<Map> theirs = get("/api/stats", OTHER_API_KEY);
        assertThat(theirs.getBody().get("failed")).isEqualTo(2);
        assertThat(theirs.getBody().get("successRate")).isEqualTo(0.0);

        // The 24-hour series is always 24 buckets, even when nothing happened, so the chart never
        // draws a straight line across a gap.
        @SuppressWarnings("unchecked")
        List<Object> throughput = (List<Object>) mine.getBody().get("throughput");
        assertThat(throughput).hasSize(24);
    }

    @Test
    @DisplayName("a revoked key stops working")
    void revokedKeyIsRejected() {
        var key = apiKeys.findByKeyHash(hasher.hash(API_KEY)).orElseThrow();
        key.revoke();
        apiKeys.save(key);

        assertThat(get("/api/webhooks", API_KEY).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a deleted endpoint still renders in delivery history")
    void deletedEndpointKeepsHistory() {
        Delivery delivery = seedTerminalDelivery(OWNER, DeliveryStatus.FAILED, 500);

        http.exchange(
                "/api/webhooks/" + delivery.getEndpointId(),
                HttpMethod.DELETE,
                new HttpEntity<>(auth(API_KEY)),
                Void.class);

        ResponseEntity<Map> response = get("/api/deliveries/" + delivery.getId(), API_KEY);

        // Soft delete exists for exactly this: the delivery-detail page is what someone opens to
        // work out what went wrong, and "endpoint: unknown" would throw away the most useful fact
        // on the screen.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("endpoint")).isNotNull();
    }

    @Test
    @DisplayName("the SSRF guard rejects a bad destination at registration with a reason")
    void registrationRejectsPrivateUrls() {
        ResponseEntity<Map> response =
                post(
                        "/api/webhooks",
                        API_KEY,
                        "{\"name\":\"Internal\",\"url\":\"http://169.254.169.254/latest/meta-data/\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("destination_not_allowed");
        // The reason is actionable and reveals nothing the caller did not already supply.
        assertThat((String) response.getBody().get("message")).contains("link-local");
    }

    @Test
    @DisplayName("creating an endpoint returns the signing secret exactly once")
    void secretIsShownOnce() {
        ResponseEntity<Map> created =
                post(
                        "/api/webhooks",
                        API_KEY,
                        "{\"name\":\"Orders\",\"url\":\"http://localhost:9876/hooks\"}");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String secret = (String) created.getBody().get("signingSecret");
        assertThat(secret).startsWith("whsec_");

        @SuppressWarnings("unchecked")
        Map<String, Object> endpoint = (Map<String, Object>) created.getBody().get("endpoint");
        String id = (String) endpoint.get("id");

        // And never again: no read endpoint can produce it, because nothing in the read path
        // decrypts it.
        ResponseEntity<Map> fetched = get("/api/webhooks/" + id, API_KEY);
        assertThat(fetched.getBody()).doesNotContainKey("signingSecret");
        assertThat((String) fetched.getBody().get("secretPreview")).endsWith("…");
        assertThat((String) fetched.getBody().get("secretPreview")).isNotEqualTo(secret);
    }
}
