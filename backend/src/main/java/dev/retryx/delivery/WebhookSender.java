package dev.retryx.delivery;

import dev.retryx.config.RetryxProperties;
import dev.retryx.domain.AttemptOutcome;
import dev.retryx.queue.DeliveryJob;
import dev.retryx.security.AesGcmSecretCipher;
import dev.retryx.security.BlockedDestinationException;
import dev.retryx.security.HmacSigner;
import dev.retryx.security.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Performs exactly one HTTP attempt and reports what happened.
 *
 * <p>This class has two rules, and they are the reason the delivery pipeline is safe:
 *
 * <ol>
 *   <li><strong>It never touches the database.</strong> No repository, no {@code EntityManager}, no
 *       transaction. The claim transaction has already committed before this method is entered.
 *       That is what stops a ten-second receiver timeout from pinning a connection-pool slot and
 *       holding row locks, which would starve the web tier and defeat {@code SKIP LOCKED} for every
 *       other worker.
 *   <li><strong>It never throws for a delivery failure.</strong> A timeout, a 500, a refused
 *       connection are all normal, expected outcomes of this method and come back as a
 *       {@link SendResult}. Exceptions are reserved for genuine programming errors.
 *   </ol>
 *
 * <p>The signing order matters and is easy to get subtly wrong: build the bytes, sign <em>those</em>
 * bytes, send <em>those same</em> bytes. The body is built once, held in a local, signed, and
 * handed to {@code BodyPublishers.ofByteArray}. At no point is it re-serialised, re-encoded or
 * rebuilt — a signature over a body that was regenerated between signing and sending is a
 * signature over something else.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final HttpClient httpClient;
    private final SsrfGuard ssrfGuard;
    private final HmacSigner signer;
    private final AesGcmSecretCipher cipher;
    private final EventEnvelopeWriter envelopeWriter;
    private final RetryClassifier classifier;
    private final RetryxProperties.Http httpConfig;

    public WebhookSender(
            HttpClient httpClient,
            SsrfGuard ssrfGuard,
            HmacSigner signer,
            AesGcmSecretCipher cipher,
            EventEnvelopeWriter envelopeWriter,
            RetryClassifier classifier,
            RetryxProperties properties) {
        this.httpClient = httpClient;
        this.ssrfGuard = ssrfGuard;
        this.signer = signer;
        this.cipher = cipher;
        this.envelopeWriter = envelopeWriter;
        this.classifier = classifier;
        this.httpConfig = properties.http();
    }

    /**
     * Sends one webhook.
     *
     * @param job the claimed delivery, with payload, URL and encrypted secret already loaded
     * @return what happened, including timing and a response snippet; never null, never throws for
     *     a delivery-level failure
     */
    public SendResult send(DeliveryJob job) {
        URI target;
        try {
            // Re-validated on EVERY attempt, not just at registration. DNS changes. An endpoint
            // registered against a public IP in January can resolve to 10.0.0.5 in March, and
            // attempt 6 of a delivery happens long after attempt 1 was checked.
            target = ssrfGuard.validate(job.url()).uri();
        } catch (BlockedDestinationException e) {
            log.warn("delivery {} blocked: {}", job.deliveryId(), e.getReason());
            return SendResult.blocked(e.getReason());
        }

        byte[] body = envelopeWriter.write(job);
        long timestamp = Instant.now().getEpochSecond();

        String signatureHeader;
        try {
            String secret = cipher.decrypt(job.encryptedSecret());
            signatureHeader = signer.signatureHeader(secret, timestamp, body);
        } catch (RuntimeException e) {
            // A secret that cannot be decrypted means the encryption key changed or the row is
            // corrupt. Retrying will not fix either, and sending unsigned would be worse.
            log.error("delivery {} could not be signed: {}", job.deliveryId(), e.getMessage());
            return new SendResult(
                    AttemptOutcome.PERMANENT_ERROR, null, 0, "could not decrypt signing secret", null, null);
        }

        HttpRequest request =
                HttpRequest.newBuilder(target)
                        .timeout(Duration.ofMillis(httpConfig.readTimeoutMs()))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("User-Agent", httpConfig.userAgent())
                        .header("Accept", "*/*")
                        .header(WebhookHeaders.EVENT_ID, job.eventId().toString())
                        .header(WebhookHeaders.DELIVERY_ID, job.deliveryId().toString())
                        .header(WebhookHeaders.EVENT_TYPE, job.eventType())
                        .header(WebhookHeaders.ATTEMPT, Integer.toString(job.attemptNumber()))
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(timestamp))
                        .header(WebhookHeaders.SIGNATURE, signatureHeader)
                        .header(WebhookHeaders.IDEMPOTENCY_KEY, job.eventId().toString())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

        BoundedBodyCollector collector = new BoundedBodyCollector(httpConfig.maxResponseSnippetBytes());
        long startedAt = System.nanoTime();
        try {
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(collector));
            int durationMs = elapsedMs(startedAt);
            int status = response.statusCode();
            String snippet = collector.asString();

            AttemptOutcome outcome = classifier.classifyStatus(status);
            if (outcome == AttemptOutcome.SUCCESS) {
                return SendResult.success(status, durationMs, snippet, signatureHeader);
            }
            return SendResult.httpFailure(outcome, status, durationMs, snippet, signatureHeader);

        } catch (InterruptedException e) {
            // Shutdown. Restore the flag and let the lease expire so another worker picks it up.
            Thread.currentThread().interrupt();
            return SendResult.transportFailure(
                    AttemptOutcome.RETRYABLE_ERROR, elapsedMs(startedAt), "worker interrupted", signatureHeader);

        } catch (Exception e) {
            return SendResult.transportFailure(
                    classifier.classifyException(e), elapsedMs(startedAt), classifier.describe(e), signatureHeader);
        }
    }

    private static int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    /**
     * Collects at most {@code limit} bytes of the response body and discards the rest.
     *
     * <p>Why not {@code BodyHandlers.ofString()}: the destination URL is chosen by a user, so the
     * response size is chosen by a user. An unbounded handler against a receiver that answers with a
     * gigabyte — by malice or by accident — is an out-of-memory in the worker. A streaming consumer
     * keeps the memory bound explicit while still letting the request-level timeout cover the whole
     * exchange, which is what {@code ofInputStream} would give away.
     */
    private static final class BoundedBodyCollector implements Consumer<Optional<byte[]>> {

        private final int limit;
        private final ByteArrayOutputStream buffer;
        private boolean truncated;

        BoundedBodyCollector(int limit) {
            this.limit = limit;
            this.buffer = new ByteArrayOutputStream(Math.min(limit, 512));
        }

        @Override
        public void accept(Optional<byte[]> chunk) {
            if (chunk.isEmpty()) {
                return; // end of stream
            }
            int remaining = limit - buffer.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            byte[] bytes = chunk.get();
            int take = Math.min(remaining, bytes.length);
            buffer.write(bytes, 0, take);
            if (take < bytes.length) {
                truncated = true;
            }
        }

        /** @return the collected prefix as UTF-8 text, or null if the body was empty */
        String asString() {
            if (buffer.size() == 0) {
                return null;
            }
            String text = buffer.toString(StandardCharsets.UTF_8);
            return truncated ? text + "… [truncated]" : text;
        }
    }
}
