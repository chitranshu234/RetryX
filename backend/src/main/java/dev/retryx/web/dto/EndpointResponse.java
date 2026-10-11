package dev.retryx.web.dto;

import dev.retryx.domain.WebhookEndpoint;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An endpoint as returned by the API.
 *
 * <p>There is no {@code secret} field, only {@code secretPreview}. The plaintext secret is returned
 * exactly once, from {@code POST /api/webhooks}, in {@link CreatedEndpointResponse}. After that no
 * endpoint of this API can produce it — not because it is hidden, but because the only copy is
 * AES-GCM ciphertext and nothing in the read path decrypts it. A "show me my secret again" feature
 * would mean every endpoint listing is one authorisation bug away from leaking every signing key.
 *
 * @param id             endpoint id
 * @param name           display name
 * @param url            destination
 * @param description    optional note
 * @param active         false means fan-out skips it
 * @param secretPreview  display-safe hint, e.g. {@code whsec_1f4c…}
 * @param maxAttempts    retry budget override, or null for the server default
 * @param createdAt      when it was registered
 * @param updatedAt      when it last changed
 */
public record EndpointResponse(
        UUID id,
        String name,
        String url,
        String description,
        boolean active,
        String secretPreview,
        Integer maxAttempts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param endpoint entity to convert
     * @return the API representation
     */
    public static EndpointResponse from(WebhookEndpoint endpoint) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.isActive(),
                endpoint.getSecretPreview(),
                endpoint.getMaxAttempts(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt());
    }
}
