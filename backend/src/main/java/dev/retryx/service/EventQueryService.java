package dev.retryx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retryx.domain.Event;
import dev.retryx.error.ResourceNotFoundException;
import dev.retryx.repository.EventRepository;
import dev.retryx.web.dto.EventResponse;
import dev.retryx.web.dto.PageResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Event reads for the API and the dashboard. */
@Service
public class EventQueryService {

    private static final Logger log = LoggerFactory.getLogger(EventQueryService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final EventRepository events;
    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public EventQueryService(
            EventRepository events, DeliveryService deliveryService, ObjectMapper objectMapper) {
        this.events = events;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists events, newest first.
     *
     * <p>Deliveries are deliberately omitted from list items. Including them would mean a query per
     * event — the classic N+1 — and the events list in the dashboard exists to be scanned, with
     * fan-out detail one click away.
     *
     * @param ownerId   tenant
     * @param eventType optional exact type filter
     * @param page      zero-based page number
     * @param size      page size, clamped to {@value #MAX_PAGE_SIZE}
     * @return a page of events
     */
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> list(String ownerId, String eventType, int page, int size) {
        PageRequest request =
                PageRequest.of(
                        Math.max(0, page),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Event> found =
                (eventType == null || eventType.isBlank())
                        ? events.findAllByOwnerId(ownerId, request)
                        : events.findAllByOwnerIdAndEventType(ownerId, eventType.trim(), request);

        return PageResponse.of(found, event -> toResponse(event, null));
    }

    /**
     * One event, with its fan-out.
     *
     * @param ownerId tenant
     * @param id      event id
     * @return the event and every delivery created from it
     * @throws ResourceNotFoundException if it does not exist or is not this owner's
     */
    @Transactional(readOnly = true)
    public EventResponse get(String ownerId, UUID id) {
        Event event =
                events
                        .findByIdAndOwnerId(id, ownerId)
                        .orElseThrow(() -> new ResourceNotFoundException("event", id));

        return toResponse(event, deliveryService.forEvent(ownerId, id));
    }

    private EventResponse toResponse(Event event, List<dev.retryx.web.dto.DeliveryResponse> deliveries) {
        return new EventResponse(
                event.getId(),
                event.getEventType(),
                parse(event.getPayload()),
                event.getIdempotencyKey(),
                event.getCreatedAt(),
                deliveries);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("stored payload could not be parsed", e);
            return null;
        }
    }
}
