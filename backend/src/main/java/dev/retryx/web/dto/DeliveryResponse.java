package dev.retryx.web.dto;

import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A delivery as returned by the API — current state, not history.
 *
 * <p>{@code attemptCount} and {@code lastError} are the live summary; the full per-attempt timeline
 * comes from {@code GET /api/deliveries/{id}} and lives in {@link AttemptResponse}.
 *
 * @param id             delivery id
 * @param eventId        event being delivered
 * @param endpointId     destination endpoint
 * @param endpointName   destination name, joined in for display
 * @param eventType      event type, joined in for display
 * @param status         current state
 * @param attemptCount   attempts made so far
 * @param maxAttempts    retry budget
 * @param nextAttemptAt  when it becomes claimable again; the UI renders this as "retrying in …"
 * @param lastStatusCode last HTTP status seen
 * @param lastError      last failure description
 * @param replayCount    how many times a human has replayed this
 * @param completedAt    when it reached a terminal state
 * @param createdAt      when fan-out created it
 */
public record DeliveryResponse(
        UUID id,
        UUID eventId,
        UUID endpointId,
        String endpointName,
        String eventType,
        DeliveryStatus status,
        int attemptCount,
        int maxAttempts,
        OffsetDateTime nextAttemptAt,
        Integer lastStatusCode,
        String lastError,
        int replayCount,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {

    /**
     * @param delivery     entity to convert
     * @param endpointName joined display name, or null if unavailable
     * @param eventType    joined event type, or null if unavailable
     * @return the API representation
     */
    public static DeliveryResponse from(Delivery delivery, String endpointName, String eventType) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getEndpointId(),
                endpointName,
                eventType,
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getMaxAttempts(),
                delivery.getNextAttemptAt(),
                delivery.getLastStatusCode(),
                delivery.getLastError(),
                delivery.getReplayCount(),
                delivery.getCompletedAt(),
                delivery.getCreatedAt());
    }
}
