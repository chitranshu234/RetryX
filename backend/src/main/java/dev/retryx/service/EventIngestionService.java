package dev.retryx.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.Event;
import dev.retryx.repository.DeliveryRepository;
import dev.retryx.repository.EventRepository;
import dev.retryx.web.dto.IngestEventRequest;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Event ingestion, with idempotency.
 *
 * <h2>Why idempotency matters here specifically</h2>
 *
 * A producer POSTs an event; the response is lost to a network timeout. The producer cannot tell
 * whether Retryx stored it or not. Its only safe move is to retry — and without idempotency that
 * retry creates a second event, a second fan-out, and a second webhook to every endpoint. The
 * failure mode is particularly nasty because it is triggered by the network being flaky, which is
 * exactly when producers retry most.
 *
 * <h2>How it is enforced</h2>
 *
 * Not by this check:
 *
 * <pre>
 *   if (!exists(key)) { insert(); }   // WRONG under concurrency
 * </pre>
 *
 * Two simultaneous requests both run {@code exists}, both see nothing, and both insert. The window
 * is small, which only means the bug is rare, reproduces under load, and is impossible to find from
 * a stack trace.
 *
 * <p>The actual guarantee is the partial unique index {@code uq_events_owner_idempotency_key}.
 * Postgres serialises the two inserts and one of them fails. This class then <em>uses</em> that
 * failure: it catches the violation and re-reads the row the winner committed, so the loser returns
 * the same event id as the winner. The pre-check that opens the method is purely an optimisation
 * for the common case, where the duplicate arrives seconds later and there is no race at all.
 *
 * <p>The constraint is scoped to {@code (owner_id, idempotency_key)}: two tenants may legitimately
 * both use {@code "order-1"}, and a global constraint would have one tenant's traffic silently
 * deduplicating against another's.
 *
 * <h2>What a duplicate returns</h2>
 *
 * {@code 200 OK} with the original event and {@code X-Idempotent-Replay: true}, not {@code 409}.
 * The producer asked for "this event exists", and after the duplicate it does; an error would push
 * every well-behaved retrying client into handling a failure that is not one. The header is there
 * for clients that want to know.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    /**
     * Cap on serialised payload size. A webhook payload is a notification, not a file transfer: the
     * payload is copied into every delivery's request body and held in memory during each send, so
     * an unbounded one multiplies across the fan-out and the retry budget.
     */
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final EventFanoutWriter writer;
    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final ObjectMapper objectMapper;

    public EventIngestionService(
            EventFanoutWriter writer,
            EventRepository events,
            DeliveryRepository deliveries,
            ObjectMapper objectMapper) {
        this.writer = writer;
        this.events = events;
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
    }

    /**
     * The outcome of an ingest call.
     *
     * @param event            the stored event — the original one, if this was a duplicate
     * @param deliveries       deliveries queued for it
     * @param idempotentReplay true when this request matched an existing idempotency key and created
     *                         nothing
     */
    public record IngestResult(Event event, List<Delivery> deliveries, boolean idempotentReplay) {}

    /**
     * Stores an event and queues its deliveries, or returns the existing event if this is a
     * duplicate.
     *
     * <p>Not {@code @Transactional}. The transaction lives one level down, in
     * {@link EventFanoutWriter}, so that this method can survive its rollback and recover. A
     * transaction here would be marked rollback-only by the constraint violation and the recovery
     * read would fail.
     *
     * @param ownerId        tenant
     * @param request        event type and payload
     * @param idempotencyKey value of the {@code Idempotency-Key} header, or null
     * @param sourceIp       caller address, for debugging
     * @return the event, its deliveries, and whether this was a duplicate
     * @throws IllegalArgumentException if the payload is not a JSON object/array or is too large
     */
    public IngestResult ingest(
            String ownerId, IngestEventRequest request, String idempotencyKey, String sourceIp) {

        String payloadJson = serialisePayload(request.payload());
        String key = normalise(idempotencyKey);

        // Fast path: the duplicate arrived well after the original.
        if (key != null) {
            Optional<IngestResult> existing = findExisting(ownerId, key);
            if (existing.isPresent()) {
                log.debug("idempotent replay for owner {} key {}", ownerId, key);
                return existing.get();
            }
        }

        try {
            EventFanoutWriter.Written written =
                    writer.writeAndFanOut(ownerId, request.eventType(), payloadJson, key, sourceIp);
            log.info(
                    "ingested event {} ({}) for owner {}; {} deliveries queued",
                    written.event().getId(),
                    request.eventType(),
                    ownerId,
                    written.deliveries().size());
            return new IngestResult(written.event(), written.deliveries(), false);

        } catch (DataIntegrityViolationException e) {
            // Slow path: two duplicates raced, and the database picked a winner. Find their row.
            if (key == null) {
                throw e; // not an idempotency conflict — some other constraint, so surface it
            }
            return findExisting(ownerId, key)
                    .orElseThrow(
                            () ->
                                    // The violation was on the idempotency index but the row is not
                                    // readable. Nothing sensible to recover; let it 500 loudly rather
                                    // than silently return something wrong.
                                    new IllegalStateException(
                                            "idempotency conflict for key '" + key + "' but no stored event found", e));
        }
    }

    /**
     * Looks up a previously stored event by idempotency key, with its deliveries.
     *
     * @param ownerId tenant
     * @param key     idempotency key
     * @return the result to return as a replay, if the key has been used
     */
    private Optional<IngestResult> findExisting(String ownerId, String key) {
        return events
                .findByOwnerIdAndIdempotencyKey(ownerId, key)
                .map(
                        event ->
                                new IngestResult(
                                        event,
                                        deliveries.findAllByEventIdAndOwnerIdOrderByCreatedAtAsc(event.getId(), ownerId),
                                        true));
    }

    /**
     * @param payload parsed request payload
     * @return compact JSON text to store in {@code jsonb}
     */
    private String serialisePayload(JsonNode payload) {
        if (!payload.isObject() && !payload.isArray()) {
            throw new IllegalArgumentException("payload must be a JSON object or array");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload could not be serialised: " + e.getOriginalMessage());
        }
        if (json.length() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "payload is " + json.length() + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }
        return json;
    }

    private String normalise(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be 255 characters or fewer");
        }
        return trimmed;
    }
}
