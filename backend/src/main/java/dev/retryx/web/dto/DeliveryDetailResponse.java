package dev.retryx.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Response to {@code GET /api/deliveries/{id}} — everything the delivery-detail page needs, in one
 * request.
 *
 * <p>Bundled rather than split across three endpoints because the page is useless without all three
 * parts, and three round trips would mean three loading states and a visible waterfall for no
 * benefit.
 *
 * @param delivery      current state
 * @param attempts      the full timeline, oldest first
 * @param endpoint      where it is being delivered, so the page can show the URL that failed
 * @param eventType     the event's type
 * @param eventPayload  the original payload, so a developer can see what was actually being sent
 */
public record DeliveryDetailResponse(
        DeliveryResponse delivery,
        List<AttemptResponse> attempts,
        EndpointResponse endpoint,
        String eventType,
        JsonNode eventPayload) {}
