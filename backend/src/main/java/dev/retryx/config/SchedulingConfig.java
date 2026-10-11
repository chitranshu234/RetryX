package dev.retryx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler that drives the worker poll and the stale-claim sweep.
 *
 * <p>Spring's default {@code @Scheduled} executor is a <strong>single</strong> thread. With two
 * scheduled jobs on one thread, the reaper cannot run while the worker's poll is busy — and the
 * worker's poll blocks until its whole batch of HTTP sends completes, which with default timeouts
 * can be thirteen seconds against a dead receiver. The reaper would then only run between batches,
 * precisely when the system is least healthy and recovery matters most. Two threads keep the two
 * jobs independent.
 *
 * <p>{@code waitForTasksToCompleteOnShutdown} plus a termination wait pairs with
 * {@code server.shutdown: graceful}: on SIGTERM, in-flight sends finish and record their outcome
 * instead of being abandoned mid-call. Rows abandoned mid-call are recoverable — the reaper exists
 * for exactly that — but recovering them costs a lease expiry and risks a duplicate send, so
 * finishing cleanly is strictly better during a planned deploy.
 */
@Configuration
public class SchedulingConfig {

    /**
     * @return the task scheduler used by every {@code @Scheduled} method in the application
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("retryx-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
