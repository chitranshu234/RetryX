package dev.retryx.security;

/**
 * Thrown when a destination URL is rejected by {@link SsrfGuard}.
 *
 * <p>Carries a short, user-facing reason so the API can explain the rejection on
 * {@code POST /api/webhooks} and the worker can write the same text into the attempt log — a
 * delivery blocked at send time appears in the timeline with the reason, rather than as a
 * mysterious gap.
 */
public class BlockedDestinationException extends RuntimeException {

    private final String reason;

    /**
     * @param reason short explanation, safe to show to the API caller
     */
    public BlockedDestinationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** @return the short explanation */
    public String getReason() {
        return reason;
    }
}
