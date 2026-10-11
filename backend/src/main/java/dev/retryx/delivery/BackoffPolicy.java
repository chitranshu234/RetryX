package dev.retryx.delivery;

import dev.retryx.config.RetryxProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides when a failed delivery may be tried again.
 *
 * <h2>The series</h2>
 *
 * <pre>
 *   exp(n)   = min( base × multiplier^(n-1), max )
 *   delay(n) = jitter( exp(n) )
 * </pre>
 *
 * With the defaults (base 2s, multiplier 2, cap 1h, 6 attempts) the unjittered series is
 * 2s, 4s, 8s, 16s, 32s — so a delivery that is going to fail has done so within about a minute,
 * while a receiver that is down for a few minutes is still caught by the later attempts.
 *
 * <h2>Why exponential, and why capped</h2>
 *
 * Exponential growth is the cheap way to serve two incompatible goals at once: recover fast from a
 * blip (the early retries are seconds apart) without hammering a receiver that is genuinely down
 * (the later ones back off hard). Linear backoff has to pick one.
 *
 * <p>The cap exists because doubling is not something to leave unbounded — attempt 20 would be
 * eleven days out. A ceiling keeps the retry window a product decision rather than an accident of
 * arithmetic.
 *
 * <h2>Clocks</h2>
 *
 * {@link #nextAttemptAt} uses the application clock, unlike leases, which use the database clock.
 * That asymmetry is deliberate: a lease is a mutual-exclusion primitive shared between machines and
 * clock skew there causes double sends, whereas a few milliseconds of skew on "try again in eight
 * seconds" changes nothing. See {@link dev.retryx.queue.DeliveryQueueDao}.
 *
 * <h2>Testability</h2>
 *
 * The randomness arrives through a {@link DoubleSupplier}, so tests can pin it to 0.0 or 1.0 and
 * assert the exact bounds of each jitter strategy instead of asserting "the number is somewhere in
 * a range", which is how flaky tests are born.
 */
@Component
public class BackoffPolicy {

    /**
     * Never schedule a retry closer than this. Full jitter can draw a near-zero delay, and an
     * immediate retry is both useless (nothing has changed in 3ms) and a tight loop against a
     * failing receiver.
     */
    private static final Duration MINIMUM_DELAY = Duration.ofMillis(250);

    private final RetryxProperties.Retry config;
    private final DoubleSupplier random;

    /**
     * Production constructor.
     *
     * @param properties supplies base delay, multiplier, cap, jitter strategy and budget
     */
    @Autowired
    public BackoffPolicy(RetryxProperties properties) {
        this(properties.retry(), () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Test constructor.
     *
     * @param config retry settings
     * @param random source of values in {@code [0, 1)}
     */
    public BackoffPolicy(RetryxProperties.Retry config, DoubleSupplier random) {
        this.config = config;
        this.random = random;
    }

    /**
     * Delay before the attempt following the one that just failed.
     *
     * @param failedAttemptNumber one-based number of the attempt that just failed
     * @return how long to wait, at least {@link #MINIMUM_DELAY}
     */
    public Duration delayAfter(int failedAttemptNumber) {
        int exponent = Math.max(0, failedAttemptNumber - 1);

        // Math.pow on a double, then clamp, so a large exponent saturates instead of overflowing.
        double exponential = config.baseDelaySeconds() * Math.pow(config.multiplier(), exponent);
        double capped = Math.min(exponential, config.maxDelaySeconds());
        double jittered = applyJitter(capped);

        Duration delay = Duration.ofMillis(Math.round(jittered * 1000));
        return delay.compareTo(MINIMUM_DELAY) < 0 ? MINIMUM_DELAY : delay;
    }

    /**
     * Absolute time to write into {@code deliveries.next_attempt_at}.
     *
     * @param failedAttemptNumber one-based number of the attempt that just failed
     * @return now plus {@link #delayAfter}
     */
    public OffsetDateTime nextAttemptAt(int failedAttemptNumber) {
        return OffsetDateTime.now().plus(delayAfter(failedAttemptNumber));
    }

    /**
     * The unjittered delay, exposed so the dashboard can show "retrying in ~8s" and tests can check
     * the underlying series separately from the randomisation.
     *
     * @param failedAttemptNumber one-based number of the attempt that just failed
     * @return the deterministic exponential delay, capped
     */
    public Duration exponentialDelay(int failedAttemptNumber) {
        int exponent = Math.max(0, failedAttemptNumber - 1);
        double exponential = config.baseDelaySeconds() * Math.pow(config.multiplier(), exponent);
        return Duration.ofMillis(Math.round(Math.min(exponential, config.maxDelaySeconds()) * 1000));
    }

    /** @return the configured default retry budget */
    public int defaultMaxAttempts() {
        return config.maxAttempts();
    }

    /** @return the jitter strategy in force */
    public JitterStrategy jitter() {
        return config.jitter();
    }

    private double applyJitter(double seconds) {
        return switch (config.jitter()) {
            case NONE -> seconds;
            case FULL -> random.getAsDouble() * seconds;
            case EQUAL -> seconds / 2 + random.getAsDouble() * (seconds / 2);
        };
    }
}
