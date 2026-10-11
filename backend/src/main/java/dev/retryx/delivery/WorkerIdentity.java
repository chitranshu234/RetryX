package dev.retryx.delivery;

import dev.retryx.config.RetryxProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This process's identity, written into {@code deliveries.locked_by} and
 * {@code delivery_attempts.worker_id}.
 *
 * <p>Resolved once at startup and never changed. The identity has to be stable for the life of the
 * process, because it is what the lease check in {@link DeliveryOutcomeRecorder} compares against —
 * an id that changed per claim would make every lease look lost.
 *
 * <p>It also has to be <em>distinct</em> per process. The default appends a random suffix to the
 * hostname rather than using the hostname alone, because two containers on one host share a
 * hostname often enough (and two workers in one compose file certainly do) that collisions would be
 * routine, and two workers sharing an id can each pass the other's lease check — exactly the double
 * send the lease exists to prevent. Set {@code RETRYX_WORKER_ID} explicitly when you want readable
 * values in the attempt log.
 */
@Component
public class WorkerIdentity {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdentity.class);

    private final String id;

    public WorkerIdentity(RetryxProperties properties) {
        String configured = properties.worker().id();
        if (configured != null && !configured.isBlank()) {
            this.id = configured.trim();
        } else {
            this.id = hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        log.info("worker identity: {}", this.id);
    }

    /** @return the stable identity of this process */
    public String id() {
        return id;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
