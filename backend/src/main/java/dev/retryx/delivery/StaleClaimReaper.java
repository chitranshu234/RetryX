package dev.retryx.delivery;

import dev.retryx.config.RetryxProperties;
import dev.retryx.queue.DeliveryQueueDao;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers deliveries whose worker died mid-send.
 *
 * <h2>The failure this exists for</h2>
 *
 * A worker claims 25 rows, flips them to {@code IN_PROGRESS}, and is then {@code SIGKILL}ed — OOM,
 * a node eviction, someone pulling a plug. Those 25 rows are now marked as being worked on by a
 * process that no longer exists. Nothing in the claim query will ever return them again, because
 * the claim query only looks at {@code PENDING}. Without this sweep they are lost, permanently, and
 * silently: no error is logged anywhere, the dashboard shows them as "in progress" forever, and the
 * events simply never arrive. This is the single most common bug in hand-rolled database queues.
 *
 * <h2>The mechanism</h2>
 *
 * The claim stamped a lease — {@code locked_until = now() + lease-seconds}, on the database clock.
 * A live worker finishes well inside that window. So "{@code IN_PROGRESS} and
 * {@code locked_until < now()}" means, unambiguously, that whoever held this row is not coming
 * back, and the row can be returned to {@code PENDING}. Recovery time is therefore bounded by
 * {@code lease-seconds + reaper interval} — with the defaults, 60s + 5s — and is a tuning
 * parameter rather than an accident.
 *
 * <p>The sweep uses {@code SKIP LOCKED} itself, so every worker can run its own reaper without the
 * reapers fighting each other. There is no leader to elect and no separate cron job to deploy.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * It does not increment {@code attempt_count}. The worker may have died before sending, after
 * sending, or halfway through reading the response — Retryx cannot tell which, so it does not
 * pretend to by logging an attempt that may never have happened. The honest consequence is stated
 * plainly: if the receiver had already processed the request, the requeued delivery will send it a
 * second time. That is the at-least-once contract, and it is why every webhook carries a stable
 * {@code X-Retryx-Event-Id}.
 *
 * <p>It also means a row that reliably crashes its worker is requeued forever without consuming
 * budget — a poison pill. Bounding that needs a separate lease-expiry counter, which is listed as
 * future work in DEVELOPER.md rather than pretended away here.
 */
@Component
@ConditionalOnProperty(name = "retryx.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class StaleClaimReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleClaimReaper.class);

    private final DeliveryQueueDao queue;
    private final RetryxProperties.Reaper config;
    private final MeterRegistry meters;

    public StaleClaimReaper(DeliveryQueueDao queue, RetryxProperties properties, MeterRegistry meters) {
        this.queue = queue;
        this.config = properties.reaper();
        this.meters = meters;
        log.info("stale-claim reaper started: interval={}ms batch={}", config.intervalMs(), config.batchSize());
    }

    /** One sweep, driven by the scheduler. */
    @Scheduled(
            initialDelayString = "${retryx.reaper.initial-delay-ms:5000}",
            fixedDelayString = "${retryx.reaper.interval-ms:5000}")
    public void sweep() {
        try {
            recoverOnce();
        } catch (RuntimeException e) {
            log.error("stale-claim sweep failed; will retry on the next tick", e);
        }
    }

    /**
     * Requeues one batch of expired leases.
     *
     * <p>Exposed for integration tests, which simulate a crashed worker by claiming rows with a
     * zero-second lease and then calling this directly instead of sleeping for a minute.
     *
     * @return ids that were returned to the queue
     */
    public List<UUID> recoverOnce() {
        List<UUID> recovered = queue.requeueExpiredLeases(config.batchSize());
        if (!recovered.isEmpty()) {
            meters.counter("retryx.claims.recovered").increment(recovered.size());
            log.warn(
                    "recovered {} deliveries from expired leases: {}",
                    recovered.size(),
                    recovered.size() <= 10 ? recovered : recovered.subList(0, 10) + "…");
        }
        return recovered;
    }
}
