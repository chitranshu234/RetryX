package dev.retryx;

import dev.retryx.domain.ApiKey;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.repository.ApiKeyRepository;
import dev.retryx.repository.DeliveryAttemptRepository;
import dev.retryx.repository.DeliveryRepository;
import dev.retryx.repository.EventRepository;
import dev.retryx.repository.WebhookEndpointRepository;
import dev.retryx.security.AesGcmSecretCipher;
import dev.retryx.security.ApiKeyHasher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests: a real PostgreSQL, started once.
 *
 * <h2>Why a real database and not H2</h2>
 *
 * Because the thing being tested does not exist in H2. {@code FOR UPDATE SKIP LOCKED} is the
 * concurrency control for the entire system, and H2's emulation of it is not Postgres's. Neither are
 * {@code jsonb}, partial unique indexes, {@code count(*) FILTER (WHERE …)} or
 * {@code percentile_cont}. A test suite that passes against H2 and then meets Postgres in
 * production has tested the wrong database and reported a green tick for it.
 *
 * <p>The container is {@code static}, so it starts once for the whole run rather than once per test
 * class — the difference between a suite that takes twenty seconds and one nobody runs.
 *
 * <p>Schema creation goes through Flyway, exactly as in production, so the migration itself is under
 * test on every run. {@code ddl-auto: validate} then makes Hibernate assert that the entity mapping
 * matches the migrated schema: a column renamed in one place and not the other fails at startup
 * instead of at the first query that touches it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("retryx")
                    .withUsername("retryx")
                    .withPassword("retryx")
                    // The stats queries pin themselves to UTC, but leaving the server default to
                    // chance makes any timestamp assertion depend on the machine running the test.
                    .withEnv("TZ", "UTC")
                    .withEnv("PGTZ", "UTC");

    /** A 32-byte key, encoded here rather than written out as a literal. */
    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        registry.add(
                "retryx.crypto.encryption-key",
                () ->
                        Base64.getEncoder()
                                .encodeToString("retryx-test-encryption-key-32byt".getBytes(StandardCharsets.UTF_8)));
    }

    protected static final String OWNER = "owner-a";
    protected static final String OTHER_OWNER = "owner-b";
    protected static final String API_KEY = "rtx_sk_integration_test_key_0001";
    protected static final String OTHER_API_KEY = "rtx_sk_integration_test_key_0002";

    @Autowired protected ApiKeyRepository apiKeys;
    @Autowired protected WebhookEndpointRepository endpoints;
    @Autowired protected EventRepository events;
    @Autowired protected DeliveryRepository deliveries;
    @Autowired protected DeliveryAttemptRepository attempts;
    @Autowired protected ApiKeyHasher hasher;
    @Autowired protected AesGcmSecretCipher cipher;
    @Autowired protected JdbcTemplate jdbc;

    /**
     * Resets state between tests.
     *
     * <p>{@code TRUNCATE … CASCADE} rather than {@code @Transactional} rollback. A rolled-back test
     * transaction is invisible to any other connection, which means the worker — running on its own
     * connection, as it does in production — would see an empty table. Testing a queue requires the
     * data to be actually committed.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.execute(
                "TRUNCATE delivery_attempts, deliveries, events, webhook_endpoints, api_keys CASCADE");

        seedKey(OWNER, API_KEY);
        seedKey(OTHER_OWNER, OTHER_API_KEY);
    }

    private void seedKey(String ownerId, String rawKey) {
        apiKeys.save(new ApiKey(ownerId, hasher.hash(rawKey), hasher.displayPrefix(rawKey), "test"));
    }

    /**
     * Registers an endpoint directly, bypassing the API.
     *
     * @param ownerId tenant
     * @param name    display name
     * @param url     destination
     * @param secret  plaintext signing secret
     * @return the saved endpoint
     */
    protected WebhookEndpoint seedEndpoint(String ownerId, String name, String url, String secret) {
        return endpoints.save(
                new WebhookEndpoint(
                        ownerId, name, url, cipher.encrypt(secret), cipher.preview(secret), null, null));
    }
}
