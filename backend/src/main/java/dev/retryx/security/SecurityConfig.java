package dev.retryx.security;

import dev.retryx.config.RetryxProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The security rules.
 *
 * <h2>Why CSRF is disabled, and why that is safe here</h2>
 *
 * CSRF attacks work because browsers attach <em>ambient</em> credentials — cookies — to
 * cross-origin requests automatically. Retryx authenticates with a header the caller has to set
 * explicitly, holds no session, and issues no cookie, so there is nothing for an attacker's page to
 * ride on: a forged form post from {@code evil.com} arrives with no {@code X-Api-Key} and is simply
 * a 401. Disabling CSRF on a cookie-authenticated API is a serious hole; disabling it on a
 * stateless header-authenticated one is correct, and leaving it on would break every non-browser
 * client for no benefit.
 *
 * <h2>Stateless</h2>
 *
 * {@code SessionCreationPolicy.STATELESS}. No {@code JSESSIONID}, so any number of API nodes can
 * sit behind a load balancer with no sticky sessions and no session replication.
 *
 * <h2>{@code denyAll} at the end</h2>
 *
 * The last rule is {@code anyRequest().denyAll()} rather than {@code authenticated()}. It means a
 * controller added tomorrow under a path nobody listed here is unreachable until someone deliberately
 * opens it — the rules fail closed. The alternative fails open, and a forgotten mapping becomes an
 * unauthenticated endpoint that nobody notices.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final RetryxProperties properties;

    public SecurityConfig(RetryxProperties properties) {
        this.properties = properties;
    }

    /**
     * @param http               the builder
     * @param apiKeyFilter       authentication filter, inserted before the username/password filter
     *                           so it runs early in the chain
     * @return the filter chain
     * @throws Exception if the builder fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // Browsers send the CORS preflight without credentials by design.
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        // Liveness/readiness for orchestrators. Detailed health is still gated.
                                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                                "unauthorized",
                                                                "Provide a valid API key in the X-Api-Key header."))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                HttpServletResponse.SC_FORBIDDEN,
                                                                "forbidden",
                                                                "This key may not access that resource.")));

        return http.build();
    }

    /**
     * CORS for the dashboard.
     *
     * <p>Origins are an explicit list from configuration, never {@code *}. A wildcard origin
     * combined with {@code allowCredentials} is rejected by browsers anyway, and a wildcard without
     * it would still let any site on the internet read responses from a user's Retryx if it got hold
     * of a key.
     *
     * @return the CORS source used by the filter chain
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-Api-Key", "Authorization", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Idempotent-Replay"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Writes an error body in the same shape the rest of the API uses. */
    private static void writeProblem(HttpServletResponse response, int status, String error, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response
                .getWriter()
                .write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\",\"status\":" + status + "}");
    }
}
