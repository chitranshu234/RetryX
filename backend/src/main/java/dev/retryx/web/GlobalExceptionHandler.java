package dev.retryx.web;

import dev.retryx.error.ConflictException;
import dev.retryx.error.ResourceNotFoundException;
import dev.retryx.security.BlockedDestinationException;
import dev.retryx.web.dto.ErrorResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into the API's single error shape.
 *
 * <p>Centralised so controllers contain no error plumbing: a service throws a domain exception and
 * the status code is decided once, here. The alternative — try/catch in every handler method — gets
 * the mapping subtly inconsistent across endpoints, and makes adding an exception type a change to
 * every controller that can throw it.
 *
 * <h2>What is deliberately not in a response body</h2>
 *
 * The {@link #onUnexpected} handler returns a fixed string and logs the stack trace server-side. An
 * exception message can carry a SQL fragment, a file path, a class name, a connection string — all
 * of which tell an attacker about the inside of the system and tell the caller nothing they can act
 * on. The {@code traceId} lets someone correlate their failed request with the server log that does
 * have the detail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * @param e the not-found exception
     * @return 404 with a generic code
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("not_found", e.getMessage(), 404));
    }

    /**
     * @param e the conflict
     * @return 409
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> onConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("conflict", e.getMessage(), 409));
    }

    /**
     * A destination URL was rejected by the SSRF guard.
     *
     * <p>400, not 500: the request was understood and is being refused, and the caller can fix it by
     * supplying a different URL. The reason is included because it is genuinely actionable ("port
     * 6379 is not allowed") and reveals nothing about the internal network that the caller did not
     * already supply in the request.
     *
     * @param e the rejection
     * @return 400 with the reason
     */
    @ExceptionHandler(BlockedDestinationException.class)
    public ResponseEntity<ErrorResponse> onBlockedDestination(BlockedDestinationException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("destination_not_allowed", e.getReason(), 400));
    }

    /**
     * Bean-validation failure on a request body.
     *
     * @param e the validation exception
     * @return 400 with a field-by-field breakdown
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.validation("the request body is not valid", fields));
    }

    /**
     * @param e the illegal argument
     * @return 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("bad_request", e.getMessage(), 400));
    }

    /**
     * Malformed JSON, or a body that cannot be bound.
     *
     * @param e the parse failure
     * @return 400 without echoing the parser's internals
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("malformed_json", "the request body could not be parsed as JSON", 400));
    }

    /**
     * A path variable of the wrong type, typically a malformed UUID.
     *
     * @param e the mismatch
     * @return 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("bad_request", "'" + e.getName() + "' is not a valid value", 400));
    }

    /**
     * Anything unhandled.
     *
     * @param e the exception
     * @return 500 with a correlation id and no internal detail
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled error [trace {}]", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ErrorResponse.of(
                                "internal_error", "something went wrong. Trace id: " + traceId, 500));
    }
}
