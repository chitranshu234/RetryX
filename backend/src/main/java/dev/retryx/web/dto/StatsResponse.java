package dev.retryx.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dashboard overview figures.
 *
 * <p>Not part of the original API surface — added because the dashboard needs it, and because every
 * number here is also one of the figures worth measuring under load.
 *
 * @param pending         deliveries waiting for their next attempt
 * @param inProgress      deliveries currently leased by a worker
 * @param delivered       deliveries acknowledged with a 2xx
 * @param failed          deliveries that gave up
 * @param totalEvents     events ingested by this owner
 * @param activeEndpoints live endpoints
 * @param successRate     delivered ÷ (delivered + failed), as a percentage of terminal deliveries.
 *                        Deliberately excludes in-flight rows: including them would make the number
 *                        dip every time traffic arrives, which is the opposite of a health signal.
 * @param dueNow          rows whose {@code next_attempt_at} has already passed — the real backlog
 * @param oldestDueAgeSec age of the oldest due row. The honest "are we keeping up?" metric: depth
 *                        can be high and healthy, but a rising oldest-age means falling behind.
 * @param avgResponseMs   mean receiver response time over the recent window
 * @param p95ResponseMs   95th percentile response time, which is where receiver trouble shows up
 *                        long before the mean moves
 * @param throughput      per-hour delivery counts for the last 24 hours, oldest first
 * @param generatedAt     when these numbers were computed
 */
public record StatsResponse(
        long pending,
        long inProgress,
        long delivered,
        long failed,
        long totalEvents,
        long activeEndpoints,
        double successRate,
        long dueNow,
        double oldestDueAgeSec,
        Double avgResponseMs,
        Double p95ResponseMs,
        List<ThroughputBucket> throughput,
        OffsetDateTime generatedAt) {

    /**
     * One hour of delivery activity.
     *
     * @param hour      start of the hour
     * @param delivered successes in that hour
     * @param failed    permanent failures in that hour
     * @param retried   retryable failures in that hour — shown separately because a healthy system
     *                  with a flaky receiver has a high retry count and a high success rate at the
     *                  same time, and collapsing the two hides exactly that
     */
    public record ThroughputBucket(OffsetDateTime hour, long delivered, long failed, long retried) {}
}
