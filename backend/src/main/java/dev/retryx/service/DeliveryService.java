package dev.retryx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retryx.config.RetryxProperties;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.error.ConflictException;
import dev.retryx.error.ResourceNotFoundException;
import dev.retryx.repository.DeliveryAttemptRepository;
import dev.retryx.repository.DeliveryRepository;
import dev.retryx.repository.EventRepository;
import dev.retryx.repository.WebhookEndpointRepository;
import dev.retryx.web.dto.AttemptResponse;
import dev.retryx.web.dto.DeliveryDetailResponse;
import dev.retryx.web.dto.DeliveryResponse;
import dev.retryx.web.dto.EndpointResponse;
import dev.retryx.web.dto.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery reads and replay.
 *
 * <h2>The N+1 that is not here</h2>
 *
 * A page of 20 deliveries needs each one's endpoint name and event type. Fetching them per row —
 * which is what a JPA {@code @ManyToOne} would do lazily, invisibly, inside the serialiser — is 41
 * queries for one page, and the symptom is a dashboard that gets slower as the page size grows for
 * no apparent reason. Instead the ids are collected and resolved in two batch reads, giving three
 * queries regardless of page size. This is the main reason {@link Delivery} holds plain UUIDs rather
 * than associations: an N+1 you have to write by hand is an N+1 you notice.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final EventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final ObjectMapper objectMapper;
    private final RetryxProperties.Retry retryConfig;

    public DeliveryService(
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            EventRepository events,
            WebhookEndpointRepository endpoints,
            ObjectMapper objectMapper,
            RetryxProperties properties) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.events = events;
        this.endpoints = endpoints;
        this.objectMapper = objectMapper;
        this.retryConfig = properties.retry();
    }

    /**
     * Lists deliveries, newest first, optionally filtered by status.
     *
     * @param ownerId tenant
     * @param status  status filter, or null for all
     * @param page    zero-based page number
     * @param size    page size, clamped to {@value #MAX_PAGE_SIZE}
     * @return a page of deliveries with endpoint and event names resolved
     */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> list(String ownerId, DeliveryStatus status, int page, int size) {
        PageRequest request =
                PageRequest.of(
                        Math.max(0, page),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Delivery> found =
                status == null
                        ? deliveries.findAllByOwnerId(ownerId, request)
                        : deliveries.findAllByOwnerIdAndStatus(ownerId, status, request);

        List<DeliveryResponse> items = enrich(found.getContent());
        return PageResponse.of(items, found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /**
     * Everything the delivery-detail page needs.
     *
     * @param ownerId tenant
     * @param id      delivery id
     * @return state, timeline, endpoint and original payload
     * @throws ResourceNotFoundException if the delivery is not this owner's
     */
    @Transactional(readOnly = true)
    public DeliveryDetailResponse detail(String ownerId, UUID id) {
        Delivery delivery = require(ownerId, id);

        List<AttemptResponse> timeline =
                attempts.findAllByDeliveryIdOrderByAttemptNumberAsc(id).stream()
                        .map(AttemptResponse::from)
                        .toList();

        // Soft-deleted endpoints are included here on purpose: the history must stay readable.
        WebhookEndpoint endpoint =
                endpoints.findByIdAndOwnerId(delivery.getEndpointId(), ownerId).orElse(null);
        Event event = events.findByIdAndOwnerId(delivery.getEventId(), ownerId).orElse(null);

        return new DeliveryDetailResponse(
                DeliveryResponse.from(
                        delivery,
                        endpoint != null ? endpoint.getName() : null,
                        event != null ? event.getEventType() : null),
                timeline,
                endpoint != null ? EndpointResponse.from(endpoint) : null,
                event != null ? event.getEventType() : null,
                event != null ? parse(event.getPayload()) : null);
    }

    /**
     * All deliveries fanned out from one event.
     *
     * @param ownerId tenant
     * @param eventId event id
     * @return the deliveries, oldest first
     */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> forEvent(String ownerId, UUID eventId) {
        return enrich(deliveries.findAllByEventIdAndOwnerIdOrderByCreatedAtAsc(eventId, ownerId));
    }

    /**
     * Puts a terminal delivery back in the queue.
     *
     * <p>Only {@code DELIVERED} and {@code FAILED} can be replayed. A {@code PENDING} or
     * {@code IN_PROGRESS} delivery is already going to be attempted, and resetting it would either
     * do nothing or stamp over a lease a worker is currently relying on — so that is a 409, not a
     * silent no-op.
     *
     * <p>Replay <em>extends</em> the budget rather than resetting the attempt counter, so the
     * timeline of a delivery replayed twice reads 1-6, then 7-12, and the original failures remain
     * visible beside the retry. Resetting the counter would make the attempt log ambiguous about
     * which "attempt 3" is being discussed.
     *
     * <p>Replaying a {@code DELIVERED} delivery is allowed, and is the natural way to answer "the
     * receiver lost it, send it again". It will be a duplicate from the receiver's point of view,
     * which is fine: the event id is unchanged, so a receiver that deduplicates sees a repeat and
     * one that does not gets what the operator asked for.
     *
     * @param ownerId tenant
     * @param id      delivery id
     * @return the requeued delivery
     * @throws ConflictException if the delivery is still active
     */
    @Transactional
    public Delivery replay(String ownerId, UUID id) {
        Delivery delivery = require(ownerId, id);

        if (!delivery.getStatus().isTerminal()) {
            throw new ConflictException(
                    "delivery "
                            + id
                            + " is "
                            + delivery.getStatus()
                            + " and will be attempted already; only DELIVERED or FAILED deliveries can be replayed");
        }

        delivery.replay(retryConfig.maxAttempts());
        Delivery saved = deliveries.save(delivery);

        log.info(
                "owner {} replayed delivery {} (replay #{}, budget now {})",
                ownerId,
                id,
                saved.getReplayCount(),
                saved.getMaxAttempts());

        return saved;
    }

    /**
     * Replays several deliveries in one call.
     *
     * <p>Not in the original API surface; added because "retry everything that failed while the
     * receiver was down" is the actual operational need, and a dashboard button that fires 200
     * individual requests is a worse version of the same thing. Deliveries that cannot be replayed
     * are skipped rather than failing the whole batch — a partial bulk action that reports what it
     * did is more useful than one that refuses because of one ineligible row.
     *
     * @param ownerId tenant
     * @param ids     delivery ids
     * @return the ids that were actually requeued
     */
    @Transactional
    public List<UUID> replayAll(String ownerId, List<UUID> ids) {
        List<UUID> requeued = new java.util.ArrayList<>();

        for (UUID id : ids.stream().distinct().limit(500).toList()) {
            deliveries
                    .findByIdAndOwnerId(id, ownerId)
                    .filter(delivery -> delivery.getStatus().isTerminal())
                    .ifPresent(
                            delivery -> {
                                delivery.replay(retryConfig.maxAttempts());
                                deliveries.save(delivery);
                                requeued.add(delivery.getId());
                            });
        }

        log.info("owner {} bulk-replayed {} of {} requested deliveries", ownerId, requeued.size(), ids.size());
        return requeued;
    }

    /**
     * @param ownerId tenant
     * @param id      delivery id
     * @return the delivery
     * @throws ResourceNotFoundException if it does not exist or is not this owner's
     */
    private Delivery require(String ownerId, UUID id) {
        return deliveries
                .findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("delivery", id));
    }

    /**
     * Resolves endpoint names and event types for a batch of deliveries in two queries.
     *
     * <p>The ids passed to {@code findAllById} came out of rows already filtered by
     * {@code owner_id}, so they are owner-scoped by construction — this is the one place a lookup
     * by bare id is safe, and it is safe because of where the ids came from.
     *
     * @param batch deliveries to convert
     * @return API representations with display names filled in
     */
    private List<DeliveryResponse> enrich(List<Delivery> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }

        Set<UUID> endpointIds = batch.stream().map(Delivery::getEndpointId).collect(Collectors.toSet());
        Set<UUID> eventIds = batch.stream().map(Delivery::getEventId).collect(Collectors.toSet());

        Map<UUID, String> endpointNames =
                endpoints.findAllById(endpointIds).stream()
                        .collect(Collectors.toMap(WebhookEndpoint::getId, WebhookEndpoint::getName));
        Map<UUID, String> eventTypes =
                events.findAllById(eventIds).stream()
                        .collect(Collectors.toMap(Event::getId, Event::getEventType));

        return batch.stream()
                .map(
                        delivery ->
                                DeliveryResponse.from(
                                        delivery,
                                        endpointNames.get(delivery.getEndpointId()),
                                        eventTypes.get(delivery.getEventId())))
                .toList();
    }

    /**
     * @param json stored payload text
     * @return the parsed tree, or null if it cannot be parsed
     */
    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // jsonb guarantees valid JSON, so this is unreachable in practice. Returning null beats
            // failing the whole detail page if it ever does happen.
            log.warn("stored payload could not be parsed", e);
            return null;
        }
    }
}
