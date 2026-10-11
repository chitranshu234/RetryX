package dev.retryx.repository;

import dev.retryx.domain.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Endpoint queries.
 *
 * <p>Every method here takes {@code ownerId}. That is not decoration: it is the enforcement point
 * for multi-tenant isolation. There is deliberately no {@code findById(UUID)} in use anywhere in
 * the service layer, because a lookup by primary key alone is exactly how one tenant ends up
 * reading another's data — the caller forgets the ownership check, the code still compiles, and the
 * bug is invisible until someone else's webhook URL shows up in a support ticket. Ownership is
 * expressed in the query so it cannot be forgotten.
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /**
     * @param id      endpoint id
     * @param ownerId tenant that must own it
     * @return the endpoint, or empty if it does not exist, is deleted, or belongs to someone else
     *     (all three are indistinguishable to the caller, by design)
     */
    Optional<WebhookEndpoint> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, String ownerId);

    /**
     * Like {@link #findByIdAndOwnerIdAndDeletedAtIsNull} but <em>includes</em> soft-deleted rows.
     *
     * <p>Both exist on purpose. Management endpoints must not operate on a deleted endpoint, so they
     * use the stricter method. History views must still be able to render one: a delivery that failed
     * last week against an endpoint deleted yesterday is exactly the thing somebody is looking at
     * when they open the delivery-detail page, and showing "endpoint: unknown" there would throw away
     * the most useful fact on the screen. Reads for display get this method; writes never do.
     *
     * @param id      endpoint id
     * @param ownerId tenant that must own it
     * @return the endpoint, deleted or not
     */
    Optional<WebhookEndpoint> findByIdAndOwnerId(UUID id, String ownerId);

    /**
     * @param ownerId tenant
     * @return the owner's live endpoints, newest first
     */
    List<WebhookEndpoint> findAllByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(String ownerId);

    /**
     * Fan-out target list. Called once per ingested event.
     *
     * @param ownerId tenant
     * @return the owner's endpoints that are both active and not deleted
     */
    List<WebhookEndpoint> findAllByOwnerIdAndActiveTrueAndDeletedAtIsNull(String ownerId);

    /**
     * @param ownerId tenant
     * @return count of live endpoints, for the dashboard
     */
    long countByOwnerIdAndDeletedAtIsNull(String ownerId);
}
