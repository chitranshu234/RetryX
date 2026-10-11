package dev.retryx.config;

import dev.retryx.delivery.JitterStrategy;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in Retryx, bound from the {@code retryx.*} section of application.yml.
 *
 * <p>Using a typed record rather than scattered {@code @Value} annotations means a typo in a
 * property name is a startup failure instead of a silent default, and the IDE can autocomplete the
 * YAML from the generated metadata.
 *
 * @param worker  the poll/claim/dispatch loop
 * @param reaper  stale-claim recovery
 * @param retry   backoff and the retry budget
 * @param http    outbound HTTP timeouts
 * @param signing HMAC signature settings
 * @param crypto  AES-GCM key for secrets at rest
 * @param ssrf    destination URL restrictions
 * @param cors    dashboard origins
 * @param demo    development seed data
 */
@ConfigurationProperties(prefix = "retryx")
public record RetryxProperties(
        Worker worker,
        Reaper reaper,
        Retry retry,
        Http http,
        Signing signing,
        Crypto crypto,
        Ssrf ssrf,
        Cors cors,
        Demo demo) {

    /**
     * @param enabled        set false to stop this process from claiming work (API-only node, or
     *                       an integration test that drives the worker by hand)
     * @param id             value written to {@code deliveries.locked_by}; blank means autogenerate
     * @param initialDelayMs delay before the first poll, so the process finishes starting before it
     *                       takes on work. Integration tests set this high and call
     *                       {@code runOnce()} themselves — driving a queue from a real scheduler is
     *                       how a test suite acquires intermittent failures.
     * @param pollIntervalMs delay between claim queries; the latency floor for a due row
     * @param batchSize      rows claimed per poll
     * @param concurrency    simultaneous in-flight HTTP sends in this process
     * @param leaseSeconds   how long a claim is honoured before another worker may steal the row.
     *                       Must exceed connect+read timeout, otherwise a slow receiver causes a
     *                       double send.
     */
    public record Worker(
            boolean enabled,
            String id,
            long initialDelayMs,
            long pollIntervalMs,
            int batchSize,
            int concurrency,
            int leaseSeconds) {}

    /**
     * @param enabled        set false to observe stuck rows instead of recovering them
     * @param initialDelayMs delay before the first sweep
     * @param intervalMs     how often to sweep for expired leases
     * @param batchSize      maximum rows requeued per sweep
     */
    public record Reaper(boolean enabled, long initialDelayMs, long intervalMs, int batchSize) {}

    /**
     * @param maxAttempts      default retry budget per delivery
     * @param baseDelaySeconds delay before attempt 2
     * @param multiplier       growth factor per attempt
     * @param maxDelaySeconds  ceiling, so the series cannot run away
     * @param jitter           how to randomise the computed delay
     */
    public record Retry(
            int maxAttempts,
            long baseDelaySeconds,
            double multiplier,
            long maxDelaySeconds,
            JitterStrategy jitter) {}

    /**
     * @param connectTimeoutMs        TCP + TLS handshake budget
     * @param readTimeoutMs           budget for the receiver to produce a response
     * @param maxResponseSnippetBytes response bytes retained for debugging
     * @param userAgent               User-Agent header sent to receivers
     */
    public record Http(
            int connectTimeoutMs, int readTimeoutMs, int maxResponseSnippetBytes, String userAgent) {}

    /** @param toleranceSeconds the replay window receivers are told to enforce */
    public record Signing(long toleranceSeconds) {}

    /** @param encryptionKey base64 of 16/24/32 raw bytes, for AES-GCM */
    public record Crypto(String encryptionKey) {}

    /**
     * @param enabled      master switch; disabling it is only ever right inside a test
     * @param requireHttps reject plain http destinations
     * @param allowedPorts destination ports that may be used
     * @param allowHosts   hosts exempt from the private-address checks. <strong>Development
     *                     only.</strong> Anything in here is a hole in the SSRF defence.
     */
    public record Ssrf(
            boolean enabled, boolean requireHttps, Set<Integer> allowedPorts, Set<String> allowHosts) {}

    /** @param allowedOrigins browser origins permitted to call the API */
    public record Cors(List<String> allowedOrigins) {}

    /**
     * @param enabled          seed demo data when the owner has none
     * @param ownerId          owner the seed data belongs to
     * @param apiKey           plaintext key to seed, printed at startup
     * @param receiverBaseUrl  where the seeded endpoints point
     * @param signingSecret    signing secret for the seeded endpoints. Fixed rather than generated
     *                         so the bundled receiver can be configured with the same value and
     *                         actually verify signatures end to end — a demo in which verification
     *                         is skipped would not demonstrate the thing most worth seeing.
     */
    public record Demo(
            boolean enabled,
            String ownerId,
            String apiKey,
            String receiverBaseUrl,
            String signingSecret) {}
}
