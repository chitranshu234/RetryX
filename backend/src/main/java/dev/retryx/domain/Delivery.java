package dev.retryx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One (event → endpoint) delivery. <strong>This table is the queue.</strong>
 *
 * <p>Workers claim rows here with {@code FOR UPDATE SKIP LOCKED}, so Retryx needs no broker. The
 * trade-offs that choice implies are spelled out in DEVELOPER.md; the ones that constrain this
 * class are:
 *
 * <ul>
 *   <li><strong>No {@code @Version}.</strong> Optimistic locking would fight the queue: the claim
 *       query and the reaper both update rows through raw JDBC and would leave the version column
 *       stale. Mutual exclusion here comes from the <em>lease</em> ({@code locked_by} +
 *       {@code locked_until}), which is also what survives a worker dying mid-send. Two mechanisms
 *       guarding the same rows would only create spurious failures.
 *   <li><strong>Backoff is a timestamp, not a sleep.</strong> Retrying later means writing a
 *       further-future {@code nextAttemptAt} and letting the row fall out of the claim query's
 *       {@code WHERE}. No worker ever blocks waiting for a retry window.
 *   <li><strong>{@code attemptCount} only ever increases.</strong> Replay raises
 *       {@code maxAttempts} instead of resetting the count, which keeps
 *       {@code (delivery_id, attempt_number)} unique and the timeline in
 *       {@link DeliveryAttempt} strictly ordered for the row's whole life.
 * </ul>
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    /** Error text longer than this is truncated before it reaches the column. */
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Copied from the event. Denormalised on purpose: every owner-scoped list query and the
     * authorisation check on {@code GET /api/deliveries/{id}} then hit one index on one table
     * instead of joining through events on the hottest read path in the dashboard.
     */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    /**
     * Plain UUID columns rather than {@code @ManyToOne}. A queue row is read by the thousand in
     * tight loops; associations invite lazy-loading inside the worker and N+1 queries in list
     * endpoints. Joins happen explicitly, in SQL, where they can be seen and indexed.
     */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "replay_count", nullable = false)
    private int replayCount = 0;

    @Column(name = "last_replayed_at")
    private OffsetDateTime lastReplayedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected Delivery() {
        // for JPA
    }

    /**
     * @param ownerId     tenant, copied from the event
     * @param eventId     event to deliver
     * @param endpointId  destination
     * @param maxAttempts retry budget for this row
     */
    public Delivery(String ownerId, UUID eventId, UUID endpointId, int maxAttempts) {
        this.ownerId = ownerId;
        this.eventId = eventId;
        this.endpointId = endpointId;
        this.maxAttempts = maxAttempts;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ------------------------------------------------------------------
    //  state transitions
    // ------------------------------------------------------------------

    /**
     * The attempt number the next send will carry. One-based, so the first send is attempt 1.
     *
     * @return {@code attemptCount + 1}
     */
    public int nextAttemptNumber() {
        return attemptCount + 1;
    }

    /**
     * Whether another attempt is permitted after the given one fails retryably.
     *
     * @param attemptNumber the attempt that just failed
     * @return true if the budget allows one more
     */
    public boolean hasBudgetAfter(int attemptNumber) {
        return attemptNumber < maxAttempts;
    }

    /**
     * Receiver answered 2xx. Terminal.
     *
     * @param attemptNumber the attempt that succeeded
     * @param statusCode    the 2xx code observed
     */
    public void onSuccess(int attemptNumber, int statusCode) {
        this.attemptCount = attemptNumber;
        this.status = DeliveryStatus.DELIVERED;
        this.lastStatusCode = statusCode;
        this.lastError = null;
        this.completedAt = OffsetDateTime.now();
        releaseLease();
    }

    /**
     * Retryable failure with budget left: go back to PENDING and become claimable again once
     * {@code nextAttemptAt} passes.
     *
     * @param attemptNumber the attempt that failed
     * @param nextAttemptAt when this row may be claimed again, from the backoff policy
     * @param statusCode    HTTP status if we got one, else null
     * @param error         short description for the UI
     */
    public void onRetryScheduled(
            int attemptNumber, OffsetDateTime nextAttemptAt, Integer statusCode, String error) {
        this.attemptCount = attemptNumber;
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastStatusCode = statusCode;
        this.lastError = truncate(error);
        releaseLease();
    }

    /**
     * Permanent failure, or the budget is now exhausted. Terminal until replayed.
     *
     * @param attemptNumber the attempt that failed
     * @param statusCode    HTTP status if we got one, else null
     * @param error         short description for the UI
     */
    public void onFailed(int attemptNumber, Integer statusCode, String error) {
        this.attemptCount = attemptNumber;
        this.status = DeliveryStatus.FAILED;
        this.lastStatusCode = statusCode;
        this.lastError = truncate(error);
        this.completedAt = OffsetDateTime.now();
        releaseLease();
    }

    /**
     * Puts a terminal delivery back in the queue.
     *
     * <p>The retry budget is <em>extended</em> rather than reset: {@code maxAttempts} becomes
     * {@code attemptCount + extraBudget}. That keeps attempt numbers monotonic, so the attempt log
     * for a delivery replayed three times reads 1-6, 7-12, 13-18 instead of 1-6 three times over.
     *
     * @param extraBudget how many further attempts to allow
     * @throws IllegalStateException if the delivery is still active
     */
    public void replay(int extraBudget) {
        if (!status.isTerminal()) {
            throw new IllegalStateException(
                    "delivery " + id + " is " + status + "; only DELIVERED or FAILED can be replayed");
        }
        this.status = DeliveryStatus.PENDING;
        this.maxAttempts = attemptCount + extraBudget;
        this.nextAttemptAt = OffsetDateTime.now();
        this.completedAt = null;
        this.replayCount += 1;
        this.lastReplayedAt = OffsetDateTime.now();
        releaseLease();
    }

    /** Drops the lease. Always paired with a move out of IN_PROGRESS. */
    private void releaseLease() {
        this.lockedUntil = null;
        this.lockedBy = null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    // ------------------------------------------------------------------
    //  accessors
    // ------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public String getLastError() {
        return lastError;
    }

    public int getReplayCount() {
        return replayCount;
    }

    public OffsetDateTime getLastReplayedAt() {
        return lastReplayedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
