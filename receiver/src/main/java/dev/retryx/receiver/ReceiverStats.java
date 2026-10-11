package dev.retryx.receiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counts what arrives, so claims about Retryx become numbers.
 *
 * <h2>The measurement that matters: duplicate sends</h2>
 *
 * The naive way to count duplicates is to track event ids and call a repeat a duplicate. That
 * number is garbage here, because <em>a retry is supposed to repeat the event id</em> — it is the
 * same event, deliberately sent again, and the stable id is the whole point.
 *
 * <p>The meaningful unit is {@code deliveryId:attemptNumber}. One delivery attempt should cross the
 * wire exactly once; if the same (delivery, attempt) pair arrives twice, two workers claimed one row
 * and the {@code SKIP LOCKED} guarantee has been violated. That is the number that must be zero, and
 * {@link #getDuplicateAttempts()} is it.
 *
 * <p>The event-level repeat count is also tracked, separately, as {@link #getRepeatedEvents()}: it
 * is <em>expected</em> to be non-zero whenever retries happen, and it is the honest, visible
 * evidence of at-least-once delivery.
 *
 * <h2>The memory cap</h2>
 *
 * Exact distinct counts need to remember what has been seen, and a load test can push millions of
 * requests. Above {@link #TRACKING_LIMIT} keys this stops recording new ones and raises
 * {@code trackingSaturated}, so the reported figures say "accurate up to here" instead of quietly
 * becoming wrong — or taking the process down with an OutOfMemoryError in the middle of a benchmark.
 * HyperLogLog would give an approximate count in constant memory; exact-but-bounded is the better
 * trade when the claim being tested is "this number is exactly zero".
 */
@Component
public class ReceiverStats {

    /** Maximum distinct keys retained before tracking saturates. */
    private static final int TRACKING_LIMIT = 500_000;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong injectedFailures = new AtomicLong();
    private final AtomicLong signatureValid = new AtomicLong();
    private final AtomicLong signatureInvalid = new AtomicLong();
    private final AtomicLong signatureUnchecked = new AtomicLong();
    private final AtomicLong duplicateAttempts = new AtomicLong();
    private final AtomicLong repeatedEvents = new AtomicLong();

    /** {@code deliveryId:attempt} → times seen. A value above 1 is a genuine double send. */
    private final Map<String, Integer> attemptKeys = new ConcurrentHashMap<>();

    /** event id → times seen. Values above 1 are retries, which are expected. */
    private final Map<String, Integer> eventIds = new ConcurrentHashMap<>();

    private volatile boolean trackingSaturated;

    /**
     * Records one received request.
     *
     * @param eventId    value of {@code X-Retryx-Event-Id}
     * @param deliveryId value of {@code X-Retryx-Delivery-Id}
     * @param attempt    value of {@code X-Retryx-Attempt}
     */
    public void record(String eventId, String deliveryId, String attempt) {
        totalRequests.incrementAndGet();

        if (deliveryId != null && attempt != null) {
            track(attemptKeys, deliveryId + ":" + attempt, duplicateAttempts);
        }
        if (eventId != null) {
            track(eventIds, eventId, repeatedEvents);
        }
    }

    /**
     * @param seen    map to update
     * @param key     key to count
     * @param repeats counter incremented when the key has been seen before
     */
    private void track(Map<String, Integer> seen, String key, AtomicLong repeats) {
        if (seen.size() >= TRACKING_LIMIT && !seen.containsKey(key)) {
            trackingSaturated = true;
            return;
        }
        // compute() is atomic per key, so two threads receiving the same key cannot both see
        // "absent" and both insert 1 — which would under-count exactly the duplicates we are
        // trying to detect.
        seen.compute(
                key,
                (ignored, count) -> {
                    if (count == null) {
                        return 1;
                    }
                    repeats.incrementAndGet();
                    return count + 1;
                });
    }

    /** Records that this receiver chose to fail a request on purpose. */
    public void recordInjectedFailure() {
        injectedFailures.incrementAndGet();
    }

    /**
     * @param result outcome from {@link SignatureVerifier}
     */
    public void recordSignature(SignatureVerifier.Result result) {
        switch (result) {
            case VALID -> signatureValid.incrementAndGet();
            case NOT_CONFIGURED -> signatureUnchecked.incrementAndGet();
            default -> signatureInvalid.incrementAndGet();
        }
    }

    /** Clears every counter. Call between load-test runs. */
    public void reset() {
        totalRequests.set(0);
        injectedFailures.set(0);
        signatureValid.set(0);
        signatureInvalid.set(0);
        signatureUnchecked.set(0);
        duplicateAttempts.set(0);
        repeatedEvents.set(0);
        attemptKeys.clear();
        eventIds.clear();
        trackingSaturated = false;
    }

    /**
     * @return a snapshot, with the figures named so a reader knows which ones should be zero
     */
    public Map<String, Object> snapshot() {
        return Map.ofEntries(
                Map.entry("totalRequests", totalRequests.get()),
                Map.entry("uniqueEvents", eventIds.size()),
                Map.entry("uniqueDeliveryAttempts", attemptKeys.size()),
                // The headline correctness number. Must be 0.
                Map.entry("duplicateAttempts", duplicateAttempts.get()),
                // Expected to be > 0 whenever retries happen. This is at-least-once, visible.
                Map.entry("repeatedEvents", repeatedEvents.get()),
                Map.entry("injectedFailures", injectedFailures.get()),
                Map.entry("signatureValid", signatureValid.get()),
                Map.entry("signatureInvalid", signatureInvalid.get()),
                Map.entry("signatureUnchecked", signatureUnchecked.get()),
                Map.entry("trackingSaturated", trackingSaturated));
    }

    /** @return number of (delivery, attempt) pairs received more than once — must be 0 */
    public long getDuplicateAttempts() {
        return duplicateAttempts.get();
    }

    /** @return number of event ids received more than once — expected to be non-zero with retries */
    public long getRepeatedEvents() {
        return repeatedEvents.get();
    }

    /** @return total requests received */
    public long getTotalRequests() {
        return totalRequests.get();
    }
}
