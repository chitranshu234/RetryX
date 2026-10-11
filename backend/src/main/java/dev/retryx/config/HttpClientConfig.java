package dev.retryx.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP client Retryx uses to deliver webhooks.
 *
 * <p>Two settings here are load-bearing security controls, not performance tuning.
 *
 * <h2>{@code Redirect.NEVER}</h2>
 *
 * This is the other half of the SSRF defence. {@link dev.retryx.security.SsrfGuard} resolves the
 * destination and rejects private addresses — and all of that is worthless if the client then
 * follows a {@code 302 Location: http://169.254.169.254/latest/meta-data/}. The guard validated the
 * URL the user registered; a redirect is a second, unvalidated URL chosen by the receiver at
 * request time. So redirects are never followed, and a 3xx is classified as a permanent failure by
 * {@link dev.retryx.delivery.RetryClassifier}: the user is told to register the final URL instead.
 *
 * <h2>Timeouts on both phases</h2>
 *
 * The connect timeout lives on the client; the overall request timeout is set per request in
 * {@link dev.retryx.delivery.WebhookSender}. Both are required. A client with no request timeout
 * will wait forever on a receiver that accepts the connection and then never answers — the classic
 * way a worker pool dies: every thread parked on a socket read, the queue backing up, and nothing
 * in the logs because nothing has failed yet. The lease length in
 * {@code retryx.worker.lease-seconds} must stay comfortably above connect + read, or a slow send
 * outlives its own claim and another worker re-sends it.
 *
 * <h2>Why {@code java.net.http.HttpClient} and not {@code RestClient}</h2>
 *
 * The signing scheme requires sending the <em>exact</em> bytes that were signed, and this API takes
 * and returns {@code byte[]} without a message-converter layer that might re-encode anything. It
 * also exposes {@code Redirect.NEVER} and a real {@code HttpTimeoutException} directly, which makes
 * retry classification honest. Spring's {@code RestClient} is the better choice for consuming JSON
 * APIs; this is not that.
 */
@Configuration
public class HttpClientConfig {

    /**
     * @param properties supplies the connect timeout
     * @return a shared, thread-safe client for all outbound webhook sends
     */
    @Bean
    public HttpClient webhookHttpClient(RetryxProperties properties) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.http().connectTimeoutMs()))
                // Pinned to HTTP/1.1. Negotiating h2 against thousands of unknown receivers buys
                // nothing for single small POSTs and occasionally finds a broken proxy.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
