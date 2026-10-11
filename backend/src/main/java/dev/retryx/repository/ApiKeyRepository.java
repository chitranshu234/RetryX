package dev.retryx.repository;

import dev.retryx.domain.ApiKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * API key lookups.
 *
 * <p>{@link #findByKeyHash} is the single hottest query in the application — it runs on every
 * authenticated request — and is served by the unique index {@code uq_api_keys_key_hash}.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Authentication lookup.
     *
     * @param keyHash SHA-256 hex of the presented key
     * @return the key record, revoked or not; the caller checks {@link ApiKey#isActive()}
     */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * @param ownerId tenant
     * @return true if this owner already has at least one key (used by the demo seeder)
     */
    boolean existsByOwnerId(String ownerId);
}
