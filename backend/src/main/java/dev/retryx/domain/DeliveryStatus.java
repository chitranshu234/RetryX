package dev.retryx.domain;

/**
 * Where a delivery is right now.
 *
 * <pre>
 *   PENDING ──claim──&gt; IN_PROGRESS ──2xx──────────────────&gt; DELIVERED
 *      ^                    │
 *      │                    ├── retryable, budget left ──&gt; PENDING
 *      │                    ├── permanent | budget gone ─&gt; FAILED
 *      │                    │
 *      └── lease expired ───┘   (recovered by StaleClaimReaper)
 *
 *   DELIVERED | FAILED ──replay──&gt; PENDING
 * </pre>
 *
 * <p>Stored as text, not as an ordinal. An ordinal would silently remap every historical row the
 * first time someone reorders this enum.
 */
public enum DeliveryStatus {

    /** Waiting for {@code next_attempt_at} to arrive. The only claimable state. */
    PENDING,

    /** Leased by a worker. {@code locked_by} owns it until {@code locked_until}. */
    IN_PROGRESS,

    /** The receiver answered 2xx. Terminal, unless someone replays it. */
    DELIVERED,

    /** Permanent error, or the retry budget ran out. Terminal, unless replayed. */
    FAILED;

    /** @return true if no worker will pick this row up again without a replay */
    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }
}
