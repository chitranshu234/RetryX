package dev.retryx.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A webhook receiver, for demonstrating and load-testing Retryx.
 *
 * <p>It plays two roles.
 *
 * <p><strong>A reference implementation.</strong> {@link SignatureVerifier} is the code a real
 * consumer of Retryx webhooks has to write, and it is written here independently of the sender's
 * implementation on purpose: if both sides shared a class, the signature scheme would be "whatever
 * that class does" rather than a documented protocol, and an incompatibility would be impossible to
 * detect from inside this repository.
 *
 * <p><strong>A controllable failure injector.</strong> You cannot demonstrate a retry engine against
 * a receiver that always works. The routes here fail on demand — probabilistically, always, slowly,
 * or permanently — so every branch of the retry classifier can be exercised on purpose instead of
 * waited for.
 *
 * <p>It also counts what it receives, which is what turns claims like "no duplicate sends across
 * workers" into a number. See {@link ReceiverStats}.
 */
@SpringBootApplication
public class ReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReceiverApplication.class, args);
    }
}
