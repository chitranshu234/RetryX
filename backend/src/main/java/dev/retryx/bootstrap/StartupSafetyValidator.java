package dev.retryx.bootstrap;

import dev.retryx.config.RetryxProperties;
import dev.retryx.security.AesGcmSecretCipher;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to run a production deployment with development defaults.
 *
 * <h2>Why a startup check rather than documentation</h2>
 *
 * Every insecure default in this project is documented as insecure. That is not enough. The failure
 * mode for "the README said to change it" is a deployment that works perfectly, passes every test,
 * serves traffic for a year, and is signing webhooks with a key committed to a public repository.
 * Nothing about it looks wrong at any point.
 *
 * <p>So the dangerous configurations are made <em>loud</em>: with the {@code prod} profile active,
 * the application will not start while the bundled encryption key is in use, and it logs an
 * unmistakable warning if the SSRF host exemptions are populated. A deployment that fails to start
 * is a ten-minute problem; a deployment that silently uses a public key is an incident.
 *
 * <p>In development the same conditions are a one-line info message, because interrupting local work
 * to warn about the local defaults is just noise that teaches people to ignore warnings.
 */
@Component
public class StartupSafetyValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyValidator.class);

    private final RetryxProperties properties;
    private final Environment environment;

    public StartupSafetyValidator(RetryxProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        boolean usingDevKey =
                AesGcmSecretCipher.INSECURE_DEV_KEY.equals(properties.crypto().encryptionKey());
        boolean hasSsrfExemptions =
                properties.ssrf().allowHosts() != null && !properties.ssrf().allowHosts().isEmpty();
        boolean ssrfDisabled = !properties.ssrf().enabled();

        if (production) {
            if (usingDevKey) {
                throw new IllegalStateException(
                        """
                        Refusing to start: RETRYX_ENCRYPTION_KEY is still the bundled development key, \
                        which is published in this repository. Every endpoint signing secret in the \
                        database would be decryptable by anyone who has read the source. \
                        Generate one with:  openssl rand -base64 32""");
            }
            if (ssrfDisabled) {
                throw new IllegalStateException(
                        "Refusing to start: retryx.ssrf.enabled=false in production turns this service "
                                + "into an open HTTP proxy into your own network.");
            }
            if (hasSsrfExemptions) {
                log.error(
                        "retryx.ssrf.allow-hosts is not empty in production: {}. Every host listed can "
                                + "resolve to a private address and bypass the SSRF checks.",
                        properties.ssrf().allowHosts());
            }
        } else {
            if (usingDevKey) {
                log.info(
                        "using the bundled development encryption key — fine locally, fatal in production "
                                + "(the prod profile refuses to start with it)");
            }
            if (hasSsrfExemptions) {
                log.info(
                        "SSRF host exemptions active for local development: {}", properties.ssrf().allowHosts());
            }
        }

        log.info(
                "Retryx ready — retry budget {} attempts, base {}s, x{}, cap {}s, jitter {}",
                properties.retry().maxAttempts(),
                properties.retry().baseDelaySeconds(),
                properties.retry().multiplier(),
                properties.retry().maxDelaySeconds(),
                properties.retry().jitter());
    }
}
