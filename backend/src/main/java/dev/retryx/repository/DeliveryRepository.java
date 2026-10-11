package dev.retryx.repository;

import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Delivery reads and writes for the API and the worker's completion step.
 *
 * <p>Claiming is <em>not</em> here. {@code FOR UPDATE SKIP LOCKED} with
 * {@code UPDATE … RETURNING} has no sensible JPA expression, and dressing it up as a repository
 * method would hide the one query whose exact SQL matters most. It lives in
 * {@link dev.retryx.queue.DeliveryQueueDao} as plain SQL instead.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /**
     * @param id      delivery id
     * @param ownerId tenant that must own it
     * @return the delivery, or empty
     */
    Optional<Delivery> findByIdAndOwnerId(UUID id, String ownerId);

    /**
     * @param eventId event id
     * @param ownerId tenant that must own it
     * @return every delivery fanned out from one event
     */
    List<Delivery> findAllByEventIdAndOwnerIdOrderByCreatedAtAsc(UUID eventId, String ownerId);

    /**
     * @param ownerId  tenant
     * @param pageable page and sort
     * @return a page of the owner's deliveries
     */
    Page<Delivery> findAllByOwnerId(String ownerId, Pageable pageable);

    /**
     * @param ownerId  tenant
     * @param status   status filter
     * @param pageable page and sort
     * @return a page of the owner's deliveries in one status
     */
    Page<Delivery> findAllByOwnerIdAndStatus(String ownerId, DeliveryStatus status, Pageable pageable);

    /**
     * @param ownerId tenant
     * @param status  status to count
     * @return number of the owner's deliveries in that status
     */
    long countByOwnerIdAndStatus(String ownerId, DeliveryStatus status);

    /**
     * @param eventId event id
     * @return number of deliveries already fanned out for this event
     */
    long countByEventId(UUID eventId);
}
