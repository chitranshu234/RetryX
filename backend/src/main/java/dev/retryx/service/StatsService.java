package dev.retryx.service;

import dev.retryx.queue.DeliveryQueueDao;
import dev.retryx.web.dto.StatsResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates for the dashboard overview.
 *
 * <p>Raw SQL again, for a different reason than the queue: these are aggregate queries, which is
 * what SQL is for and what JPQL is bad at. {@code count(*) FILTER (WHERE …)} and
 * {@code percentile_cont} have no JPQL equivalent at all, so the alternatives are one query per
 * figure (eight round trips for one page) or loading rows into the JVM and counting them there
 * (which stops working the moment there is real data). One grouped scan per figure, computed where
 * the data lives, is the only version that stays correct as the table grows.
 *
 * <h2>Honesty about these numbers</h2>
 *
 * <ul>
 *   <li>Success rate counts only <em>terminal</em> deliveries. Including pending ones would make the
 *       number drop every time traffic arrives and recover as it drained, which looks like an
 *       incident and is not one.
 *   <li>Retries are a separate series from failures. A system with a flaky receiver has lots of
 *       retries <em>and</em> a high success rate at the same time; folding them together would hide
 *       precisely the behaviour the retry engine exists to produce.
 *   <li>p95 is reported next to the mean. Receiver trouble shows up in the tail long before the mean
 *       moves, so a mean-only latency chart is a lagging indicator.
 * </ul>
 */
@Service
public class StatsService {

    private static final String STATUS_COUNTS_SQL =
            """
            SELECT status, count(*) AS total
              FROM deliveries
             WHERE owner_id = :ownerId
             GROUP BY status
            """;

    private static final String RESPONSE_TIME_SQL =
            """
            SELECT avg(a.response_time_ms)                                          AS avg_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY a.response_time_ms) AS p95_ms
              FROM delivery_attempts a
              JOIN deliveries d ON d.id = a.delivery_id
             WHERE d.owner_id = :ownerId
               AND a.attempted_at >= now() - interval '24 hours'
               AND a.response_time_ms IS NOT NULL
            """;

    /**
     * Hourly activity for the last 24 hours.
     *
     * <p>Grouped over {@code delivery_attempts}, not {@code deliveries}, because the question is
     * "what happened, and when" — and a delivery retried four times contributed four events to the
     * timeline at four different moments. Grouping the mutable {@code deliveries} table by
     * {@code updated_at} would attribute all of that to one instant, and would lose it on the next
     * retry.
     *
     * <p>{@code AT TIME ZONE 'UTC'} before truncating is not decoration. {@code date_trunc} works in
     * the session's time zone, so the same query run on a machine set to Asia/Kolkata would produce
     * buckets at 30 minutes past each UTC hour — which then fail to match hour boundaries generated
     * in Java and silently drop or double-count rows. Pinning the query to UTC and generating the
     * Java series in UTC makes the two agree by construction.
     */
    private static final String THROUGHPUT_SQL =
            """
            SELECT date_trunc('hour', a.attempted_at AT TIME ZONE 'UTC')             AS bucket,
                   count(*) FILTER (WHERE a.outcome = 'SUCCESS')                      AS delivered,
                   count(*) FILTER (WHERE a.outcome IN ('PERMANENT_ERROR', 'BLOCKED')) AS failed,
                   count(*) FILTER (WHERE a.outcome = 'RETRYABLE_ERROR')              AS retried
              FROM delivery_attempts a
              JOIN deliveries d ON d.id = a.delivery_id
             WHERE d.owner_id = :ownerId
               AND a.attempted_at >= now() - interval '24 hours'
             GROUP BY bucket
             ORDER BY bucket
            """;

    private static final String TOTALS_SQL =
            """
            SELECT (SELECT count(*) FROM events WHERE owner_id = :ownerId)           AS total_events,
                   (SELECT count(*) FROM webhook_endpoints
                     WHERE owner_id = :ownerId AND deleted_at IS NULL AND is_active) AS active_endpoints
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DeliveryQueueDao queue;

    public StatsService(NamedParameterJdbcTemplate jdbc, DeliveryQueueDao queue) {
        this.jdbc = jdbc;
        this.queue = queue;
    }

    /**
     * Computes the overview for one owner.
     *
     * @param ownerId tenant
     * @return the figures the dashboard renders
     */
    @Transactional(readOnly = true)
    public StatsResponse forOwner(String ownerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("ownerId", ownerId);

        Map<String, Long> byStatus = new HashMap<>();
        RowMapper<Void> statusRow =
                (rs, index) -> {
                    byStatus.put(rs.getString("status"), rs.getLong("total"));
                    return null;
                };
        jdbc.query(STATUS_COUNTS_SQL, params, statusRow);

        long pending = byStatus.getOrDefault("PENDING", 0L);
        long inProgress = byStatus.getOrDefault("IN_PROGRESS", 0L);
        long delivered = byStatus.getOrDefault("DELIVERED", 0L);
        long failed = byStatus.getOrDefault("FAILED", 0L);

        long terminal = delivered + failed;
        double successRate = terminal == 0 ? 100.0 : (delivered * 100.0) / terminal;

        List<Double[]> latency =
                jdbc.query(
                        RESPONSE_TIME_SQL,
                        params,
                        (rs, index) -> {
                            // wasNull() must be checked immediately after each read: avg() and
                            // percentile_cont() both return SQL NULL when no rows matched, and
                            // getDouble() would quietly report 0.0 — an empty window would look like
                            // a perfectly fast one.
                            double avg = rs.getDouble("avg_ms");
                            Double avgOrNull = rs.wasNull() ? null : avg;
                            double p95 = rs.getDouble("p95_ms");
                            Double p95OrNull = rs.wasNull() ? null : p95;
                            return new Double[] {avgOrNull, p95OrNull};
                        });
        Double avgMs = latency.isEmpty() ? null : latency.get(0)[0];
        Double p95Ms = latency.isEmpty() ? null : latency.get(0)[1];

        List<long[]> totals =
                jdbc.query(
                        TOTALS_SQL,
                        params,
                        (rs, index) -> new long[] {rs.getLong("total_events"), rs.getLong("active_endpoints")});
        long totalEvents = totals.isEmpty() ? 0L : totals.get(0)[0];
        long activeEndpoints = totals.isEmpty() ? 0L : totals.get(0)[1];

        return new StatsResponse(
                pending,
                inProgress,
                delivered,
                failed,
                totalEvents,
                activeEndpoints,
                round(successRate),
                queue.countDueNow(),
                round(queue.oldestDueAgeSeconds()),
                round(avgMs),
                round(p95Ms),
                throughput(ownerId),
                OffsetDateTime.now());
    }

    /**
     * Hourly series for the last 24 hours, with empty hours filled in.
     *
     * <p>Postgres returns no row for an hour in which nothing happened. Handing that straight to a
     * chart draws a line from 09:00 to 14:00 as though the hours between did not exist, which
     * silently renders a five-hour outage as a gentle slope. The gaps are filled with zeros so quiet
     * periods look quiet.
     *
     * @param ownerId tenant
     * @return exactly 24 buckets, oldest first, in UTC
     */
    private List<StatsResponse.ThroughputBucket> throughput(String ownerId) {
        Map<OffsetDateTime, long[]> observed = new HashMap<>();
        RowMapper<Void> bucketRow =
                (rs, index) -> {
                    // The query already converted to UTC, so this comes back as a naive timestamp
                    // that is known to be UTC. Attaching the offset explicitly keeps it comparable
                    // with the generated series.
                    LocalDateTime naive = rs.getObject("bucket", LocalDateTime.class);
                    observed.put(
                            naive.atOffset(ZoneOffset.UTC),
                            new long[] {rs.getLong("delivered"), rs.getLong("failed"), rs.getLong("retried")});
                    return null;
                };
        jdbc.query(THROUGHPUT_SQL, new MapSqlParameterSource("ownerId", ownerId), bucketRow);

        OffsetDateTime currentHour = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        List<StatsResponse.ThroughputBucket> series = new ArrayList<>(24);
        for (int offset = 23; offset >= 0; offset--) {
            OffsetDateTime hour = currentHour.minusHours(offset);
            long[] counts = observed.get(hour);
            series.add(
                    new StatsResponse.ThroughputBucket(
                            hour,
                            counts == null ? 0 : counts[0],
                            counts == null ? 0 : counts[1],
                            counts == null ? 0 : counts[2]));
        }
        return series;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static Double round(Double value) {
        return value == null ? null : round(value.doubleValue());
    }
}
