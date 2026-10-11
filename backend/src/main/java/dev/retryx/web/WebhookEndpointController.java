package dev.retryx.web;

import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.security.ApiKeyPrincipal;
import dev.retryx.service.WebhookEndpointService;
import dev.retryx.web.dto.CreateEndpointRequest;
import dev.retryx.web.dto.CreatedEndpointResponse;
import dev.retryx.web.dto.EndpointResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/webhooks} — register, list and remove destinations.
 *
 * <p>The owner is taken from {@code @AuthenticationPrincipal}, never from the request. There is no
 * {@code ownerId} parameter anywhere in this controller, which is the structural reason a caller
 * cannot ask for someone else's endpoints: the parameter they would need to tamper with does not
 * exist.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookEndpointController {

    private final WebhookEndpointService service;

    public WebhookEndpointController(WebhookEndpointService service) {
        this.service = service;
    }

    /**
     * Registers an endpoint and returns its signing secret.
     *
     * <p>201, with the plaintext secret in the body — the only response that ever contains it.
     *
     * @param principal authenticated caller
     * @param request   name, URL and options
     * @return 201 with the endpoint and its secret
     */
    @PostMapping
    public ResponseEntity<CreatedEndpointResponse> create(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @Valid @RequestBody CreateEndpointRequest request) {

        WebhookEndpointService.Created created = service.create(principal.ownerId(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new CreatedEndpointResponse(
                                EndpointResponse.from(created.endpoint()), created.plaintextSecret()));
    }

    /**
     * @param principal authenticated caller
     * @return the caller's endpoints, newest first
     */
    @GetMapping
    public List<EndpointResponse> list(@AuthenticationPrincipal ApiKeyPrincipal principal) {
        return service.list(principal.ownerId()).stream().map(EndpointResponse::from).toList();
    }

    /**
     * @param principal authenticated caller
     * @param id        endpoint id
     * @return the endpoint
     */
    @GetMapping("/{id}")
    public EndpointResponse get(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        return EndpointResponse.from(service.get(principal.ownerId(), id));
    }

    /**
     * Enables or disables fan-out to an endpoint.
     *
     * <p>Not in the original API list, but the dashboard's "disable" needs it, and disabling is the
     * right answer far more often than deleting: it stops new events without discarding the ones
     * already queued or the history of what was sent.
     *
     * @param principal authenticated caller
     * @param id        endpoint id
     * @param active    desired state, from {@code ?active=false}
     * @return the updated endpoint
     */
    @PutMapping("/{id}/active")
    public EndpointResponse setActive(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam boolean active) {
        return EndpointResponse.from(service.setActive(principal.ownerId(), id, active));
    }

    /**
     * Soft-deletes an endpoint, keeping its delivery history.
     *
     * @param principal authenticated caller
     * @param id        endpoint id
     * @return 204
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.ownerId(), id);
        return ResponseEntity.noContent().build();
    }
}
