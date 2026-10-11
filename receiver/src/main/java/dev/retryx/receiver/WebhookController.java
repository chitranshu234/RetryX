package dev.retryx.receiver;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The receiving endpoints, with failure injection.
 *
 * <p>Note {@code @RequestBody byte[]} on every handler. That is not an optimisation — it is the
 * only way to verify an HMAC. Declaring the parameter as a DTO would hand the handler a parsed
 * object and discard the exact bytes that were signed, after which no signature can ever be checked.
 * Every webhook receiver that "cannot get signature verification to work" has made this mistake.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier verifier;
    private final ReceiverStats stats;
    private final double defaultFailureRate;
    private final long defaultLatencyMs;
    private final boolean rejectInvalidSignature;

    public WebhookController(
            SignatureVerifier verifier,
            ReceiverStats stats,
            @Value("${receiver.failure-rate:0.0}") double defaultFailureRate,
            @Value("${receiver.latency-ms:0}") long defaultLatencyMs,
            @Value("${receiver.reject-invalid-signature:true}") boolean rejectInvalidSignature) {
        this.verifier = verifier;
        this.stats = stats;
        this.defaultFailureRate = defaultFailureRate;
        this.defaultLatencyMs = defaultLatencyMs;
        this.rejectInvalidSignature = rejectInvalidSignature;

        log.info(
                "receiver ready: failureRate={} latencyMs={} rejectInvalidSignature={}",
                defaultFailureRate,
                defaultLatencyMs,
                rejectInvalidSignature);
    }

    /**
     * The normal endpoint. Fails at {@code receiver.failure-rate}, which defaults to never.
     *
     * @param body       raw request bytes
     * @param signature  {@code X-Retryx-Signature}
     * @param eventId    {@code X-Retryx-Event-Id}
     * @param deliveryId {@code X-Retryx-Delivery-Id}
     * @param attempt    {@code X-Retryx-Attempt}
     * @return 200, or 500 when a failure is injected
     */
    @PostMapping("/hooks")
    public ResponseEntity<?> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Retryx-Signature", required = false) String signature,
            @RequestHeader(value = "X-Retryx-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Retryx-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Retryx-Attempt", required = false) String attempt) {

        return handle(body, signature, eventId, deliveryId, attempt, defaultFailureRate, defaultLatencyMs);
    }

    /**
     * Fails about 40% of the time. Used by the seeded demo endpoint so the dashboard shows real
     * retries within seconds of starting up.
     *
     * @param body       raw request bytes
     * @param signature  {@code X-Retryx-Signature}
     * @param eventId    {@code X-Retryx-Event-Id}
     * @param deliveryId {@code X-Retryx-Delivery-Id}
     * @param attempt    {@code X-Retryx-Attempt}
     * @return 200 or 500
     */
    @PostMapping("/hooks/flaky")
    public ResponseEntity<?> flaky(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Retryx-Signature", required = false) String signature,
            @RequestHeader(value = "X-Retryx-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Retryx-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Retryx-Attempt", required = false) String attempt) {

        return handle(body, signature, eventId, deliveryId, attempt, 0.4, defaultLatencyMs);
    }

    /**
     * Always 500. Demonstrates budget exhaustion: the delivery retries the full schedule and then
     * lands in FAILED, where it can be replayed.
     *
     * @param body       raw request bytes
     * @param signature  {@code X-Retryx-Signature}
     * @param eventId    {@code X-Retryx-Event-Id}
     * @param deliveryId {@code X-Retryx-Delivery-Id}
     * @param attempt    {@code X-Retryx-Attempt}
     * @return 500
     */
    @PostMapping("/hooks/always-fail")
    public ResponseEntity<?> alwaysFail(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Retryx-Signature", required = false) String signature,
            @RequestHeader(value = "X-Retryx-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Retryx-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Retryx-Attempt", required = false) String attempt) {

        return handle(body, signature, eventId, deliveryId, attempt, 1.0, defaultLatencyMs);
    }

    /**
     * Returns 404. Demonstrates the <em>permanent</em> branch of the retry classifier: one attempt,
     * then straight to FAILED with no retries, because a 404 will be a 404 next time too.
     *
     * @param body       raw request bytes
     * @param eventId    {@code X-Retryx-Event-Id}
     * @param deliveryId {@code X-Retryx-Delivery-Id}
     * @param attempt    {@code X-Retryx-Attempt}
     * @return 404
     */
    @PostMapping("/hooks/not-found")
    public ResponseEntity<?> notFound(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Retryx-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Retryx-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Retryx-Attempt", required = false) String attempt) {

        stats.record(eventId, deliveryId, attempt);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "no handler registered for this event type"));
    }

    /**
     * Sleeps for 20 seconds — longer than Retryx's default 10s read timeout. Demonstrates that a
     * timeout is classified as retryable and that the lease outlives the HTTP call.
     *
     * @param body       raw request bytes
     * @param eventId    {@code X-Retryx-Event-Id}
     * @param deliveryId {@code X-Retryx-Delivery-Id}
     * @param attempt    {@code X-Retryx-Attempt}
     * @return 200, eventually — the sender will usually have given up first
     * @throws InterruptedException if the sleep is interrupted
     */
    @PostMapping("/hooks/slow")
    public ResponseEntity<?> slow(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Retryx-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-Retryx-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Retryx-Attempt", required = false) String attempt)
            throws InterruptedException {

        stats.record(eventId, deliveryId, attempt);
        Thread.sleep(20_000);
        return ResponseEntity.ok(Map.of("status", "eventually"));
    }

    /**
     * @return the counters, including the duplicate-send figure that must be zero
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return stats.snapshot();
    }

    /**
     * Clears the counters, so each load-test run starts from a clean slate.
     *
     * @return confirmation
     */
    @PostMapping("/reset")
    public Map<String, String> reset() {
        stats.reset();
        log.info("receiver counters reset");
        return Map.of("status", "reset");
    }

    /** @return a liveness probe that does not touch the counters */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    /**
     * Shared handling: verify, count, optionally fail.
     *
     * @param body        raw request bytes
     * @param signature   signature header
     * @param eventId     event id header
     * @param deliveryId  delivery id header
     * @param attempt     attempt header
     * @param failureRate probability of injecting a 500
     * @param latencyMs   artificial delay before responding
     * @return the response to send back to Retryx
     */
    private ResponseEntity<?> handle(
            byte[] body,
            String signature,
            String eventId,
            String deliveryId,
            String attempt,
            double failureRate,
            long latencyMs) {

        stats.record(eventId, deliveryId, attempt);

        SignatureVerifier.Result verification = verifier.verify(signature, body);
        stats.recordSignature(verification);

        if (verification != SignatureVerifier.Result.VALID
                && verification != SignatureVerifier.Result.NOT_CONFIGURED
                && rejectInvalidSignature) {
            // 400 is correct and deliberate: an unverifiable request will not become verifiable on
            // retry, so Retryx classifies it as permanent and stops. A 500 here would make the
            // sender retry a request this receiver will never accept, six times, forever.
            log.warn("rejecting event {} — signature {}", eventId, verification);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid signature", "reason", verification.name()));
        }

        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            stats.recordInjectedFailure();
            log.info("injecting failure for event {} (attempt {})", eventId, attempt);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "injected failure", "attempt", String.valueOf(attempt)));
        }

        log.debug("accepted event {} (delivery {}, attempt {})", eventId, deliveryId, attempt);
        return ResponseEntity.ok(Map.of("status", "accepted", "eventId", String.valueOf(eventId)));
    }
}
