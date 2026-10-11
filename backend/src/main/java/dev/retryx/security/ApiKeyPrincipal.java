package dev.retryx.security;

import java.util.UUID;

/**
 * The authenticated caller.
 *
 * <p>{@code ownerId} is the tenant boundary for the entire application. Every repository method
 * that reads user data takes it, and the API never exposes a lookup that does not. Controllers
 * receive this through {@code @AuthenticationPrincipal}, so the owner comes from the verified
 * credential and can never be read from a request parameter or a body field — which is the shape of
 * the bug where {@code ?ownerId=someone-else} returns someone else's webhooks.
 *
 * @param ownerId   tenant this key authenticates as
 * @param apiKeyId  which key was used, for audit
 * @param keyPrefix display-safe prefix of that key
 */
public record ApiKeyPrincipal(String ownerId, UUID apiKeyId, String keyPrefix) {

    @Override
    public String toString() {
        return ownerId + "/" + keyPrefix;
    }
}
