package dev.retryx.service;

import dev.retryx.config.RetryxProperties;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.repository.DeliveryRepository;
import dev.retryx.repository.EventRepository;
import dev.retryx.repository.WebhookEndpointRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event and its fan-out in one transaction.
 *
 * <h2>Why this is atomic</h2>
 *
 * The event row and its delivery rows are inserted in a single transaction, so there is no instant
 * at which an event exists with no deliveries queued. If they were separate commits, a crash
 * between them would leave an event that was accepted (the producer got a 201) and that will never
 * be delivered to anyone — an event silently lost, with a successful API response as evidence that
 * it was not. Inserting the work alongside the fact is the same reasoning behind the transactional
 * outbox pattern, applied inside one database.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * {@link EventIngestionService} must be able to catch the unique-constraint violation that
 * concurrent duplicate requests produce and recover from it. Catching it requires the failing
 * transaction to have already rolled back and a new one to be available for the recovery read, which
 * is only possible if the insert happens behind a proxy boundary. A {@code this.}-call to a
 * {@code @Transactional} method in the same bean bypasses the proxy entirely, so the rollback would
 * never be scoped and the subsequent read would run inside a transaction already marked
 * rollback-only.
 */
@Service
public class EventFanoutWriter {

    private static final Logger log = LoggerFactory.getLogger(EventFanoutWriter.class);

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final RetryxProperties.Retry retryConfig;

    public EventFanoutWriter(
            EventRepository events,
            DeliveryRepository deliveries,
            WebhookEndpointRepository endpoints,
            RetryxProperties properties) {
        this.events = events;
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.retryConfig = properties.retry();
    }

    /**
     * The event and the deliveries queued for it.
     *
     * @param event      the stored event
     * @param deliveries one per active endpoint
     */
    public record Written(Event event, List<Delivery> deliveries) {}

    /**
     * Stores the event and queues one delivery per active endpoint.
     *
     * @param ownerId        tenant
     * @param eventType      producer's type label
     * @param payloadJson    JSON payload as compact text
     * @param idempotencyKey optional dedupe key
     * @param sourceIp       caller address
     * @return the event and its deliveries
     * @throws org.springframework.dao.DataIntegrityViolationException if another request with the
     *     same idempotency key committed first — the caller is expected to catch this
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Written writeAndFanOut(
            String ownerId, String eventType, String payloadJson, String idempotencyKey, String sourceIp) {

        // saveAndFlush, not save: the INSERT must hit the database inside this call so that a
        // duplicate idempotency key surfaces here as an exception the caller can handle, rather
        // than at an arbitrary later flush point (worst case: at commit, outside the try block).
        Event event =
                events.saveAndFlush(new Event(ownerId, eventType, payloadJson, idempotencyKey, sourceIp));

        List<WebhookEndpoint> targets = endpoints.findAllByOwnerIdAndActiveTrueAndDeletedAtIsNull(ownerId);

        if (targets.isEmpty()) {
            // Not an error. The event is a durable fact worth keeping; register an endpoint and
            // replay is not possible for an event that was never stored.
            log.debug("owner {} has no active endpoints; event {} stored with no deliveries", ownerId, event.getId());
            return new Written(event, List.of());
        }

        List<Delivery> queued =
                targets.stream()
                        .map(
                                endpoint ->
                                        new Delivery(
                                                ownerId,
                                                event.getId(),
                                                endpoint.getId(),
                                                endpoint.getMaxAttempts() != null
                                                        ? endpoint.getMaxAttempts()
                                                        : retryConfig.maxAttempts()))
                        .toList();

        List<Delivery> saved = deliveries.saveAll(queued);
        log.debug("event {} fanned out to {} endpoints", event.getId(), saved.size());

        return new Written(event, saved);
    }
}
