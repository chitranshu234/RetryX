package dev.retryx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Something that happened in the producer's system, as handed to Retryx.
 *
 * <p>Immutable once written. An event is the <em>fact</em>; the attempts to tell the world about it
 * live in {@link Delivery} and {@link DeliveryAttempt}. Retryx never edits an event, which is what
 * lets a replay re-send byte-identical content months later.
 *
 * <p>{@code payload} is held as a {@code String} mapped to {@code jsonb}. Keeping it as text rather
 * than a parsed tree means the delivery path does no deserialise/reserialise round trip, so the
 * bytes we sign stay stable across retries.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    /** Routing/filtering label chosen by the producer, e.g. {@code order.created}. */
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** The producer's JSON, stored in a {@code jsonb} column. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    /**
     * Value of the {@code Idempotency-Key} request header, if the producer sent one. Unique per
     * owner, enforced by a partial unique index — the database, not application logic, is what
     * actually makes double submission impossible under concurrency.
     */
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "source_ip", updatable = false)
    private String sourceIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected Event() {
        // for JPA
    }

    /**
     * @param ownerId        tenant
     * @param eventType      producer's type label
     * @param payload        raw JSON body
     * @param idempotencyKey optional client-supplied dedupe key
     * @param sourceIp       caller address, for debugging
     */
    public Event(String ownerId, String eventType, String payload, String idempotencyKey, String sourceIp) {
        this.ownerId = ownerId;
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.sourceIp = sourceIp;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
