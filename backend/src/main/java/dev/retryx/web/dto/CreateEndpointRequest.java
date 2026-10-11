package dev.retryx.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/webhooks} body.
 *
 * @param name        display name, required
 * @param url         destination; validated by {@link dev.retryx.security.SsrfGuard} before the row
 *                    is written, so a rejected URL is a 400 rather than a delivery that fails
 *                    forever
 * @param description optional note
 * @param secret      optional caller-supplied signing secret. Useful when pointing Retryx at a
 *                    receiver that already has a secret configured. Omit it and Retryx generates
 *                    one, which is the better default — a generated secret is 32 bytes of CSPRNG
 *                    output rather than whatever the caller typed.
 * @param maxAttempts optional per-endpoint retry budget, overriding the server default
 */
public record CreateEndpointRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 2000) String url,
        @Size(max = 500) String description,
        @Size(min = 16, max = 200) String secret,
        @Min(1) @Max(20) Integer maxAttempts) {}
