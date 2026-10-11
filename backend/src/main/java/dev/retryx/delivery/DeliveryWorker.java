package dev.retryx.delivery;

import dev.retryx.config.RetryxProperties;
import dev.retryx.queue.DeliveryJob;
import dev.retryx.queue.DeliveryQueueDao;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The delivery loop: poll, claim, dispatch.
 *
 * <pre>
 *   every poll-interval-ms:
 *       ids  = claim up to batch-size due rows        (one short transaction, then COMMIT)
 *       jobs = load payload + url + secret for ids    (one read)
 *       dispatch jobs across `concurrency` threads    (no transaction held)
 *       wait for the batch, then schedule the next poll
 * </pre>
 *
 * <h2>Why polling is fine here</h2>
 *
 * The obvious objection is that polling an empty queue wastes queries. At a 500ms interval that is
 * two index lookups a second against a partial index over the backlog — unmeasurable next to the
 * cost of the HTTP calls this system exists to make. What polling buys in return is that the
 * <em>only</em> coordination mechanism is the database: there is no broker to run, no consumer
 * group to rebalance, no listener to reconnect, and a worker that was down for an hour catches up
 * by doing nothing special. Postgres {@code LISTEN}/{@code NOTIFY} could cut the idle latency, but
 * it is not durable — a notification sent while a worker is reconnecting is simply gone — so it can
 * only ever be an optimisation on top of polling, never a replacement for it. Retryx keeps the
 * reliable half.
 *
 * <h2>Why {@code fixedDelay} and not {@code fixedRate}</h2>
 *
 * {@code fixedRate} starts a new poll every N ms regardless of whether the last one finished. With
 * sends that can take seconds, polls would pile up, each claiming another batch, and the process
 * would quietly take on unbounded concurrent work until it fell over. {@code fixedDelay} waits for
 * the previous batch, which makes in-flight work bounded by {@code batch-size} and turns the loop
 * into natural backpressure: slow receivers slow the claim rate instead of exploding memory.
 *
 * <h2>Scaling</h2>
 *
 * Run more copies of this process. {@code SKIP LOCKED} guarantees they claim disjoint batches, so
 * no configuration, partition assignment or leader election is involved — which is the whole payoff
 * of putting the queue in the database.
 */
@Component
@ConditionalOnProperty(name = "retryx.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryQueueDao queue;
    private final DeliveryProcessor processor;
    private final WorkerIdentity identity;
    private final RetryxProperties.Worker config;
    private final ExecutorService executor;

    public DeliveryWorker(
            DeliveryQueueDao queue,
            DeliveryProcessor processor,
            WorkerIdentity identity,
            RetryxProperties properties) {
        this.queue = queue;
        this.processor = processor;
        this.identity = identity;
        this.config = properties.worker();
        this.executor = Executors.newFixedThreadPool(config.concurrency(), namedThreads());

        log.info(
                "delivery worker {} started: poll={}ms batch={} concurrency={} lease={}s",
                identity.id(),
                config.pollIntervalMs(),
                config.batchSize(),
                config.concurrency(),
                config.leaseSeconds());
    }

    /**
     * One iteration of the loop, driven by the scheduler.
     *
     * <p>{@code fixedDelayString} rather than a literal so the interval stays configurable per
     * deployment; the {@code :500} default keeps the annotation valid even if the property is
     * removed.
     */
    @Scheduled(
            initialDelayString = "${retryx.worker.initial-delay-ms:2000}",
            fixedDelayString = "${retryx.worker.poll-interval-ms:500}")
    public void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently never scheduled again by some executors,
            // and the worker would appear to "just stop". Swallow, log, poll again.
            log.error("delivery poll failed; will retry on the next tick", e);
        }
    }

    /**
     * Claims and processes one batch, synchronously.
     *
     * <p>Exposed so integration tests can disable the scheduler and drive the worker a batch at a
     * time — testing a queue against a real clock and a real scheduler is how flaky test suites are
     * built.
     *
     * @return the number of deliveries attempted in this batch
     */
    public int runOnce() {
        // --- transaction 1: claim. Commits before anything else happens. ---
        List<UUID> claimed = queue.claimDue(identity.id(), config.batchSize(), config.leaseSeconds());
        if (claimed.isEmpty()) {
            return 0;
        }

        List<DeliveryJob> jobs = queue.loadJobs(claimed);
        if (jobs.size() != claimed.size()) {
            // Possible if an event or endpoint was hard-deleted between claim and load. The rows
            // will be requeued by the reaper and then fail to load again; worth a loud log.
            log.warn("claimed {} deliveries but loaded {} jobs", claimed.size(), jobs.size());
        }

        // --- no transaction from here until each job's recorder call ---
        CompletableFuture<?>[] futures =
                jobs.stream()
                        .map(job -> CompletableFuture.runAsync(() -> processor.process(job, identity.id()), executor))
                        .toArray(CompletableFuture[]::new);

        // Waiting here is what makes in-flight work bounded. The scheduler's fixedDelay will not
        // start the next poll until this returns.
        CompletableFuture.allOf(futures).join();

        return jobs.size();
    }

    /**
     * Finishes in-flight sends on shutdown rather than abandoning them.
     *
     * <p>An abandoned send is recoverable — its lease expires and the reaper requeues it — but
     * recovery costs a lease interval of delay and risks re-sending something the receiver already
     * processed. During a planned deploy there is no reason to pay that.
     */
    @PreDestroy
    public void shutdown() {
        log.info("delivery worker {} shutting down; draining in-flight sends", identity.id());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("in-flight sends did not finish in 30s; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "retryx-send-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
