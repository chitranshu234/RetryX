package dev.retryx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A destination Retryx delivers to, plus the secret it signs that destination's payloads with.
 *
 * <p>The signing secret is stored as AES-256-GCM ciphertext, so a leaked database dump does not let
 * an attacker forge signed webhooks. The key lives in an environment variable, outside the database
 * — the one separation that makes encryption-at-rest meaningful here.
 *
 * <p>Deletion is soft. Deliveries reference this row forever, and a hard delete would turn the
 * delivery-detail page into a dangling pointer right when someone is trying to work out what went
 * wrong.
 */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Validated by the SSRF guard both here (on write) and again at send time. */
    @Column(name = "url", nullable = false)
    private String url;

    /** {@code v1.<base64url nonce>.<base64url ciphertext+tag>} */
    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    /** e.g. {@code whsec_1f4c…} — enough to recognise a secret, not enough to use it. */
    @Column(name = "secret_preview", nullable = false)
    private String secretPreview;

    @Column(name = "description")
    private String description;

    /** Inactive endpoints are skipped by fan-out but keep their delivery history. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Per-endpoint retry budget override. Null means use {@code retryx.retry.max-attempts}. */
    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected WebhookEndpoint() {
        // for JPA
    }

    /**
     * @param ownerId          tenant
     * @param name             display name
     * @param url              destination, already SSRF-validated
     * @param encryptedSecret  AES-GCM envelope of the signing secret
     * @param secretPreview    display-safe hint
     * @param description      optional note
     * @param maxAttempts      optional retry budget override
     */
    public WebhookEndpoint(
            String ownerId,
            String name,
            String url,
            String encryptedSecret,
            String secretPreview,
            String description,
            Integer maxAttempts) {
        this.ownerId = ownerId;
        this.name = name;
        this.url = url;
        this.encryptedSecret = encryptedSecret;
        this.secretPreview = secretPreview;
        this.description = description;
        this.maxAttempts = maxAttempts;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /** @return true when fan-out should create deliveries for this endpoint */
    public boolean isLive() {
        return active && deletedAt == null;
    }

    /** Enables or disables the endpoint without losing its history. */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** Soft-deletes the endpoint. Idempotent. */
    public void softDelete() {
        if (deletedAt == null) {
            this.deletedAt = OffsetDateTime.now();
            this.active = false;
        }
    }

    public void rename(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public String getSecretPreview() {
        return secretPreview;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
