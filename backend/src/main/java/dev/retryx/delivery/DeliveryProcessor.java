package dev.retryx.delivery;

import dev.retryx.queue.DeliveryJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one delivery end to end: send, then record.
 *
 * <p>Deliberately <strong>not</strong> transactional. This is the method that spans the network
 * call, and the single most important rule in the whole worker design is that no database
 * transaction is open while that call is in flight:
 *
 * <pre>
 *   claim      ── transaction 1 ── commit      (DeliveryQueueDao, ~1ms)
 *   load job   ── read-only     ── commit
 *   HTTP send  ── NO TRANSACTION ──            (up to 13s with default timeouts)
 *   record     ── transaction 2 ── commit      (DeliveryOutcomeRecorder, ~1ms)
 * </pre>
 *
 * <p>Wrapping all of that in one transaction is the obvious-looking implementation and it fails in
 * four ways at once: a pool connection is held for the duration of someone else's slow server, the
 * claimed rows stay locked so {@code SKIP LOCKED} can no longer route around them, Postgres cannot
 * vacuum anything newer than the oldest open transaction, and 20 concurrent sends to a dead
 * receiver exhaust a 20-connection pool and take the API down with them. The fix is not a bigger
 * pool; it is not holding the transaction.
 *
 * <p>The cost of the split is the at-least-once window: if this process dies after the receiver
 * processed the request but before {@code record} commits, the lease expires, another worker
 * re-sends, and the receiver sees the event twice. That is an unavoidable consequence of the
 * receiver's commit and ours being in different systems with no shared transaction — see the
 * "exactly-once" discussion in DEVELOPER.md. Retryx's answer is to make the duplicate cheap to
 * handle: stable {@code X-Retryx-Event-Id} and byte-identical bodies across retries.
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final WebhookSender sender;
    private final DeliveryOutcomeRecorder recorder;

    public DeliveryProcessor(WebhookSender sender, DeliveryOutcomeRecorder recorder) {
        this.sender = sender;
        this.recorder = recorder;
    }

    /**
     * Attempts one delivery and persists the outcome.
     *
     * @param job      a claimed delivery
     * @param workerId this worker's identity
     */
    public void process(DeliveryJob job, String workerId) {
        try {
            SendResult result = sender.send(job);
            recorder.record(job, result, workerId);
        } catch (RuntimeException e) {
            // Nothing in send() is supposed to throw; if it does, swallowing it here keeps one bad
            // row from killing the worker thread. The lease will expire and the reaper requeues it.
            log.error("unhandled error processing delivery {}", job.deliveryId(), e);
        }
    }
}
