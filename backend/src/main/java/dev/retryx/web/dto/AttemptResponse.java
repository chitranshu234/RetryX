package dev.retryx.web.dto;

import dev.retryx.domain.AttemptOutcome;
import dev.retryx.domain.DeliveryAttempt;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in a delivery's timeline.
 *
 * @param id                  attempt id
 * @param attemptNumber       one-based, strictly increasing for the delivery
 * @param outcome             verdict for this attempt alone
 * @param statusCode          HTTP status, null if no response arrived
 * @param responseTimeMs      duration of the HTTP call
 * @param errorMessage        failure description
 * @param responseBodySnippet leading bytes of the receiver's response — in practice the single most
 *                            useful field on this page, because it usually contains the receiver's
 *                            own error message
 * @param requestSignature    the exact signature header sent, so a developer can reproduce the HMAC
 *                            locally and settle whose calculation is wrong
 * @param workerId            which worker process sent it
 * @param attemptedAt         when it was sent
 */
public record AttemptResponse(
        UUID id,
        int attemptNumber,
        AttemptOutcome outcome,
        Integer statusCode,
        Integer responseTimeMs,
        String errorMessage,
        String responseBodySnippet,
        String requestSignature,
        String workerId,
        OffsetDateTime attemptedAt) {

    /**
     * @param attempt entity to convert
     * @return the API representation
     */
    public static AttemptResponse from(DeliveryAttempt attempt) {
        return new AttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getOutcome(),
                attempt.getStatusCode(),
                attempt.getResponseTimeMs(),
                attempt.getErrorMessage(),
                attempt.getResponseBodySnippet(),
                attempt.getRequestSignature(),
                attempt.getWorkerId(),
                attempt.getAttemptedAt());
    }
}
