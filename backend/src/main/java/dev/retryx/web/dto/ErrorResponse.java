package dev.retryx.web.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The single error shape every failure in the API uses.
 *
 * <p>One shape, always. A client that handles errors should not have to discover that validation
 * failures look different from 404s, which look different from whatever the servlet container
 * produces when nothing catches an exception.
 *
 * @param error     stable machine-readable code, e.g. {@code not_found}. Safe to branch on; the
 *                  message is not.
 * @param message   human-readable explanation
 * @param status    HTTP status, repeated in the body so it survives logging and proxying
 * @param fields    per-field validation messages, present only for validation failures
 * @param timestamp when the error was produced
 */
public record ErrorResponse(
        String error, String message, int status, Map<String, String> fields, OffsetDateTime timestamp) {

    /**
     * @param error   machine-readable code
     * @param message explanation
     * @param status  HTTP status
     * @return an error body with no field detail
     */
    public static ErrorResponse of(String error, String message, int status) {
        return new ErrorResponse(error, message, status, null, OffsetDateTime.now());
    }

    /**
     * @param message explanation
     * @param fields  field name → message
     * @return a validation error body
     */
    public static ErrorResponse validation(String message, Map<String, String> fields) {
        return new ErrorResponse("validation_failed", message, 400, fields, OffsetDateTime.now());
    }
}
