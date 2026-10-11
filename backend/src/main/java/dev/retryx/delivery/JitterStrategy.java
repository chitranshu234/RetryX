package dev.retryx.delivery;

/**
 * How much randomness to add to a computed backoff delay.
 *
 * <h2>Why jitter at all</h2>
 *
 * A receiver goes down for thirty seconds. Ten thousand deliveries fail at roughly the same moment.
 * With pure exponential backoff they all wait exactly 2s, then exactly 4s, then exactly 8s — and
 * arrive in ten-thousand-request spikes, in lockstep, forever. The receiver comes back up, is
 * flattened by the first synchronised wave, fails again, and the waves get sharper with each round
 * because every failure re-synchronises the group. That is a thundering herd, and retrying is what
 * created it.
 *
 * <p>Jitter spreads the group out in time. The same ten thousand retries land smeared across the
 * window instead of stacked at its edge, which lets a recovering receiver actually recover. It also
 * de-correlates Retryx's own workers, so claim batches do not all come due on the same tick.
 */
public enum JitterStrategy {

    /**
     * {@code delay = exp}. Deterministic and therefore reproducible, which is useful in tests and
     * demos and wrong in production for the reason above.
     */
    NONE,

    /**
     * {@code delay = random(0, exp)} — AWS's "full jitter". Maximum spread and the lowest chance of
     * collisions, at the cost of sometimes retrying almost immediately and of losing the rough
     * ordering between deliveries that failed at different times.
     */
    FULL,

    /**
     * {@code delay = exp/2 + random(0, exp/2)} — "equal jitter". Keeps a guaranteed minimum wait
     * (so a struggling receiver always gets at least half the intended breathing room) while still
     * spreading the herd across half the window. Retryx's default: the guaranteed floor matters more
     * here than the last few percent of spread, because the thing being protected is somebody
     * else's server.
     */
    EQUAL
}
