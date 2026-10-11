package dev.retryx.delivery;

import dev.retryx.domain.AttemptOutcome;

/**
 * The outcome of one HTTP send, as a value.
 *
 * <p>{@link WebhookSender} never throws for a delivery failure and never touches the database. It
 * returns one of these, and {@link DeliveryProcessor} decides what it means for the queue. Keeping
 * "what happened on the wire" separate from "what to do about it" is what makes the retry rules
 * unit-testable without a socket, and the sender testable without a database.
 *
 * @param outcome         verdict for this attempt
 * @param statusCode      HTTP status, or null if no response arrived
 * @param durationMs      wall-clock time of the HTTP call alone
 * @param errorMessage    short failure description, or null on success
 * @param responseSnippet leading bytes of the response body, truncated
 * @param signatureHeader the signature header value sent, for reproducibility
 */
public record SendResult(
        AttemptOutcome outcome,
        Integer statusCode,
        int durationMs,
        String errorMessage,
        String responseSnippet,
        String signatureHeader) {

    /**
     * @param statusCode      the 2xx returned
     * @param durationMs      duration of the call
     * @param responseSnippet response body prefix
     * @param signatureHeader signature sent
     * @return a success result
     */
    public static SendResult success(
            int statusCode, int durationMs, String responseSnippet, String signatureHeader) {
        return new SendResult(
                AttemptOutcome.SUCCESS, statusCode, durationMs, null, responseSnippet, signatureHeader);
    }

    /**
     * A response arrived but was not a 2xx.
     *
     * @param outcome         retryable or permanent, per {@link RetryClassifier}
     * @param statusCode      the status returned
     * @param durationMs      duration of the call
     * @param responseSnippet response body prefix, usually the most useful debugging artefact
     * @param signatureHeader signature sent
     * @return a failure result
     */
    public static SendResult httpFailure(
            AttemptOutcome outcome,
            int statusCode,
            int durationMs,
            String responseSnippet,
            String signatureHeader) {
        return new SendResult(
                outcome,
                statusCode,
                durationMs,
                "receiver responded " + statusCode,
                responseSnippet,
                signatureHeader);
    }

    /**
     * No response: timeout, refused connection, TLS failure.
     *
     * @param outcome         retryable or permanent, per {@link RetryClassifier}
     * @param durationMs      time spent before giving up
     * @param errorMessage    description of the transport failure
     * @param signatureHeader signature that would have been sent, if we got that far
     * @return a transport failure result
     */
    public static SendResult transportFailure(
            AttemptOutcome outcome, int durationMs, String errorMessage, String signatureHeader) {
        return new SendResult(outcome, null, durationMs, errorMessage, null, signatureHeader);
    }

    /**
     * The SSRF guard refused the destination, so nothing was sent.
     *
     * @param reason why the destination was rejected
     * @return a blocked result
     */
    public static SendResult blocked(String reason) {
        return new SendResult(AttemptOutcome.BLOCKED, null, 0, reason, null, null);
    }

    /** @return true when the receiver acknowledged with a 2xx */
    public boolean isSuccess() {
        return outcome == AttemptOutcome.SUCCESS;
    }
}
