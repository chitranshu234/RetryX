package dev.retryx.queue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.AbstractIntegrationTest;
import dev.retryx.domain.Delivery;
import dev.retryx.domain.DeliveryStatus;
import dev.retryx.domain.Event;
import dev.retryx.domain.WebhookEndpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The claim query, under concurrency.
 *
 * <p>This is the test the whole architecture rests on. If two workers can claim one row, Retryx
 * double-sends webhooks, and no amount of correctness elsewhere matters.
 */
class MultiWorkerClaimIT extends AbstractIntegrationTest {

    @Autowired private DeliveryQueueDao queue;

    private static final int WORKERS = 8;
    private static final int ROWS = 400;

    @Test
    @DisplayName("eight workers claiming at once never claim the same row twice")
    void noRowIsClaimedTwice() throws Exception {
        seedDueDeliveries(ROWS);

        // All threads are released at the same instant. Starting them sequentially would let each
        // finish before the next began, and the test would pass without ever creating contention —
        // which is the only thing it is trying to observe.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(WORKERS);
        List<UUID> allClaims = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                String workerId = "worker-" + worker;
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                // Each worker drains until it gets nothing, as the real loop does.
                                List<UUID> claimed;
                                do {
                                    claimed = queue.claimDue(workerId, 25, 60);
                                    allClaims.addAll(claimed);
                                } while (!claimed.isEmpty());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                finished.countDown();
                            }
                        });
            }

            start.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The assertion that matters: every row claimed exactly once.
        assertThat(allClaims).hasSize(ROWS);
        assertThat(allClaims).doesNotHaveDuplicates();

        // And every row is now leased, by exactly one identifiable worker.
        List<Delivery> all = deliveries.findAll();
        assertThat(all).hasSize(ROWS);
        assertThat(all)
                .allSatisfy(
                        delivery -> {
                            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.IN_PROGRESS);
                            assertThat(delivery.getLockedBy()).startsWith("worker-");
                            assertThat(delivery.getLockedUntil()).isNotNull();
                        });
    }

    @Test
    @DisplayName("a claim marks the rows IN_PROGRESS and stamps a lease from the database clock")
    void claimStampsLease() {
        seedDueDeliveries(3);

        List<UUID> claimed = queue.claimDue("worker-1", 10, 30);

        assertThat(claimed).hasSize(3);

        // The lease must come from now() evaluated by Postgres, not from the worker's clock — that
        // is what lets several machines agree on when a lease expires.
        Integer leasedInFuture =
                jdbc.queryForObject(
                        "SELECT count(*) FROM deliveries WHERE locked_until > now() AND locked_by = 'worker-1'",
                        Integer.class);
        assertThat(leasedInFuture).isEqualTo(3);
    }

    @Test
    @DisplayName("a row whose next_attempt_at is in the future is not claimed")
    void respectsBackoffSchedule() {
        seedDueDeliveries(2);
        jdbc.update("UPDATE deliveries SET next_attempt_at = now() + interval '1 hour'");

        assertThat(queue.claimDue("worker-1", 10, 60)).isEmpty();

        // Backoff is implemented purely by pushing this timestamp out; nothing sleeps.
        jdbc.update("UPDATE deliveries SET next_attempt_at = now() - interval '1 second'");
        assertThat(queue.claimDue("worker-1", 10, 60)).hasSize(2);
    }

    @Test
    @DisplayName("already-claimed rows are skipped rather than blocking a second worker")
    void skipsLockedRows() {
        seedDueDeliveries(5);

        List<UUID> first = queue.claimDue("worker-1", 3, 60);
        List<UUID> second = queue.claimDue("worker-2", 10, 60);

        assertThat(first).hasSize(3);
        // Two left, and worker-2 got them immediately rather than waiting for worker-1's lease.
        assertThat(second).hasSize(2);
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    @DisplayName("the batch size is respected")
    void respectsBatchSize() {
        seedDueDeliveries(100);

        assertThat(queue.claimDue("worker-1", 10, 60)).hasSize(10);
        assertThat(queue.claimDue("worker-1", 7, 60)).hasSize(7);
    }

    @Test
    @DisplayName("claims oldest-first, so nothing starves")
    void claimsInDueOrder() {
        WebhookEndpoint endpoint = seedEndpoint(OWNER, "E", "http://localhost:9876/hooks", "whsec_x");

        List<UUID> inOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Event event = events.save(new Event(OWNER, "t." + i, "{\"i\":" + i + "}", null, null));
            Delivery delivery = deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));
            // Stagger next_attempt_at so there is a definite order to observe. The cast is
            // explicit because Postgres cannot infer a type for a bare JDBC placeholder inside an
            // interval expression.
            jdbc.update(
                    "UPDATE deliveries SET next_attempt_at = now() - "
                            + "(CAST(? AS double precision) * interval '1 second') WHERE id = ?",
                    100 - i * 10,
                    delivery.getId());
            inOrder.add(delivery.getId());
        }

        List<UUID> claimed = queue.claimDue("worker-1", 2, 60);

        // The two longest-waiting rows, in that order.
        assertThat(claimed).containsExactly(inOrder.get(0), inOrder.get(1));
    }

    @Test
    @DisplayName("loading jobs joins in the payload, URL and encrypted secret")
    void loadJobsJoinsEverythingASendNeeds() {
        WebhookEndpoint endpoint =
                seedEndpoint(OWNER, "Orders", "http://localhost:9876/hooks", "whsec_known");
        Event event = events.save(new Event(OWNER, "order.created", "{\"orderId\":\"A-1\"}", null, null));
        deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));

        List<UUID> claimed = queue.claimDue("worker-1", 10, 60);
        List<DeliveryJob> jobs = queue.loadJobs(claimed);

        assertThat(jobs).hasSize(1);
        DeliveryJob job = jobs.get(0);
        assertThat(job.eventType()).isEqualTo("order.created");
        assertThat(job.url()).isEqualTo("http://localhost:9876/hooks");
        assertThat(job.endpointName()).isEqualTo("Orders");
        assertThat(job.attemptNumber()).isEqualTo(1);
        // jsonb round-trips as normalised text, which is what makes the envelope byte-stable.
        assertThat(job.payloadJson()).contains("\"orderId\"").contains("A-1");
        // The secret arrives still encrypted; it is decrypted at the last possible moment.
        assertThat(cipher.decrypt(job.encryptedSecret())).isEqualTo("whsec_known");
    }

    @Test
    @DisplayName("an empty queue returns nothing without error")
    void emptyQueue() {
        assertThat(queue.claimDue("worker-1", 25, 60)).isEmpty();
        assertThat(queue.loadJobs(List.of())).isEmpty();
        assertThat(queue.countDueNow()).isZero();
    }

    @Test
    @DisplayName("queue depth and oldest-wait reflect reality")
    void queueMetrics() {
        seedDueDeliveries(4);
        jdbc.update("UPDATE deliveries SET next_attempt_at = now() - interval '20 seconds'");

        assertThat(queue.countDueNow()).isEqualTo(4);
        assertThat(queue.oldestDueAgeSeconds()).isGreaterThan(19).isLessThan(60);

        queue.claimDue("worker-1", 2, 60);

        assertThat(queue.countInFlight()).isEqualTo(2);
        assertThat(queue.countDueNow()).isEqualTo(2);
    }

    /** Creates {@code howMany} deliveries that are due now. */
    private void seedDueDeliveries(int howMany) {
        WebhookEndpoint endpoint =
                seedEndpoint(OWNER, "Load", "http://localhost:9876/hooks", "whsec_load");

        for (int i = 0; i < howMany; i++) {
            Event event = events.save(new Event(OWNER, "load.test", "{\"i\":" + i + "}", null, null));
            deliveries.save(new Delivery(OWNER, event.getId(), endpoint.getId(), 4));
        }
    }
}
