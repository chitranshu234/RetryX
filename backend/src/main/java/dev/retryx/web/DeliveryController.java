package dev.retryx.web;

import dev.retryx.domain.DeliveryStatus;
import dev.retryx.security.ApiKeyPrincipal;
import dev.retryx.service.DeliveryService;
import dev.retryx.web.dto.DeliveryDetailResponse;
import dev.retryx.web.dto.DeliveryResponse;
import dev.retryx.web.dto.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/deliveries} — delivery reads and replay. */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveries;

    public DeliveryController(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    /**
     * Lists deliveries, newest first.
     *
     * @param principal authenticated caller
     * @param status    optional status filter — {@code PENDING}, {@code IN_PROGRESS},
     *                  {@code DELIVERED} or {@code FAILED}
     * @param page      zero-based page number
     * @param size      page size
     * @return a page of deliveries
     */
    @GetMapping
    public PageResponse<DeliveryResponse> list(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return deliveries.list(principal.ownerId(), status, page, size);
    }

    /**
     * One delivery with its full attempt timeline.
     *
     * @param principal authenticated caller
     * @param id        delivery id
     * @return state, timeline, endpoint and the original payload
     */
    @GetMapping("/{id}")
    public DeliveryDetailResponse detail(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        return deliveries.detail(principal.ownerId(), id);
    }

    /**
     * Puts a terminal delivery back in the queue.
     *
     * <p>Returns the delivery in its new {@code PENDING} state rather than 202, so the UI can render
     * the change without a second request. A worker will pick it up within one poll interval.
     *
     * @param principal authenticated caller
     * @param id        delivery id
     * @return the requeued delivery
     */
    @PostMapping("/{id}/replay")
    public DeliveryResponse replay(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.replay(principal.ownerId(), id), null, null);
    }

    /**
     * Replays several deliveries at once.
     *
     * <p>An addition to the original API surface: "retry everything that failed while the receiver
     * was down" is the real operational need, and a dashboard button that fires 200 separate requests
     * is a worse implementation of the same thing. Ineligible ids are skipped rather than failing the
     * batch, and the response reports exactly which ones were requeued.
     *
     * @param principal authenticated caller
     * @param body      {@code {"ids": ["…", "…"]}}
     * @return the ids that were requeued, and how many
     */
    @PostMapping("/replay")
    public Map<String, Object> replayBulk(
            @AuthenticationPrincipal ApiKeyPrincipal principal, @RequestBody Map<String, List<UUID>> body) {

        List<UUID> ids = body.getOrDefault("ids", List.of());
        List<UUID> requeued = deliveries.replayAll(principal.ownerId(), ids);

        return Map.of(
                "requested", ids.size(),
                "replayed", requeued.size(),
                "ids", requeued);
    }
}
