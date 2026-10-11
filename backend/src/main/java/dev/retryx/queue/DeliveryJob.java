package dev.retryx.queue;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Everything a worker needs to perform one send, gathered in one read.
 *
 * <p>Claiming returns ids only; this record is the follow-up join across
 * {@code deliveries × events × webhook_endpoints}. Two queries per batch rather than one per
 * delivery, and — more importantly — the worker holds a plain immutable value, not a JPA entity.
 * There is no persistence context open while the HTTP call is in flight, so there is no way to
 * accidentally keep a database connection or a transaction alive across the network wait.
 *
 * @param deliveryId      the delivery being attempted
 * @param ownerId         tenant
 * @param eventId         event being delivered; sent in a header and stable across retries, so a
 *                        receiver can use it as a deduplication key
 * @param endpointId      destination endpoint
 * @param endpointName    display name, for log lines
 * @param eventType       producer's type label
 * @param payloadJson     the event's JSON payload, as text straight out of {@code jsonb}
 * @param eventCreatedAt  when the event was ingested
 * @param url             destination URL, re-validated before every send
 * @param encryptedSecret AES-GCM envelope of the signing secret; decrypted at the last moment
 * @param attemptCount    attempts already recorded
 * @param maxAttempts     retry budget for this row
 */
public record DeliveryJob(
        UUID deliveryId,
        String ownerId,
        UUID eventId,
        UUID endpointId,
        String endpointName,
        String eventType,
        String payloadJson,
        OffsetDateTime eventCreatedAt,
        String url,
        String encryptedSecret,
        int attemptCount,
        int maxAttempts) {

    /**
     * @return the one-based number of the attempt this job is about to make
     */
    public int attemptNumber() {
        return attemptCount + 1;
    }

    /**
     * @return true if a retryable failure on this attempt still leaves budget for another
     */
    public boolean hasBudgetAfterThisAttempt() {
        return attemptNumber() < maxAttempts;
    }
}
