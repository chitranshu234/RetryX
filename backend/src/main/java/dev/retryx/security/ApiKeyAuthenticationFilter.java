package dev.retryx.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the API key off the request and, if it checks out, populates the security context.
 *
 * <p>Accepts either header, because both are conventions callers expect:
 *
 * <pre>
 *   X-Api-Key: rtx_sk_…
 *   Authorization: Bearer rtx_sk_…
 * </pre>
 *
 * <h2>What this filter does not do</h2>
 *
 * It does not reject anything. An absent or bad key leaves the security context empty and the
 * request continues; {@link SecurityConfig}'s authorisation rules are what return 401. Keeping
 * authentication and authorisation in separate places means a new public endpoint is a one-line
 * change to the rules rather than a condition added to this filter — and it means this filter cannot
 * accidentally become the thing that decides what is public.
 *
 * <p>It extends {@link OncePerRequestFilter} because a plain {@code Filter} runs again on internal
 * dispatches (forwards to the error page, async re-dispatch), which would re-authenticate and, with
 * the {@code last_used_at} throttle, double-count usage.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyAuthenticator authenticator;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {

        // Don't re-authenticate if something upstream already did.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            extractKey(request)
                    .flatMap(authenticator::authenticate)
                    .ifPresent(
                            principal -> {
                                var authentication =
                                        new UsernamePasswordAuthenticationToken(
                                                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            });
        }

        chain.doFilter(request, response);
    }

    private Optional<String> extractKey(HttpServletRequest request) {
        String direct = request.getHeader(API_KEY_HEADER);
        if (direct != null && !direct.isBlank()) {
            return Optional.of(direct.trim());
        }
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return Optional.of(authorization.substring(BEARER_PREFIX.length()).trim());
        }
        return Optional.empty();
    }
}
