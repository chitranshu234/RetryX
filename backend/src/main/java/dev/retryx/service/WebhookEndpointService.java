package dev.retryx.service;

import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.error.ResourceNotFoundException;
import dev.retryx.repository.WebhookEndpointRepository;
import dev.retryx.security.AesGcmSecretCipher;
import dev.retryx.security.SsrfGuard;
import dev.retryx.web.dto.CreateEndpointRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Endpoint registration and lifecycle.
 *
 * <p>Every method takes {@code ownerId} as its first argument and passes it through to a repository
 * query that includes it. There is no code path in this class that reads an endpoint by id alone.
 */
@Service
public class WebhookEndpointService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointService.class);

    private final WebhookEndpointRepository endpoints;
    private final SsrfGuard ssrfGuard;
    private final AesGcmSecretCipher cipher;

    public WebhookEndpointService(
            WebhookEndpointRepository endpoints, SsrfGuard ssrfGuard, AesGcmSecretCipher cipher) {
        this.endpoints = endpoints;
        this.ssrfGuard = ssrfGuard;
        this.cipher = cipher;
    }

    /** The endpoint plus its plaintext secret, which the caller must return to the user once. */
    public record Created(WebhookEndpoint endpoint, String plaintextSecret) {}

    /**
     * Registers a new endpoint.
     *
     * <p>The URL is validated here so a bad destination is a 400 the user can act on, rather than an
     * endpoint that silently fails every delivery forever. It is validated <em>again</em> before
     * every send, because this check only proves the URL was safe at registration time.
     *
     * @param ownerId tenant
     * @param request name, URL and options
     * @return the saved endpoint and its plaintext signing secret
     * @throws dev.retryx.security.BlockedDestinationException if the URL is not an allowed
     *     destination
     */
    @Transactional
    public Created create(String ownerId, CreateEndpointRequest request) {
        ssrfGuard.validate(request.url());

        String secret =
                (request.secret() == null || request.secret().isBlank())
                        ? cipher.generateSigningSecret()
                        : request.secret().trim();

        WebhookEndpoint endpoint =
                new WebhookEndpoint(
                        ownerId,
                        request.name().trim(),
                        request.url().trim(),
                        cipher.encrypt(secret),
                        cipher.preview(secret),
                        request.description(),
                        request.maxAttempts());

        WebhookEndpoint saved = endpoints.save(endpoint);
        log.info("owner {} registered endpoint {} -> {}", ownerId, saved.getId(), saved.getUrl());

        return new Created(saved, secret);
    }

    /**
     * @param ownerId tenant
     * @return the owner's live endpoints, newest first
     */
    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(String ownerId) {
        return endpoints.findAllByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerId);
    }

    /**
     * @param ownerId tenant
     * @param id      endpoint id
     * @return the endpoint
     * @throws ResourceNotFoundException if it does not exist or is not this owner's
     */
    @Transactional(readOnly = true)
    public WebhookEndpoint get(String ownerId, UUID id) {
        return endpoints
                .findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("endpoint", id));
    }

    /**
     * Enables or disables fan-out to an endpoint, keeping all of its history.
     *
     * <p>Disabling only affects <em>future</em> events. Deliveries already queued for this endpoint
     * continue to be retried, which is the right behaviour: "stop sending me new things" and "throw
     * away the things you already accepted and promised to deliver" are different requests, and
     * silently doing the second when asked for the first loses data.
     *
     * @param ownerId tenant
     * @param id      endpoint id
     * @param active  desired state
     * @return the updated endpoint
     */
    @Transactional
    public WebhookEndpoint setActive(String ownerId, UUID id, boolean active) {
        WebhookEndpoint endpoint = get(ownerId, id);
        endpoint.setActive(active);
        log.info("owner {} set endpoint {} active={}", ownerId, id, active);
        return endpoints.save(endpoint);
    }

    /**
     * Soft-deletes an endpoint.
     *
     * <p>A hard delete would cascade to {@code deliveries} and destroy the delivery history, so the
     * row stays and is marked deleted. Deletion is idempotent: deleting an already-deleted endpoint
     * is a 404 from {@link #get}, which is the correct answer to "delete the thing that is not
     * there".
     *
     * @param ownerId tenant
     * @param id      endpoint id
     */
    @Transactional
    public void delete(String ownerId, UUID id) {
        WebhookEndpoint endpoint = get(ownerId, id);
        endpoint.softDelete();
        endpoints.save(endpoint);
        log.info("owner {} deleted endpoint {}", ownerId, id);
    }
}
