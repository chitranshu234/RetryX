package dev.retryx.error;

/**
 * The requested resource does not exist, <em>or</em> belongs to a different owner.
 *
 * <p>Those two cases are deliberately indistinguishable. Answering 403 for "exists but is not
 * yours" and 404 for "does not exist" turns the API into an oracle: an attacker with any valid key
 * can enumerate ids and learn exactly which ones exist in other tenants, which is information they
 * should not have even without being able to read the rows. Both answer 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param resource human-readable resource name, e.g. "endpoint"
     * @param id       the id that was not found
     */
    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " " + id + " was not found");
    }

    /** @param message full message */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
