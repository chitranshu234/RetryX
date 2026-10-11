package dev.retryx.bootstrap;

import dev.retryx.config.RetryxProperties;
import dev.retryx.domain.ApiKey;
import dev.retryx.domain.WebhookEndpoint;
import dev.retryx.repository.ApiKeyRepository;
import dev.retryx.repository.WebhookEndpointRepository;
import dev.retryx.security.AesGcmSecretCipher;
import dev.retryx.security.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demo owner, API key and two endpoints on first start.
 *
 * <p>So that {@code docker compose up} produces something you can immediately click on, rather than
 * an empty dashboard and a chicken-and-egg problem: the API needs a key, and there is no
 * unauthenticated endpoint that mints one (deliberately — multi-user signup is out of scope, and a
 * public key-minting endpoint would be worse than no auth at all).
 *
 * <p>The two seeded endpoints point at the bundled receiver and are chosen to make the dashboard
 * interesting within seconds: one healthy, one that fails about 40% of requests. The flaky one is
 * the entire point — it exercises the retry engine, fills the attempt timeline with real 500s, and
 * produces a success rate that is neither 0 nor 100.
 *
 * <p>Guarded by {@code retryx.demo.enabled}, which the {@code prod} profile forces to false, and by
 * an existence check so restarting does not accumulate duplicates.
 */
@Component
@ConditionalOnProperty(name = "retryx.demo.enabled", havingValue = "true", matchIfMissing = false)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ApiKeyRepository apiKeys;
    private final WebhookEndpointRepository endpoints;
    private final ApiKeyHasher hasher;
    private final AesGcmSecretCipher cipher;
    private final RetryxProperties.Demo config;

    public DemoDataSeeder(
            ApiKeyRepository apiKeys,
            WebhookEndpointRepository endpoints,
            ApiKeyHasher hasher,
            AesGcmSecretCipher cipher,
            RetryxProperties properties) {
        this.apiKeys = apiKeys;
        this.endpoints = endpoints;
        this.hasher = hasher;
        this.cipher = cipher;
        this.config = properties.demo();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String ownerId = config.ownerId();

        if (apiKeys.existsByOwnerId(ownerId)) {
            log.info("demo data already present for owner '{}'", ownerId);
            banner();
            return;
        }

        String rawKey = config.apiKey();
        apiKeys.save(
                new ApiKey(ownerId, hasher.hash(rawKey), hasher.displayPrefix(rawKey), "demo seed key"));

        seedEndpoint(
                ownerId,
                "Orders service",
                config.receiverBaseUrl() + "/hooks",
                "Healthy receiver. Acknowledges with 200 straight away.");

        seedEndpoint(
                ownerId,
                "Analytics sink (flaky)",
                config.receiverBaseUrl() + "/hooks/flaky",
                "Fails about 40% of requests with a 500, so the retry engine has something to do.");

        log.info("seeded demo owner '{}' with 2 endpoints", ownerId);
        banner();
    }

    private void seedEndpoint(String ownerId, String name, String url, String description) {
        // The seeded secret is the configured fixed value, so the bundled receiver — which is given
        // the same value — can verify every signature. A real endpoint gets a generated one.
        String secret = config.signingSecret();
        endpoints.save(
                new WebhookEndpoint(
                        ownerId, name, url, cipher.encrypt(secret), cipher.preview(secret), description, null));
    }

    /** Prints the demo key where a developer will actually see it. */
    private void banner() {
        log.info(
                """

                ────────────────────────────────────────────────────────────────────
                  Retryx is running.

                  Dashboard : http://localhost:5173
                  API       : http://localhost:8080/api
                  Receiver  : {}

                  Demo API key (paste it into the dashboard):
                      {}

                  Send one event:
                      curl -X POST http://localhost:8080/api/events \\
                        -H "X-Api-Key: {}" \\
                        -H "Content-Type: application/json" \\
                        -H "Idempotency-Key: demo-1" \\
                        -d '{{"eventType":"order.created","payload":{{"orderId":"A-1","total":42.5}}}}'
                ────────────────────────────────────────────────────────────────────
                """,
                config.receiverBaseUrl(),
                config.apiKey(),
                config.apiKey());
    }
}
