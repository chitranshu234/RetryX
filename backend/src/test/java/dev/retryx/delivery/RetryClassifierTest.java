package dev.retryx.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.retryx.domain.AttemptOutcome;
import dev.retryx.security.BlockedDestinationException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Retry classification.
 *
 * <p>Table-driven, because this is policy rather than logic: the value of the test is that every
 * decision is written down and visible, so changing one is a visible change to a list rather than
 * an edit buried in a conditional.
 */
class RetryClassifierTest {

    private final RetryClassifier classifier = new RetryClassifier();

    @ParameterizedTest(name = "{0} is a success")
    @ValueSource(ints = {200, 201, 202, 204, 299})
    @DisplayName("any 2xx counts as acknowledged")
    void success(int status) {
        // Receivers legitimately answer 200, 201, 202 or 204. Insisting on 200 would fail
        // deliveries that worked.
        assertThat(classifier.classifyStatus(status)).isEqualTo(AttemptOutcome.SUCCESS);
    }

    @ParameterizedTest(name = "{0} is retryable")
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504, 599})
    @DisplayName("server trouble and explicit back-off signals are retried")
    void retryable(int status) {
        assertThat(classifier.classifyStatus(status)).isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
    }

    @ParameterizedTest(name = "{0} is permanent")
    @ValueSource(ints = {400, 401, 403, 404, 405, 409, 410, 422, 451})
    @DisplayName("statements about the request are not retried")
    void permanent(int status) {
        // The request will be byte-identical next time, so the answer will be too. Retrying a 404
        // six times is six requests spent confirming a fact already known.
        assertThat(classifier.classifyStatus(status)).isEqualTo(AttemptOutcome.PERMANENT_ERROR);
    }

    @ParameterizedTest(name = "{0} is permanent")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    @DisplayName("redirects are permanent, because Retryx does not follow them")
    void redirectsArePermanent(int status) {
        // Following a redirect would let a public URL bounce us to 169.254.169.254 and walk
        // straight through the SSRF guard, so the client is configured never to. A 3xx is
        // therefore a configuration error: register the final URL.
        assertThat(classifier.classifyStatus(status)).isEqualTo(AttemptOutcome.PERMANENT_ERROR);
    }

    @Test
    @DisplayName("timeouts, refusals and resets are retryable")
    void transportFailuresAreRetryable() {
        assertThat(classifier.classifyException(new SocketTimeoutException("Read timed out")))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
        assertThat(classifier.classifyException(new HttpTimeoutException("request timed out")))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
        assertThat(classifier.classifyException(new ConnectException("Connection refused")))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
        assertThat(classifier.classifyException(new IOException("Connection reset by peer")))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("an unresolvable host is retryable, deliberately")
    void unknownHostIsRetryable() {
        // It looks permanent, and a deleted DNS record is. But resolver blips are common, and the
        // retry budget bounds the cost of being wrong at six attempts — where being wrong the other
        // way silently drops a customer's event.
        assertThat(classifier.classifyException(new UnknownHostException("api.example.com")))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("a failed TLS handshake is permanent")
    void tlsFailureIsPermanent() {
        // An expired or mismatched certificate needs an operator, not another attempt 30 seconds
        // later. The row lands in FAILED where it is visible, and replay re-sends it once fixed.
        assertThat(classifier.classifyException(new SSLHandshakeException("certificate expired")))
                .isEqualTo(AttemptOutcome.PERMANENT_ERROR);
    }

    @Test
    @DisplayName("an SSRF rejection is its own outcome, not a failure to retry")
    void blockedDestination() {
        assertThat(classifier.classifyException(new BlockedDestinationException("loopback")))
                .isEqualTo(AttemptOutcome.BLOCKED);
    }

    @Test
    @DisplayName("unwraps nested causes before deciding")
    void unwrapsCauses() {
        // Client libraries wrap transport failures two or three layers deep. Classifying the
        // wrapper is how "every failure is a ResourceAccessException" bugs happen.
        Exception wrapped =
                new RuntimeException("request failed", new IllegalStateException("io", new SocketTimeoutException("timeout")));

        assertThat(classifier.classifyException(wrapped)).isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("survives a self-referential cause chain")
    void circularCause() {
        // A malformed exception chain must not spin forever inside the classifier.
        Exception first = new RuntimeException("a");
        Exception second = new RuntimeException("b", first);
        first.initCause(second);

        assertThat(classifier.classifyException(first)).isNotNull();
    }

    @Test
    @DisplayName("an unexpected exception is retried rather than dropped")
    void unknownIsRetryable() {
        // If Retryx itself throws something unforeseen, the conservative reading is "our bug, not
        // their rejection". Retrying wastes a few attempts; treating it as permanent loses an event.
        assertThat(classifier.classifyException(new NullPointerException()))
                .isEqualTo(AttemptOutcome.RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("describes failures compactly for the attempt log")
    void describe() {
        assertThat(classifier.describe(new SocketTimeoutException("Read timed out")))
                .isEqualTo("SocketTimeoutException: Read timed out");
        assertThat(classifier.describe(new ConnectException())).isEqualTo("ConnectException");
    }
}
