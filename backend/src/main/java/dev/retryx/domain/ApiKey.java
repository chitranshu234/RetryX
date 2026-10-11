package dev.retryx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An API key, stored as a hash.
 *
 * <p>The raw key is shown to the user exactly once, at creation, and then never again — there is no
 * column here that could reproduce it. {@code keyHash} is a plain SHA-256 of the raw key, which is
 * the right choice because the key is 32 bytes of {@link java.security.SecureRandom} output. A slow
 * KDF like bcrypt exists to make guessing a low-entropy human password expensive; against 256 bits
 * of entropy there is nothing to guess, and a slow hash on the authentication hot path would just
 * be a self-inflicted denial of service.
 *
 * @see dev.retryx.security.ApiKeyHasher
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Tenant this key authenticates as. Every query in the app is scoped by this. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    /** Lowercase hex SHA-256 of the raw key. Unique index; the authentication lookup. */
    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    /** Clear-text leading characters, for display only (e.g. {@code rtx_sk_8fa2}). */
    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    /** Human label, e.g. "ci-pipeline". */
    @Column(name = "label")
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    /** Non-null means the key no longer authenticates. Kept for the audit trail. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ApiKey() {
        // for JPA
    }

    /**
     * @param ownerId   tenant the key belongs to
     * @param keyHash   SHA-256 hex of the raw key
     * @param keyPrefix display-safe prefix of the raw key
     * @param label     human label
     */
    public ApiKey(String ownerId, String keyHash, String keyPrefix, String label) {
        this.ownerId = ownerId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.label = label;
    }

    /** @return true when this key may still be used to authenticate */
    public boolean isActive() {
        return revokedAt == null;
    }

    /** Marks the key as used now. Best-effort bookkeeping, not an audit guarantee. */
    public void touch() {
        this.lastUsedAt = OffsetDateTime.now();
    }

    /** Revokes the key. Idempotent. */
    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getLabel() {
        return label;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }
}
