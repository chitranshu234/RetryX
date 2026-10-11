package dev.retryx.delivery;

/**
 * The HTTP headers Retryx sends with every webhook.
 *
 * <p>Collected in one place because they are a public contract: receivers parse them, and renaming
 * one is a breaking change for every integration.
 */
public final class WebhookHeaders {

    /**
     * The event's id. <strong>Stable across every retry of the same delivery</strong>, which makes
     * it the key a receiver should deduplicate on. Retryx guarantees at-least-once delivery, so
     * this header is how a receiver turns that into effectively-once processing.
     */
    public static final String EVENT_ID = "X-Retryx-Event-Id";

    /** The delivery's id — the (event, endpoint) pair. Also stable across retries. */
    public static final String DELIVERY_ID = "X-Retryx-Delivery-Id";

    /** The producer's event type, e.g. {@code order.created}, for cheap routing before parsing. */
    public static final String EVENT_TYPE = "X-Retryx-Event-Type";

    /** One-based attempt number. The only header that changes between retries of one delivery. */
    public static final String ATTEMPT = "X-Retryx-Attempt";

    /** Unix seconds, covered by the signature. Receivers reject anything outside their tolerance. */
    public static final String TIMESTAMP = "X-Retryx-Timestamp";

    /** {@code t=<unix seconds>,v1=<hex hmac-sha256>} */
    public static final String SIGNATURE = "X-Retryx-Signature";

    /**
     * Mirrors {@link #EVENT_ID} under the conventional name, so receivers that already honour
     * {@code Idempotency-Key} get deduplication without reading Retryx's documentation.
     */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private WebhookHeaders() {
        // constants only
    }
}
