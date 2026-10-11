package dev.retryx.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.retryx.AbstractIntegrationTest;
import dev.retryx.domain.AttemptOutcome;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryAttempt;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.security.HmacSigner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The full delivery lifecycle, against a real HTTP server.
 *
 * <p>WireMock on a real socket rather than a mocked HTTP client, because the behaviours under test
 * are transport behaviours: a read timeout, a connection the receiver closes, a 500 followed by a
 * 200. A mocked client would be asserting that the code calls the methods the test expects, which
 * is a restatement of the implementation rather than a test of it.
 *
 * <p>The port is fixed so it can be added to the SSRF port allowlist in {@code application-test.yml}
 * — the guard is deliberately left switched on for these tests, because a delivery path tested with
 * the guard disabled is not the delivery path that runs in production.
 */
class DeliveryLifecycleIT extends AbstractIntegrationTest {

    private static final int RECEIVER_PORT = 9876;
    private static final String SECRET = "whsec_lifecycle_secret";

    private static WireMockServer receiver;

    @Autowired private DeliveryWorker worker;
    @Autowired private HmacSigner signer;

    @BeforeAll
    static void startReceiver() {
        receiver = new WireMockServer(WireMockConfiguration.options().port(RECEIVER_PORT));
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @BeforeEach
    void resetReceiver() {
        receiver.resetAll();
    }

    @Test
    @DisplayName("delivers on the first attempt when the receiver accepts")
    void happyPath() {
        receiver.stubFor(post(urlEqualTo("/hooks")).willReturn(aResponse().withStatus(200).withBody("ok")));

        Delivery delivery = queueDelivery("/hooks");

        assertThat(worker.runOnce()).isEqualTo(1);

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getLastStatusCode()).isEqualTo(200);
        assertThat(after.getCompletedAt()).isNotNull();
        // The lease is released on completion, so nothing is left looking claimed.
        assertThat(after.getLockedBy()).isNull();

        List<DeliveryAttempt> timeline = attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(timeline.get(0).getResponseBodySnippet()).isEqualTo("ok");
        assertThat(timeline.get(0).getWorkerId()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("sends the documented headers, with a verifiable signature")
    void sendsSignedRequest() {
        receiver.stubFor(post(urlEqualTo("/hooks")).willReturn(aResponse().withStatus(200)));

        Delivery delivery = queueDelivery("/hooks");
        worker.runOnce();

        var requests = receiver.findAll(postRequestedFor(urlEqualTo("/hooks")));
        assertThat(requests).hasSize(1);
        var request = requests.get(0);

        Event event = events.findById(delivery.getEventId()).orElseThrow();

        assertThat(request.getHeader("X-Retryx-Event-Id")).isEqualTo(event.getId().toString());
        assertThat(request.getHeader("X-Retryx-Delivery-Id")).isEqualTo(delivery.getId().toString());
        assertThat(request.getHeader("X-Retryx-Event-Type")).isEqualTo("order.created");
        assertThat(request.getHeader("X-Retryx-Attempt")).isEqualTo("1");
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(event.getId().toString());
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        // The signature must verify against the exact bytes the receiver got. If the signer and the
        // sender ever disagree about what was signed, this is where it shows up.
        String signature = request.getHeader("X-Retryx-Signature");
        long timestamp = Long.parseLong(request.getHeader("X-Retryx-Timestamp"));
        byte[] body = request.getBody();

        assertThat(signature).isNotNull();
        assertThat(signer.verify(SECRET, signature, body, timestamp, 300)).isTrue();

        // And the envelope is the documented shape.
        String bodyText = new String(body, StandardCharsets.UTF_8);
        assertThat(bodyText).contains("\"id\":\"" + event.getId() + "\"");
        assertThat(bodyText).contains("\"type\":\"order.created\"");
        assertThat(bodyText).contains("\"data\":");
    }

    @Test
    @DisplayName("retries a 500 and succeeds on the third attempt")
    void retriesThenSucceeds() {
        // A scenario, so the stub answers differently each call — 500, 500, then 200.
        receiver.stubFor(
                post(urlEqualTo("/hooks"))
                        .inScenario("flaky")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(500).withBody("boom"))
                        .willSetStateTo("second"));
        receiver.stubFor(
                post(urlEqualTo("/hooks"))
                        .inScenario("flaky")
                        .whenScenarioStateIs("second")
                        .willReturn(aResponse().withStatus(503))
                        .willSetStateTo("third"));
        receiver.stubFor(
                post(urlEqualTo("/hooks"))
                        .inScenario("flaky")
                        .whenScenarioStateIs("third")
                        .willReturn(aResponse().withStatus(200)));

        Delivery delivery = queueDelivery("/hooks");

        // Three polls. The test profile sets base-delay to 0 with no jitter, so a retry is due
        // immediately and the test does not have to sleep through a backoff schedule.
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.PENDING);

        worker.runOnce();
        worker.runOnce();

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(after.getAttemptCount()).isEqualTo(3);

        // The whole history is preserved — this is why attempts are a separate, append-only table.
        List<DeliveryAttempt> timeline = attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(timeline).hasSize(3);
        assertThat(timeline).extracting(DeliveryAttempt::getAttemptNumber).containsExactly(1, 2, 3);
        assertThat(timeline).extracting(DeliveryAttempt::getStatusCode).containsExactly(500, 503, 200);
        assertThat(timeline)
                .extracting(DeliveryAttempt::getOutcome)
                .containsExactly(
                        AttemptOutcome.RETRYABLE_ERROR, AttemptOutcome.RETRYABLE_ERROR, AttemptOutcome.SUCCESS);
        assertThat(timeline.get(0).getResponseBodySnippet()).isEqualTo("boom");
    }

    @Test
    @DisplayName("gives up after the retry budget is exhausted")
    void exhaustsBudget() {
        receiver.stubFor(post(urlEqualTo("/hooks")).willReturn(aResponse().withStatus(500)));

        Delivery delivery = queueDelivery("/hooks");

        // Budget is 4 in the test profile.
        for (int poll = 0; poll < 6; poll++) {
            worker.runOnce();
        }

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(after.getAttemptCount()).isEqualTo(4);
        assertThat(after.getLastError()).contains("retry budget exhausted");

        // Exactly four attempts — not five, and not an endless loop.
        assertThat(attempts.countByDeliveryId(delivery.getId())).isEqualTo(4);
        assertThat(receiver.findAll(postRequestedFor(anyUrl()))).hasSize(4);
    }

    @Test
    @DisplayName("does not retry a 404")
    void permanentFailureStopsImmediately() {
        receiver.stubFor(
                post(urlEqualTo("/hooks"))
                        .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"no such route\"}")));

        Delivery delivery = queueDelivery("/hooks");

        worker.runOnce();
        worker.runOnce();
        worker.runOnce();

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        // One attempt, with budget left over. Spending five more requests confirming a 404 is a
        // self-inflicted load test.
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getMaxAttempts()).isEqualTo(4);

        List<DeliveryAttempt> timeline = attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getOutcome()).isEqualTo(AttemptOutcome.PERMANENT_ERROR);
        // The receiver's own message is kept, because it usually explains the failure better than
        // the status code does.
        assertThat(timeline.get(0).getResponseBodySnippet()).contains("no such route");
    }

    @Test
    @DisplayName("does not follow a redirect, and treats it as permanent")
    void doesNotFollowRedirects() {
        receiver.stubFor(
                post(urlEqualTo("/hooks"))
                        .willReturn(
                                aResponse()
                                        .withStatus(302)
                                        // The attack: a public URL redirecting to the cloud metadata service.
                                        .withHeader("Location", "http://169.254.169.254/latest/meta-data/")));

        Delivery delivery = queueDelivery("/hooks");
        worker.runOnce();

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(after.getLastStatusCode()).isEqualTo(302);
        // Only one request was made — the redirect was not followed, so the SSRF guard's work was
        // not undone by the HTTP client.
        assertThat(receiver.findAll(postRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    @DisplayName("treats a read timeout as retryable")
    void timeoutIsRetryable() {
        // The test profile sets read-timeout to 2s; the receiver takes 4.
        receiver.stubFor(
                post(urlEqualTo("/hooks")).willReturn(aResponse().withStatus(200).withFixedDelay(4000)));

        Delivery delivery = queueDelivery("/hooks");
        worker.runOnce();

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.PENDING);

        List<DeliveryAttempt> timeline = attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getOutcome()).isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
        assertThat(timeline.get(0).getStatusCode()).isNull();
        assertThat(timeline.get(0).getErrorMessage()).containsIgnoringCase("timeout");
    }

    @Test
    @DisplayName("blocks a private destination at send time and says why")
    void blocksPrivateDestinationAtSendTime() {
        // The URL is written straight into the row, bypassing the API's validation — which is what
        // would happen if DNS changed after registration, or if a row were inserted by hand.
        WebhookEndpoint endpoint = seedEndpoint(OWNER, "Evil", "http://10.1.2.3:8080/hooks", SECRET);
        Event event = events.save(new Event(OWNER, "order.created", "{\"a\":1}", null, null));
        Delivery delivery = deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));

        worker.runOnce();

        Delivery after = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.FAILED);

        List<DeliveryAttempt> timeline = attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(delivery.getId());
        // Recorded as an attempt even though nothing was sent, so the timeline explains the gap
        // rather than leaving one.
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getOutcome()).isEqualTo(AttemptOutcome.BLOCKED);
        assertThat(timeline.get(0).getErrorMessage()).contains("private network");
    }

    @Test
    @DisplayName("sends the same event id and the same body on every retry")
    void retriesAreStable() {
        receiver.stubFor(post(urlEqualTo("/hooks")).willReturn(aResponse().withStatus(500)));

        Delivery delivery = queueDelivery("/hooks");
        worker.runOnce();
        worker.runOnce();

        var requests = receiver.findAll(postRequestedFor(urlEqualTo("/hooks")));
        assertThat(requests).hasSize(2);

        // Stable: a receiver can deduplicate on the event id or on a hash of the body.
        assertThat(requests.get(0).getHeader("X-Retryx-Event-Id"))
                .isEqualTo(requests.get(1).getHeader("X-Retryx-Event-Id"));
        assertThat(requests.get(0).getBodyAsString()).isEqualTo(requests.get(1).getBodyAsString());

        // Changing: the attempt number, and the timestamp inside the signature.
        assertThat(requests.get(0).getHeader("X-Retryx-Attempt")).isEqualTo("1");
        assertThat(requests.get(1).getHeader("X-Retryx-Attempt")).isEqualTo("2");
    }

    /** Queues one delivery to the WireMock receiver at the given path. */
    private Delivery queueDelivery(String path) {
        WebhookEndpoint endpoint =
                seedEndpoint(OWNER, "Receiver", "http://localhost:" + RECEIVER_PORT + path, SECRET);
        Event event =
                events.save(new Event(OWNER, "order.created", "{\"orderId\":\"A-1\",\"total\":42.5}", null, null));
        return deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));
    }
}
