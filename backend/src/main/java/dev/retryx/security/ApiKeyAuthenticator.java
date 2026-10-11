package dev.retryx.security;

import dev.retryx.domain.ApiKey;
import dev.retryx.repository.ApiKeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a raw API key into a verified {@link ApiKeyPrincipal}.
 *
 * <p>The lookup is by hash against a unique index, so authentication costs one index probe. Note
 * what is <em>not</em> here: no scan over candidate keys, no decryption, no constant-time compare
 * of the key itself. We hash what the client sent and look for that exact hash — a wrong key finds
 * nothing, and there is no comparison whose duration could leak information about a correct one.
 *
 * <h2>The {@code last_used_at} throttle</h2>
 *
 * Recording when a key was last used is genuinely useful (it is how you find the credential nobody
 * has rotated in two years). Doing it naively turns every single read request into a write:
 * a {@code GET} that should be one index lookup becomes a lookup plus an {@code UPDATE} plus a WAL
 * record plus a dead tuple for vacuum to clean up. Under load that write amplification is larger
 * than the actual workload, and it serialises concurrent requests using the same key behind one row
 * lock.
 *
 * <p>So the timestamp is updated at most once a minute per key, tracked in memory. The cost is that
 * {@code last_used_at} is accurate to the minute and resets on restart — entirely acceptable for
 * what it is used for. This is a deliberate accuracy-for-throughput trade, not an oversight: if the
 * value were needed for audit rather than hygiene it would have to be written to an append-only log
 * instead, not to a mutable column on the hot path.
 */
@Service
public class ApiKeyAuthenticator {

    /** Minimum gap between {@code last_used_at} writes for the same key. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository apiKeys;
    private final ApiKeyHasher hasher;

    /** keyId → when we last wrote {@code last_used_at}. Bounded by the number of live keys. */
    private final Map<UUID, Instant> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyAuthenticator(ApiKeyRepository apiKeys, ApiKeyHasher hasher) {
        this.apiKeys = apiKeys;
        this.hasher = hasher;
    }

    /**
     * Verifies a presented key.
     *
     * @param rawKey the value from the {@code X-Api-Key} or {@code Authorization} header
     * @return the principal, or empty if the key is unknown, malformed or revoked — the caller must
     *     not distinguish between those cases in its response
     */
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (!hasher.looksLikeKey(rawKey)) {
            return Optional.empty();
        }

        Optional<ApiKey> found = apiKeys.findByKeyHash(hasher.hash(rawKey));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        ApiKey key = found.get();
        if (!key.isActive()) {
            return Optional.empty();
        }

        touchThrottled(key);

        return Optional.of(new ApiKeyPrincipal(key.getOwnerId(), key.getId(), key.getKeyPrefix()));
    }

    private void touchThrottled(ApiKey key) {
        Instant now = Instant.now();
        Instant previous = lastTouched.get(key.getId());
        if (previous == null || Duration.between(previous, now).compareTo(TOUCH_INTERVAL) >= 0) {
            lastTouched.put(key.getId(), now);
            key.touch();
            apiKeys.save(key);
        }
    }
}
