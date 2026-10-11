package dev.retryx.domain;

/**
 * What happened on one individual HTTP attempt.
 *
 * <p>This is deliberately separate from {@link DeliveryStatus}: an attempt can be a
 * {@code RETRYABLE_ERROR} while the delivery as a whole ends up {@code DELIVERED}, and the last
 * attempt of an exhausted budget is still only a {@code RETRYABLE_ERROR} even though the delivery
 * is now {@code FAILED}. Recording the attempt's own verdict keeps the audit trail honest about
 * what the receiver actually said.
 */
public enum AttemptOutcome {

    /** 2xx. */
    SUCCESS,

    /** 5xx, 408, 425, 429, timeout, connection reset — worth trying again. */
    RETRYABLE_ERROR,

    /** 4xx (other than the above), a redirect, or an unparseable response — will not improve. */
    PERMANENT_ERROR,

    /**
     * Never left the process: the SSRF guard rejected the destination at send time. Recorded as an
     * attempt on purpose, so the dashboard shows <em>why</em> nothing was sent instead of leaving a
     * silent gap in the timeline.
     */
    BLOCKED;

    /** @return true when this outcome should push the delivery back to PENDING */
    public boolean isRetryable() {
        return this == RETRYABLE_ERROR;
    }
}
