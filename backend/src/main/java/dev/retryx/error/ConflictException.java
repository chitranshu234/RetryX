package dev.retryx.error;

/**
 * The request is valid but conflicts with the resource's current state — mapped to 409.
 *
 * <p>Used when replay is asked for on a delivery that is still {@code PENDING} or
 * {@code IN_PROGRESS}: the caller's intent ("send this again") is already in progress, and
 * honouring it would mean two attempts racing over one row.
 */
public class ConflictException extends RuntimeException {

    /** @param message explanation of the conflict */
    public ConflictException(String message) {
        super(message);
    }
}
