package dev.retryx.delivery;

import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryAttempt;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.queue.DeliveryJob;
import dev.retryx.repository.DeliveryAttemptRepository;
import dev.retryx.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the result of one attempt: appends to the attempt log and moves the delivery's state
 * machine. Runs in its own short transaction, <strong>after</strong> the HTTP call has finished.
 *
 * <h2>Why this is a separate bean from {@link DeliveryProcessor}</h2>
 *
 * Not style — correctness. Spring's {@code @Transactional} is implemented with a proxy, and a proxy
 * only intercepts calls that arrive from outside the object. Had {@code process()} and
 * {@code record()} been two methods on one bean, {@code this.record(...)} would bypass the proxy
 * entirely and run with <em>no transaction at all</em>: the attempt insert and the delivery update
 * would be two separate autocommits, and a crash between them would leave an attempt logged against
 * a delivery still marked {@code IN_PROGRESS}. The bug is silent, the code looks right, and it is
 * one of the most common ways Spring transactions are quietly lost. Separating the beans makes the
 * call cross a proxy boundary, so the annotation actually does something.
 *
 * <h2>Why the lease is re-checked here</h2>
 *
 * Between claiming a row and recording its result, this worker made a network call that could have
 * taken longer than the lease. If it did, the reaper has already requeued the row and another worker
 * may own it — possibly having sent the same payload again. Writing our result on top of theirs
 * would corrupt the state machine and overwrite a newer attempt. So the lease is verified before
 * anything is written, and a lost lease is counted as a metric, because it is the one condition
 * under which Retryx knowingly double-sends. A non-zero {@code retryx.lease.lost} count means
 * {@code retryx.worker.lease-seconds} is too short for the configured HTTP timeouts.
 */
@Service
public class DeliveryOutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeRecorder.class);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final BackoffPolicy backoff;
    private final MeterRegistry meters;

    public DeliveryOutcomeRecorder(
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            BackoffPolicy backoff,
            MeterRegistry meters) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.backoff = backoff;
        this.meters = meters;
    }

    /**
     * Records one attempt and advances the delivery.
     *
     * <p>{@code REQUIRES_NEW} keeps this transaction independent and short: it opens after the HTTP
     * call has already returned, does two statements, and commits. Nothing in it waits on a network.
     *
     * @param job      the job that was attempted
     * @param result   what the send produced
     * @param workerId this worker's identity, which must still hold the lease
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DeliveryJob job, SendResult result, String workerId) {
        Delivery delivery = deliveries.findById(job.deliveryId()).orElse(null);
        if (delivery == null) {
            // The owner deleted the event (cascade) while the send was in flight. Nothing to do.
            log.warn("delivery {} vanished while in flight", job.deliveryId());
            return;
        }

        if (delivery.getStatus() != DeliveryStatus.IN_PROGRESS
                || !workerId.equals(delivery.getLockedBy())) {
            meters.counter("retryx.lease.lost").increment();
            log.warn(
                    "lease lost for delivery {} before attempt {} could be recorded "
                            + "(status={}, lockedBy={}, thisWorker={}). The receiver may have been sent this "
                            + "payload more than once. Increase retryx.worker.lease-seconds.",
                    job.deliveryId(),
                    job.attemptNumber(),
                    delivery.getStatus(),
                    delivery.getLockedBy(),
                    workerId);
            return;
        }

        int attemptNumber = job.attemptNumber();

        attempts.save(
                new DeliveryAttempt(
                        delivery.getId(),
                        attemptNumber,
                        result.outcome(),
                        result.statusCode(),
                        result.durationMs(),
                        result.errorMessage(),
                        result.responseSnippet(),
                        result.signatureHeader(),
                        workerId));

        meters
                .counter("retryx.deliveries.attempted", "outcome", result.outcome().name())
                .increment();
        meters.timer("retryx.delivery.duration").record(java.time.Duration.ofMillis(result.durationMs()));

        switch (result.outcome()) {
            case SUCCESS -> {
                delivery.onSuccess(attemptNumber, result.statusCode());
                meters.counter("retryx.deliveries.delivered").increment();
                log.info(
                        "delivered {} to '{}' on attempt {} ({} in {}ms)",
                        delivery.getId(),
                        job.endpointName(),
                        attemptNumber,
                        result.statusCode(),
                        result.durationMs());
            }

            case RETRYABLE_ERROR -> {
                if (delivery.hasBudgetAfter(attemptNumber)) {
                    OffsetDateTime nextAttemptAt = backoff.nextAttemptAt(attemptNumber);
                    delivery.onRetryScheduled(
                            attemptNumber, nextAttemptAt, result.statusCode(), result.errorMessage());
                    meters.counter("retryx.deliveries.retried").increment();
                    log.info(
                            "retrying {} after attempt {}/{} ({}) at {}",
                            delivery.getId(),
                            attemptNumber,
                            delivery.getMaxAttempts(),
                            result.errorMessage(),
                            nextAttemptAt);
                } else {
                    delivery.onFailed(
                            attemptNumber,
                            result.statusCode(),
                            "retry budget exhausted after "
                                    + attemptNumber
                                    + " attempts; last error: "
                                    + result.errorMessage());
                    meters.counter("retryx.deliveries.failed", "reason", "budget_exhausted").increment();
                    log.warn(
                            "giving up on {} after {} attempts ({})",
                            delivery.getId(),
                            attemptNumber,
                            result.errorMessage());
                }
            }

            case PERMANENT_ERROR, BLOCKED -> {
                delivery.onFailed(attemptNumber, result.statusCode(), result.errorMessage());
                meters
                        .counter("retryx.deliveries.failed", "reason", result.outcome().name().toLowerCase())
                        .increment();
                log.warn(
                        "permanent failure for {} on attempt {}: {}",
                        delivery.getId(),
                        attemptNumber,
                        result.errorMessage());
            }
        }

        deliveries.save(delivery);
    }
}
