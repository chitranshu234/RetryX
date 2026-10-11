package dev.retryx.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.config.RetryxProperties;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Backoff arithmetic.
 *
 * <p>The randomness is injected, so every assertion here is exact. A test that asserts "the delay
 * is somewhere between 1 and 8 seconds" passes whether the implementation is right or subtly wrong,
 * and occasionally fails for no reason — pinning the random source to 0.0 and 1.0 tests the actual
 * boundaries of each jitter strategy instead.
 */
class BackoffPolicyTest {

    /** base 2s, ×2, capped at 60s, 6 attempts. */
    private static RetryxProperties.Retry config(JitterStrategy jitter) {
        return new RetryxProperties.Retry(6, 2, 2.0, 60, jitter);
    }

    private static BackoffPolicy policy(JitterStrategy jitter, double random) {
        DoubleSupplier fixed = () -> random;
        return new BackoffPolicy(config(jitter), fixed);
    }

    @Nested
    @DisplayName("the exponential series")
    class Series {

        @ParameterizedTest(name = "attempt {0} waits {1}s")
        @CsvSource({"1, 2", "2, 4", "3, 8", "4, 16", "5, 32", "6, 60"})
        @DisplayName("doubles each attempt")
        void doubles(int failedAttempt, long expectedSeconds) {
            BackoffPolicy policy = policy(JitterStrategy.NONE, 0.5);

            assertThat(policy.delayAfter(failedAttempt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
        }

        @Test
        @DisplayName("saturates at the cap instead of growing forever")
        void capped() {
            BackoffPolicy policy = policy(JitterStrategy.NONE, 0.5);

            // Unbounded, attempt 20 would be 2 × 2^19 seconds — twelve days. The cap is what makes
            // the retry window a decision rather than an accident of arithmetic.
            assertThat(policy.delayAfter(20)).isEqualTo(Duration.ofSeconds(60));
            assertThat(policy.delayAfter(100)).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("never goes backwards as attempts increase")
        void monotonic() {
            BackoffPolicy policy = policy(JitterStrategy.NONE, 0.5);

            Duration previous = Duration.ZERO;
            for (int attempt = 1; attempt <= 10; attempt++) {
                Duration current = policy.delayAfter(attempt);
                assertThat(current).isGreaterThanOrEqualTo(previous);
                previous = current;
            }
        }

        @Test
        @DisplayName("does not overflow on an absurd attempt number")
        void noOverflow() {
            BackoffPolicy policy = policy(JitterStrategy.NONE, 0.5);

            // Math.pow on a double saturates to Infinity rather than wrapping, and min() then
            // clamps it. Doing this with long arithmetic would overflow to a negative delay and
            // schedule the retry in the past — an infinite hot loop.
            assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        @Test
        @DisplayName("NONE returns the exact exponential delay")
        void none() {
            assertThat(policy(JitterStrategy.NONE, 0.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
            assertThat(policy(JitterStrategy.NONE, 1.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        }

        @Test
        @DisplayName("FULL spans zero to the full delay")
        void full() {
            // random = 1.0 → the whole window.
            assertThat(policy(JitterStrategy.FULL, 1.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(8));

            // random = 0.0 → zero, which the minimum-delay floor rescues. Scheduling a retry 0ms
            // after a failure is a tight loop against a server that is already struggling.
            assertThat(policy(JitterStrategy.FULL, 0.0).delayAfter(3))
                    .isEqualTo(Duration.ofMillis(250));
        }

        @Test
        @DisplayName("EQUAL guarantees at least half the delay")
        void equal() {
            // The guaranteed floor is the reason EQUAL is the default: a struggling receiver always
            // gets at least half the intended breathing room, however the dice land.
            assertThat(policy(JitterStrategy.EQUAL, 0.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
            assertThat(policy(JitterStrategy.EQUAL, 1.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
            assertThat(policy(JitterStrategy.EQUAL, 0.5).delayAfter(3)).isEqualTo(Duration.ofSeconds(6));
        }

        @Test
        @DisplayName("spreads a synchronised herd across the window")
        void spreadsTheHerd() {
            // The property jitter exists for: a thousand deliveries that failed at the same instant
            // must not all retry at the same instant. With real randomness the delays must differ.
            BackoffPolicy policy = new BackoffPolicy(config(JitterStrategy.EQUAL), Math::random);

            long distinct =
                    java.util.stream.IntStream.range(0, 200)
                            .mapToObj(i -> policy.delayAfter(4))
                            .distinct()
                            .count();

            // 200 draws over a 16-second window at millisecond resolution; collisions are possible
            // but near-total collapse would mean the jitter is not being applied.
            assertThat(distinct).isGreaterThan(150);
        }
    }

    @Nested
    @DisplayName("scheduling")
    class Scheduling {

        @Test
        @DisplayName("nextAttemptAt is in the future by the computed delay")
        void nextAttemptAt() {
            BackoffPolicy policy = policy(JitterStrategy.NONE, 0.5);

            var before = java.time.OffsetDateTime.now();
            var scheduled = policy.nextAttemptAt(2);

            assertThat(scheduled).isAfter(before.plusSeconds(3));
            assertThat(scheduled).isBefore(before.plusSeconds(6));
        }

        @Test
        @DisplayName("exposes the unjittered series for display")
        void exponentialDelay() {
            // The dashboard shows "retrying in ~8s"; it needs the series, not one sample from it.
            assertThat(policy(JitterStrategy.FULL, 0.0).exponentialDelay(3))
                    .isEqualTo(Duration.ofSeconds(8));
        }
    }
}
