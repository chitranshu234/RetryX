package dev.retryx.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An event as returned by the API.
 *
 * @param id             event id. Also the value of the {@code X-Retryx-Event-Id} header on every
 *                       delivery of this event, so a receiver's log line can be traced straight
 *                       back to this record.
 * @param eventType      producer's type label
 * @param payload        the original JSON
 * @param idempotencyKey the key the producer sent, if any
 * @param createdAt      ingest time
 * @param deliveries     fan-out summary, present on single-event reads and omitted from list
 *                       responses to keep them one query
 */
public record EventResponse(
        UUID id,
        String eventType,
        JsonNode payload,
        String idempotencyKey,
        OffsetDateTime createdAt,
        List<DeliveryResponse> deliveries) {}
