package dev.retryx.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/events} body.
 *
 * @param eventType producer's type label. Constrained to a dotted-identifier shape so it is safe to
 *                  put in a header and in log lines without escaping, and so typos like
 *                  {@code "Order Created "} are rejected at the edge rather than becoming a
 *                  permanent second event type nobody subscribes to.
 * @param payload   arbitrary JSON object. Stored verbatim in {@code jsonb} and delivered inside the
 *                  envelope's {@code data} field.
 */
public record IngestEventRequest(
        @NotBlank
                @Size(max = 120)
                @Pattern(
                        regexp = "^[a-zA-Z0-9][a-zA-Z0-9._:-]*$",
                        message = "must be a dotted identifier, e.g. order.created")
                String eventType,
        @NotNull JsonNode payload) {}
