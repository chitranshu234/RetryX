package dev.retryx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One HTTP attempt against one endpoint. Append-only: never updated, never deleted.
 *
 * <p>This is the reason {@code deliveries} and {@code delivery_attempts} are separate tables. A
 * delivery row is current state and is overwritten on every retry; if the two were merged, the
 * fifth attempt would erase the evidence from the first four and "it failed with a 502 twice then
 * timed out" would be unanswerable. Splitting them gives a mutable row the queue can update
 * cheaply, and an immutable log the dashboard can render as a timeline.
 */
@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    /** Response bodies are truncated to this many characters before storage. */
    private static final int MAX_SNIPPET_LENGTH = 2048;

    /** Error messages are truncated to this many characters before storage. */
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    /** One-based, unique per delivery, strictly increasing — including across replays. */
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private AttemptOutcome outcome;

    /** Null when no response arrived (timeout, connection refused, SSRF block). */
    @Column(name = "status_code", updatable = false)
    private Integer statusCode;

    /** Wall-clock duration of the send, measured around the HTTP call only. */
    @Column(name = "response_time_ms", updatable = false)
    private Integer responseTimeMs;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "response_body_snippet", updatable = false)
    private String responseBodySnippet;

    /**
     * The exact {@code X-Retryx-Signature} header value sent. Stored so a developer debugging a
     * rejected webhook can reproduce the HMAC locally and find out whose computation is wrong.
     */
    @Column(name = "request_signature", updatable = false)
    private String requestSignature;

    /** Which worker process sent this. Invaluable when a single node misbehaves. */
    @Column(name = "worker_id", updatable = false)
    private String workerId;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private OffsetDateTime attemptedAt = OffsetDateTime.now();

    protected DeliveryAttempt() {
        // for JPA
    }

    /**
     * @param deliveryId          delivery this attempt belongs to
     * @param attemptNumber       one-based attempt number
     * @param outcome             verdict for this attempt alone
     * @param statusCode          HTTP status, or null if none arrived
     * @param responseTimeMs      duration of the HTTP call
     * @param errorMessage        failure description, or null
     * @param responseBodySnippet leading bytes of the response, or null
     * @param requestSignature    signature header value sent
     * @param workerId            sending worker's identity
     */
    public DeliveryAttempt(
            UUID deliveryId,
            int attemptNumber,
            AttemptOutcome outcome,
            Integer statusCode,
            Integer responseTimeMs,
            String errorMessage,
            String responseBodySnippet,
            String requestSignature,
            String workerId) {
        this.deliveryId = deliveryId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = truncate(errorMessage, MAX_ERROR_LENGTH);
        this.responseBodySnippet = truncate(responseBodySnippet, MAX_SNIPPET_LENGTH);
        this.requestSignature = requestSignature;
        this.workerId = workerId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Integer getResponseTimeMs() {
        return responseTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResponseBodySnippet() {
        return responseBodySnippet;
    }

    public String getRequestSignature() {
        return requestSignature;
    }

    public String getWorkerId() {
        return workerId;
    }

    public OffsetDateTime getAttemptedAt() {
        return attemptedAt;
    }
}
