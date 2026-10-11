package dev.retryx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Retryx — a reliable webhook delivery service.
 *
 * <p>Retryx accepts events over HTTP, fans each one out to the webhook endpoints an owner has
 * registered, and keeps trying until the receiver acknowledges or the retry budget runs out. Every
 * attempt is recorded, and failures can be replayed.
 *
 * <p>The whole system is one Spring Boot process plus PostgreSQL. There is no message broker:
 * {@code deliveries} <em>is</em> the queue, and workers claim rows from it with
 * {@code FOR UPDATE SKIP LOCKED}. Running more copies of this process scales delivery throughput
 * and no copy will ever send another copy's row.
 *
 * <p>Delivery is <strong>at-least-once</strong>. See DEVELOPER.md for why exactly-once is not on
 * offer and what receivers must do about it.
 *
 * @see dev.retryx.delivery.DeliveryWorker the poll/claim/dispatch loop
 * @see dev.retryx.queue.DeliveryQueueDao the SKIP LOCKED claim query
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RetryxApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetryxApplication.class, args);
    }
}
