package dev.retryx.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.AbstractIntegrationTest;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.queue.DeliveryQueueDao;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Crash recovery.
 *
 * <p>The failure being simulated is the one that silently loses work in every hand-rolled database
 * queue: a worker claims rows, flips them to {@code IN_PROGRESS}, and is killed. Nothing in the
 * claim query will ever return them again, because it only looks at {@code PENDING} — so without
 * the reaper they are stuck forever, with no error logged anywhere and a dashboard that shows them
 * as "sending" indefinitely.
 *
 * <p>A crash is simulated by claiming with a zero-second lease rather than by actually killing a
 * process. The database state is identical — {@code IN_PROGRESS} with an expired
 * {@code locked_until} — and the test runs in milliseconds instead of waiting out a real lease,
 * which is the difference between a test that runs on every commit and one that is commented out.
 */
class StaleClaimRecoveryIT extends AbstractIntegrationTest {

    @Autowired private DeliveryQueueDao queue;
    @Autowired private StaleClaimReaper reaper;

    @Test
    @DisplayName("requeues deliveries whose lease has expired")
    void recoversCrashedWork() {
        seedDue(5);

        // A worker claims everything, then "dies". Lease = 0s, so it is already expired.
        List<UUID> claimed = queue.claimDue("doomed-worker", 10, 0);
        assertThat(claimed).hasSize(5);
        assertThat(deliveries.countByOwnerIdAndStatus(OWNER, DeliveryStatus.IN_PROGRESS)).isEqualTo(5);

        // Before recovery the rows are invisible to the claim query — this is the stuck state.
        assertThat(queue.claimDue("healthy-worker", 10, 60)).isEmpty();

        List<UUID> recovered = reaper.recoverOnce();

        assertThat(recovered).hasSize(5).containsExactlyInAnyOrderElementsOf(claimed);
        assertThat(deliveries.countByOwnerIdAndStatus(OWNER, DeliveryStatus.PENDING)).isEqualTo(5);

        // And now a live worker can pick them up.
        assertThat(queue.claimDue("healthy-worker", 10, 60)).hasSize(5);
    }

    @Test
    @DisplayName("leaves a live worker's claims alone")
    void doesNotStealLiveClaims() {
        seedDue(4);

        // A 60-second lease: this worker is alive and still working.
        List<UUID> claimed = queue.claimDue("live-worker", 10, 60);
        assertThat(claimed).hasSize(4);

        assertThat(reaper.recoverOnce()).isEmpty();

        // Stealing these would mean sending four webhooks twice — the exact thing the lease exists
        // to prevent.
        assertThat(deliveries.countByOwnerIdAndStatus(OWNER, DeliveryStatus.IN_PROGRESS)).isEqualTo(4);
    }

    @Test
    @DisplayName("recovers only the expired claims when both are present")
    void recoversSelectively() {
        seedDue(6);

        List<UUID> dead = queue.claimDue("dead-worker", 3, 0);
        List<UUID> alive = queue.claimDue("live-worker", 3, 120);

        List<UUID> recovered = reaper.recoverOnce();

        assertThat(recovered).containsExactlyInAnyOrderElementsOf(dead);
        assertThat(recovered).doesNotContainAnyElementsOf(alive);
    }

    @Test
    @DisplayName("records why the delivery was requeued")
    void explainsItself() {
        seedDue(1);
        queue.claimDue("worker-that-died", 1, 0);

        reaper.recoverOnce();

        Delivery recovered = deliveries.findAll().get(0);
        // Someone looking at this row later needs to know it was requeued by the reaper and not
        // retried because a receiver failed — those are different problems.
        assertThat(recovered.getLastError())
                .contains("lease expired")
                .contains("worker-that-died");
    }

    @Test
    @DisplayName("does not consume retry budget, because it cannot know whether the send happened")
    void doesNotChargeAnAttempt() {
        seedDue(1);
        queue.claimDue("doomed", 1, 0);

        reaper.recoverOnce();

        Delivery recovered = deliveries.findAll().get(0);
        // The worker may have died before sending, after sending, or mid-response. Logging an
        // attempt that may never have happened would make the timeline lie; charging budget for it
        // would eat a retry the delivery may still need. The honest consequence is a possible
        // duplicate send, which is why every webhook carries a stable event id.
        assertThat(recovered.getAttemptCount()).isZero();
        assertThat(attempts.countByDeliveryId(recovered.getId())).isZero();
        assertThat(recovered.getMaxAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("clears the lease so the row is genuinely free")
    void clearsTheLease() {
        seedDue(1);
        queue.claimDue("doomed", 1, 0);

        reaper.recoverOnce();

        Delivery recovered = deliveries.findAll().get(0);
        assertThat(recovered.getLockedBy()).isNull();
        assertThat(recovered.getLockedUntil()).isNull();
        // Immediately eligible, so recovery time is bounded by lease + sweep interval and nothing
        // else.
        assertThat(recovered.getNextAttemptAt()).isBeforeOrEqualTo(java.time.OffsetDateTime.now());
    }

    @Test
    @DisplayName("is idempotent — a second sweep finds nothing")
    void idempotent() {
        seedDue(3);
        queue.claimDue("doomed", 3, 0);

        assertThat(reaper.recoverOnce()).hasSize(3);
        assertThat(reaper.recoverOnce()).isEmpty();
    }

    @Test
    @DisplayName("a sweep over an empty queue does nothing")
    void emptyQueue() {
        assertThat(reaper.recoverOnce()).isEmpty();
    }

    private void seedDue(int howMany) {
        WebhookEndpoint endpoint =
                seedEndpoint(OWNER, "Recovery", "http://localhost:9876/hooks", "whsec_r");

        for (int i = 0; i < howMany; i++) {
            Event event = events.save(new Event(OWNER, "recover.test", "{\"i\":" + i + "}", null, null));
            deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));
        }
    }
}
