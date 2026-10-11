package dev.retryx.repository;

import dev.retryx.domain.Event;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Event reads and the idempotency lookup. */
public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * @param id      event id
     * @param ownerId tenant that must own it
     * @return the event, or empty
     */
    Optional<Event> findByIdAndOwnerId(UUID id, String ownerId);

    /**
     * Idempotency lookup.
     *
     * <p>This is the <em>optimistic</em> half of idempotency: check first, so the common duplicate
     * returns cheaply. It is not the guarantee. Two concurrent requests with the same key can both
     * see "no existing event" here; what actually stops the double insert is the partial unique
     * index {@code uq_events_owner_idempotency_key}, whose violation the service catches and
     * resolves by re-reading through this method.
     *
     * @param ownerId        tenant
     * @param idempotencyKey client-supplied key
     * @return the event previously stored under this key, if any
     */
    Optional<Event> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    /**
     * @param ownerId  tenant
     * @param pageable page and sort
     * @return a page of the owner's events
     */
    Page<Event> findAllByOwnerId(String ownerId, Pageable pageable);

    /**
     * @param ownerId   tenant
     * @param eventType exact type filter
     * @param pageable  page and sort
     * @return a page of the owner's events of one type
     */
    Page<Event> findAllByOwnerIdAndEventType(String ownerId, String eventType, Pageable pageable);

    /**
     * @param ownerId tenant
     * @return total events for this owner
     */
    long countByOwnerId(String ownerId);
}
