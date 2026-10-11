package dev.retryx.web;

import dev.retryx.security.ApiKeyPrincipal;
import dev.retryx.service.StatsService;
import dev.retryx.web.dto.StatsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/stats} — the dashboard overview.
 *
 * <p>Not part of the original API list; added for the dashboard. One endpoint rather than letting
 * the overview page assemble eight counts from the list endpoints, which would mean eight requests,
 * eight loading states, and totals computed from a page of 20 rows instead of from the table.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * @param principal authenticated caller
     * @return counts, success rate, latency percentiles and a 24-hour throughput series
     */
    @GetMapping
    public StatsResponse stats(@AuthenticationPrincipal ApiKeyPrincipal principal) {
        return statsService.forOwner(principal.ownerId());
    }
}
