package dev.retryx.queue;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue. Raw SQL, on purpose.
 *
 * <p>Everything else in Retryx uses Spring Data JPA, because entity-per-row CRUD is what JPA is
 * good at. These four statements are the exception: they are the concurrency control for the whole
 * system, their exact text is what makes multi-worker delivery safe, and hiding them behind a
 * derived query name would be hiding the most important code in the project.
 *
 * <h2>Why {@code FOR UPDATE SKIP LOCKED}</h2>
 *
 * Several worker processes poll the same table and must never hand the same row to two senders.
 * The alternatives:
 *
 * <ul>
 *   <li><strong>Plain {@code SELECT … FOR UPDATE}.</strong> Correct, and serial. Worker B's query
 *       blocks on the rows worker A locked, waits for A's transaction, then re-reads them and finds
 *       them taken. Throughput does not improve as workers are added — it degrades, because every
 *       worker queues behind the same hot rows.
 *   <li><strong>{@code SELECT} then {@code UPDATE … WHERE status = 'PENDING'}</strong> and trust
 *       the row count. This actually works, but every loser of a race has done a wasted round trip,
 *       and under contention most attempts are losers.
 *   <li><strong>Advisory locks.</strong> One lock per row id; works, but the locks live outside the
 *       table so a crash analysis has to correlate two sources of truth, and {@code pg_locks} is
 *       not a queue.
 *   <li><strong>A broker (Kafka, RabbitMQ, SQS).</strong> The right answer at a different scale.
 *       It also means the delivery state and the queue state are in two systems that can disagree,
 *       plus a second thing to operate. At this size the database is already a durable,
 *       transactional, queryable queue.
 * </ul>
 *
 * {@code SKIP LOCKED} tells Postgres: lock what you can, step over anything already locked, never
 * block. Each worker gets a disjoint batch on the first try, and throughput scales with workers
 * until the database or the receivers saturate.
 *
 * <h2>Why the lease uses the database clock</h2>
 *
 * {@code locked_until} is computed as {@code now() + lease}, evaluated by Postgres. Workers never
 * write a lease timestamp from their own clock. A lease is a distributed mutual-exclusion
 * primitive, and the only way several machines can agree on when it expires is to read the time
 * from one shared place. If worker A's clock ran two minutes fast it would consider A's own lease
 * expired early and happily re-send another worker's in-flight delivery. (Backoff, by contrast, is
 * computed in Java — a few milliseconds of skew on "retry in 8 seconds" is of no consequence. The
 * asymmetry is deliberate.)
 *
 * @see dev.retryx.delivery.DeliveryWorker the loop that calls this
 */
@Repository
public class DeliveryQueueDao {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueueDao.class);

    /**
     * Claim due work for this worker.
     *
     * <p>Read it inside-out:
     *
     * <ol>
     *   <li>The CTE finds up to {@code batchSize} rows that are {@code PENDING} and whose
     *       {@code next_attempt_at} has arrived, oldest first, locking each one and skipping any row
     *       another worker already holds.
     *   <li>The outer {@code UPDATE} flips exactly those rows to {@code IN_PROGRESS} and stamps the
     *       lease. It cannot block: the CTE already holds the locks.
     *   <li>{@code RETURNING} hands back the ids in the same round trip. No second query is needed
     *       to discover what we got, and no window exists in which rows are claimed but unknown.
     * </ol>
     *
     * <p>Note the ordering of the clauses in the CTE: {@code ORDER BY}, then {@code LIMIT}, then
     * {@code FOR UPDATE SKIP LOCKED}. Postgres requires the locking clause last.
     *
     * <p>{@code ORDER BY next_attempt_at} gives rough FIFO fairness and, together with the partial
     * index {@code idx_deliveries_claim}, makes this an index scan over the backlog rather than a
     * sequential scan over all history.
     */
    private static final String CLAIM_SQL =
            """
            WITH due AS (
                SELECT id
                  FROM deliveries
                 WHERE status = 'PENDING'
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE deliveries d
               SET status       = 'IN_PROGRESS',
                   locked_by    = :workerId,
                   locked_until = now() + (CAST(:leaseSeconds AS double precision) * interval '1 second'),
                   updated_at   = now()
              FROM due
             WHERE d.id = due.id
            RETURNING d.id
            """;

    /** Joins in everything a send needs: the event's payload and the endpoint's URL and secret. */
    private static final String LOAD_JOBS_SQL =
            """
            SELECT d.id            AS delivery_id,
                   d.owner_id      AS owner_id,
                   d.event_id      AS event_id,
                   d.endpoint_id   AS endpoint_id,
                   d.attempt_count AS attempt_count,
                   d.max_attempts  AS max_attempts,
                   e.event_type    AS event_type,
                   e.payload::text AS payload_json,
                   e.created_at    AS event_created_at,
                   w.name          AS endpoint_name,
                   w.url           AS url,
                   w.encrypted_secret AS encrypted_secret
              FROM deliveries d
              JOIN events e            ON e.id = d.event_id
              JOIN webhook_endpoints w ON w.id = d.endpoint_id
             WHERE d.id IN (:ids)
            """;

    /**
     * Stale-claim recovery.
     *
     * <p>A worker that is killed mid-send leaves its rows {@code IN_PROGRESS} with a lease that
     * nobody will ever release. Without this query they are stuck forever — the single most common
     * way a hand-rolled queue silently loses work.
     *
     * <p>The sweep is itself a {@code SKIP LOCKED} claim, so several workers can run the reaper
     * concurrently without fighting over the same stale rows.
     *
     * <p>{@code next_attempt_at = now()} makes a recovered row immediately eligible, so recovery
     * time is bounded by {@code lease-seconds + reaper interval} and nothing else.
     *
     * <p>The right-hand side of a Postgres {@code SET} sees the <em>old</em> row, which is why
     * {@code last_error} can quote the {@code locked_by} that is being cleared in the same
     * statement.
     */
    private static final String REQUEUE_STALE_SQL =
            """
            WITH stale AS (
                SELECT id
                  FROM deliveries
                 WHERE status = 'IN_PROGRESS'
                   AND locked_until < now()
                 ORDER BY locked_until
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE deliveries d
               SET status          = 'PENDING',
                   locked_by       = NULL,
                   locked_until    = NULL,
                   next_attempt_at = now(),
                   last_error      = 'lease expired; requeued after worker '
                                     || COALESCE(d.locked_by, 'unknown')
                                     || ' stopped reporting',
                   updated_at      = now()
              FROM stale
             WHERE d.id = stale.id
            RETURNING d.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryQueueDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically claims up to {@code batchSize} due deliveries for this worker.
     *
     * <p>{@code REQUIRES_NEW} and a deliberately tiny transaction: this method must commit before
     * any HTTP call begins. Holding the claim transaction open across the network wait would pin a
     * pool connection for the duration of a potentially ten-second receiver timeout, keep the row
     * locks held (defeating {@code SKIP LOCKED} for everyone else), and bloat Postgres's dead-tuple
     * horizon. <em>Claim, commit, then send</em> is the rule the whole worker design hangs on.
     *
     * @param workerId     identity written into {@code locked_by}
     * @param batchSize    maximum rows to take
     * @param leaseSeconds how long the claim is honoured
     * @return ids of the rows now owned by this worker; empty when there is no due work
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimDue(String workerId, int batchSize, int leaseSeconds) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("workerId", workerId)
                        .addValue("batchSize", batchSize)
                        .addValue("leaseSeconds", leaseSeconds);

        List<UUID> claimed = jdbc.query(CLAIM_SQL, params, (rs, i) -> rs.getObject("id", UUID.class));

        if (!claimed.isEmpty()) {
            log.debug("worker {} claimed {} deliveries", workerId, claimed.size());
        }
        return claimed;
    }

    /**
     * Loads the full job for each claimed id.
     *
     * @param deliveryIds ids returned by {@link #claimDue}
     * @return one job per id, in no particular order
     */
    @Transactional(readOnly = true)
    public List<DeliveryJob> loadJobs(Collection<UUID> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return Collections.emptyList();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", deliveryIds);
        return jdbc.query(
                LOAD_JOBS_SQL,
                params,
                (rs, i) ->
                        new DeliveryJob(
                                rs.getObject("delivery_id", UUID.class),
                                rs.getString("owner_id"),
                                rs.getObject("event_id", UUID.class),
                                rs.getObject("endpoint_id", UUID.class),
                                rs.getString("endpoint_name"),
                                rs.getString("event_type"),
                                rs.getString("payload_json"),
                                rs.getObject("event_created_at", java.time.OffsetDateTime.class),
                                rs.getString("url"),
                                rs.getString("encrypted_secret"),
                                rs.getInt("attempt_count"),
                                rs.getInt("max_attempts")));
    }

    /**
     * Returns deliveries whose lease has expired to {@code PENDING}.
     *
     * @param batchSize maximum rows to recover in one sweep
     * @return ids recovered, so the caller can log and count them
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> requeueExpiredLeases(int batchSize) {
        MapSqlParameterSource params = new MapSqlParameterSource("batchSize", batchSize);
        return jdbc.query(REQUEUE_STALE_SQL, params, (rs, i) -> rs.getObject("id", UUID.class));
    }

    /**
     * Queue depth, for metrics and the dashboard header.
     *
     * @return rows that are PENDING with {@code next_attempt_at} already passed
     */
    @Transactional(readOnly = true)
    public long countDueNow() {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM deliveries WHERE status = 'PENDING' AND next_attempt_at <= now()",
                        Map.of(),
                        Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Age of the oldest due delivery. The honest measure of whether workers are keeping up:
     * queue <em>depth</em> can be large and healthy, but a growing oldest-age means falling behind.
     *
     * @return seconds since the oldest due row became due, or 0 when the queue is empty
     */
    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds() {
        Double age =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(next_attempt_at))), 0)
                          FROM deliveries
                         WHERE status = 'PENDING'
                           AND next_attempt_at <= now()
                        """,
                        Map.of(),
                        Double.class);
        return age == null ? 0d : age;
    }

    /**
     * @return rows currently leased by some worker
     */
    @Transactional(readOnly = true)
    public long countInFlight() {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM deliveries WHERE status = 'IN_PROGRESS'", Map.of(), Long.class);
        return count == null ? 0L : count;
    }
}
