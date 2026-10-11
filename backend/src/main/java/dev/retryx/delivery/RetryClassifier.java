package dev.retryx.delivery;

import dev.retryx.domain.AttemptOutcome;
import dev.retryx.security.BlockedDestinationException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.springframework.stereotype.Component;

/**
 * Decides whether a failure is worth repeating.
 *
 * <p>This is the single most consequential piece of policy in a webhook sender, and both ways of
 * getting it wrong are expensive. Retry something permanent and you spend six attempts and an hour
 * of someone's error budget re-sending a request to a URL that 404s — multiplied by every event, a
 * misconfigured endpoint becomes a self-inflicted load test. Fail something transient and you have
 * silently dropped a customer's event because their load balancer hiccuped for 200ms.
 *
 * <h2>Status codes</h2>
 *
 * <table border="1">
 *   <caption>HTTP status classification</caption>
 *   <tr><th>Status</th><th>Verdict</th><th>Reasoning</th></tr>
 *   <tr><td>2xx</td><td>SUCCESS</td><td>Acknowledged. Anything 2xx counts; receivers legitimately
 *       answer 200, 201, 202 or 204.</td></tr>
 *   <tr><td>3xx</td><td>PERMANENT</td><td>Retryx does not follow redirects — doing so would let a
 *       public URL bounce us to {@code 169.254.169.254} and walk straight through the SSRF guard. A
 *       redirect is therefore a configuration error: the user should register the final URL.</td></tr>
 *   <tr><td>408</td><td>RETRYABLE</td><td>Request Timeout. The receiver is saying "too slow,
 *       again".</td></tr>
 *   <tr><td>425</td><td>RETRYABLE</td><td>Too Early. An explicit "resend this".</td></tr>
 *   <tr><td>429</td><td>RETRYABLE</td><td>Rate limited. Backoff is exactly the right response.</td></tr>
 *   <tr><td>other 4xx</td><td>PERMANENT</td><td>400, 401, 403, 404, 410, 422 are all statements
 *       about the request or the route, and the request will be byte-identical next time. Only a
 *       human changing something fixes them, which is what replay is for.</td></tr>
 *   <tr><td>5xx</td><td>RETRYABLE</td><td>The receiver is broken, not the request. This includes
 *       501: arguably a permanent "I don't implement this", but in practice 501 is far more often a
 *       misconfigured gateway in front of a healthy app than a considered answer, so it gets the
 *       benefit of the doubt.</td></tr>
 * </table>
 *
 * <h2>Transport failures</h2>
 *
 * Timeouts, refused connections and resets are retryable — they are the definition of transient.
 * Two judgement calls worth naming:
 *
 * <ul>
 *   <li><strong>{@link UnknownHostException} is retryable.</strong> It looks permanent, and a
 *       deleted DNS record is. But a resolver blip is common, and the retry budget bounds the cost
 *       of being wrong at six attempts, while being wrong the other way drops events.
 *   <li><strong>A failed TLS handshake is permanent.</strong> An expired or hostname-mismatched
 *       certificate does not fix itself within a 30-second retry window; it needs an operator. The
 *       row lands in FAILED where it is visible, and replay re-sends it once the certificate is
 *       fixed. Other {@link IOException}s during a TLS session (a reset mid-stream) stay retryable.
 *   <li><strong>An unexpected exception is retryable.</strong> If Retryx itself throws something
 *       unforeseen, the conservative reading is "our bug, not their rejection". Retrying risks a few
 *       wasted attempts; treating it as permanent risks dropping an event because of a null pointer
 *       in our own code.
 * </ul>
 */
@Component
public class RetryClassifier {

    /**
     * Classifies a response we actually received.
     *
     * @param statusCode HTTP status from the receiver
     * @return the verdict for this attempt
     */
    public AttemptOutcome classifyStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return AttemptOutcome.SUCCESS;
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429) {
            return AttemptOutcome.RETRYABLE_ERROR;
        }
        if (statusCode >= 500 && statusCode < 600) {
            return AttemptOutcome.RETRYABLE_ERROR;
        }
        // Everything else — 3xx (we do not follow redirects) and the remaining 4xx.
        return AttemptOutcome.PERMANENT_ERROR;
    }

    /**
     * Classifies a failure that produced no response.
     *
     * @param failure the thrown exception, possibly wrapped
     * @return the verdict for this attempt
     */
    public AttemptOutcome classifyException(Throwable failure) {
        Throwable cause = rootCause(failure);

        if (cause instanceof BlockedDestinationException) {
            // Never left the process. Retrying cannot help: the URL itself is disallowed.
            return AttemptOutcome.BLOCKED;
        }
        if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException) {
            return AttemptOutcome.PERMANENT_ERROR;
        }
        if (cause instanceof SocketTimeoutException
                || cause instanceof HttpTimeoutException
                || cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof IOException) {
            return AttemptOutcome.RETRYABLE_ERROR;
        }
        // Unknown: assume it is our fault and keep the event alive.
        return AttemptOutcome.RETRYABLE_ERROR;
    }

    /**
     * Compact, human-readable failure description for the attempt log and the UI.
     *
     * @param failure the thrown exception
     * @return e.g. {@code SocketTimeoutException: Read timed out}
     */
    public String describe(Throwable failure) {
        Throwable cause = rootCause(failure);
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /**
     * Unwraps nested causes. Client libraries wrap a {@code SocketTimeoutException} two or three
     * layers deep, and classifying the wrapper instead of the cause is how "everything is a
     * ResourceAccessException" bugs happen.
     *
     * @param failure possibly-wrapped throwable
     * @return the innermost cause
     */
    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        // Bounded, so a self-referential cause chain cannot spin here.
        for (int depth = 0; depth < 10 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }
}
