package dev.retryx.web.dto;

/**
 * Response to {@code POST /api/webhooks}.
 *
 * <p>The only time the plaintext signing secret ever leaves the server. The field is named and
 * documented as show-once so a client library author does not build a UI that assumes it can be
 * fetched again.
 *
 * @param endpoint      the created endpoint
 * @param signingSecret plaintext secret — store it now; it cannot be retrieved later
 */
public record CreatedEndpointResponse(EndpointResponse endpoint, String signingSecret) {}
