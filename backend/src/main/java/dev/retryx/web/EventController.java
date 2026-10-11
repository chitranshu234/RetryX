package dev.retryx.web;

import dev.retryx.security.ApiKeyPrincipal;
import dev.retryx.service.DeliveryService;
import dev.retryx.service.EventIngestionService;
import dev.retryx.service.EventQueryService;
import dev.retryx.web.dto.DeliveryResponse;
import dev.retryx.web.dto.EventResponse;
import dev.retryx.web.dto.IngestEventRequest;
import dev.retryx.web.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/events} — ingestion and event reads. */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIngestionService ingestion;
    private final EventQueryService queries;
    private final DeliveryService deliveries;

    public EventController(
            EventIngestionService ingestion, EventQueryService queries, DeliveryService deliveries) {
        this.ingestion = ingestion;
        this.queries = queries;
        this.deliveries = deliveries;
    }

    /**
     * Ingests an event and queues a delivery for each active endpoint.
     *
     * <h2>Status codes</h2>
     *
     * <ul>
     *   <li><strong>201</strong> — a new event was stored.
     *   <li><strong>200</strong> with {@code X-Idempotent-Replay: true} — this
     *       {@code Idempotency-Key} has been seen before; the original event is returned and nothing
     *       new was created.
     * </ul>
     *
     * <p>A duplicate is not an error. A producer retrying after a lost response is doing the right
     * thing, and the honest answer is "that event exists, here it is". Returning 409 would force
     * every well-behaved client to implement error handling for a successful outcome. The distinct
     * status code and header are there for clients that do want to tell the difference.
     *
     * @param principal      authenticated caller
     * @param request        event type and payload
     * @param idempotencyKey optional {@code Idempotency-Key} header
     * @param httpRequest    used only to record the caller's address
     * @return 201 for a new event, 200 for an idempotent replay
     */
    @PostMapping
    public ResponseEntity<EventResponse> ingest(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @Valid @RequestBody IngestEventRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        EventIngestionService.IngestResult result =
                ingestion.ingest(principal.ownerId(), request, idempotencyKey, callerAddress(httpRequest));

        EventResponse body = queries.get(principal.ownerId(), result.event().getId());

        return ResponseEntity.status(result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("X-Idempotent-Replay", Boolean.toString(result.idempotentReplay()))
                .body(body);
    }

    /**
     * @param principal authenticated caller
     * @param eventType optional exact type filter
     * @param page      zero-based page number
     * @param size      page size
     * @return a page of events, newest first
     */
    @GetMapping
    public PageResponse<EventResponse> list(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(principal.ownerId(), eventType, page, size);
    }

    /**
     * @param principal authenticated caller
     * @param id        event id
     * @return the event with its fan-out
     */
    @GetMapping("/{id}")
    public EventResponse get(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        return queries.get(principal.ownerId(), id);
    }

    /**
     * Every delivery created from one event.
     *
     * @param principal authenticated caller
     * @param id        event id
     * @return the deliveries, oldest first
     */
    @GetMapping("/{id}/deliveries")
    public List<DeliveryResponse> deliveriesForEvent(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        return deliveries.forEvent(principal.ownerId(), id);
    }

    /**
     * Best-effort caller address for debugging.
     *
     * <p>{@code X-Forwarded-For} is read when present because behind a load balancer
     * {@code getRemoteAddr()} is the balancer. It is <strong>not</strong> trusted for anything: the
     * header is client-supplied and trivially forged, so it is stored as a debugging hint and never
     * used for authorisation, rate limiting or allowlisting.
     *
     * @param request the servlet request
     * @return an address string, truncated to fit the column
     */
    private String callerAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String address =
                (forwarded != null && !forwarded.isBlank())
                        ? forwarded.split(",")[0].trim()
                        : request.getRemoteAddr();
        if (address == null) {
            return null;
        }
        return address.length() > 60 ? address.substring(0, 60) : address;
    }
}
