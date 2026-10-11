package dev.retryx.security;

import dev.retryx.config.RetryxProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops Retryx from being used as a proxy into networks it should not reach — Server-Side Request
 * Forgery.
 *
 * <h2>The attack</h2>
 *
 * Retryx takes a URL from a user and makes an HTTP request to it from inside the deployment's
 * network. That is the whole product, and it is also a textbook SSRF primitive. Register
 * {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/} as a webhook endpoint,
 * send yourself an event, and read the cloud instance's IAM credentials out of the response snippet
 * on the delivery-detail page. Or point it at {@code http://10.0.0.17:6379} and port-scan the VPC
 * by reading the error messages.
 *
 * <h2>The defence, in layers</h2>
 *
 * <ol>
 *   <li><strong>Scheme allowlist.</strong> http/https only. Blocks {@code file://},
 *       {@code gopher://}, {@code redis://}, {@code jar://}.
 *   <li><strong>No credentials in the URL.</strong> {@code http://evil@internal/} reads as the host
 *       "internal" to a request library and as the host "evil" to many URL parsers and human
 *       reviewers. Reject the ambiguity rather than resolve it.
 *   <li><strong>Port allowlist.</strong> Removes the port scanner.
 *   <li><strong>Hostname denylist.</strong> {@code localhost}, {@code *.internal},
 *       {@code *.local}, the cloud metadata names.
 *   <li><strong>Resolve the name and check <em>every</em> address.</strong> A hostile host can
 *       publish one public A record and one at {@code 127.0.0.1}; checking only the first answer
 *       means losing a coin flip. Loopback, link-local (which covers the
 *       {@code 169.254.169.254} metadata address), private, CGNAT, multicast, reserved and IPv6
 *       unique-local ranges are all rejected — including when smuggled inside an IPv4-mapped,
 *       6to4 or NAT64 IPv6 address.
 *   <li><strong>No redirect following</strong>, configured on the HTTP client in
 *       {@link dev.retryx.config.HttpClientConfig}. Otherwise every check above is bypassed by a
 *       public URL answering {@code 302 Location: http://169.254.169.254/}.
 *   <li><strong>Validate twice</strong> — once when the endpoint is registered (fast feedback) and
 *       again immediately before every send. The second is the one that counts: DNS can change
 *       between registration and delivery, and between delivery attempt 1 and attempt 6.
 * </ol>
 *
 * <h2>Known limitation: DNS rebinding</h2>
 *
 * There is still a window between "resolve and validate" and "connect", and nothing stops a
 * resolver from returning a different answer for the connect. Closing it properly means pinning the
 * validated {@link InetAddress} and connecting to that IP with the original {@code Host} header
 * (and, for TLS, SNI). {@link #validate} returns the resolved addresses precisely so a future
 * pinning connection factory can use them; the current {@code RestClient} does not pin. This is
 * documented as a known gap rather than quietly ignored — see DEVELOPER.md.
 *
 * <h2>{@code allow-hosts}</h2>
 *
 * The {@code retryx.ssrf.allow-hosts} property exempts hosts from the address checks so the bundled
 * receiver on {@code localhost} works in development and tests. <strong>Every entry is a hole in
 * this defence.</strong> The {@code prod} profile forces it empty.
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Hostnames and suffixes that are never legitimate webhook destinations. */
    private static final List<String> DENIED_HOST_SUFFIXES =
            List.of(
                    "localhost",
                    ".localhost",
                    ".local",
                    ".internal",
                    ".localdomain",
                    "metadata.google.internal",
                    "instance-data",
                    "metadata.goog");

    private final RetryxProperties.Ssrf config;

    public SsrfGuard(RetryxProperties properties) {
        this.config = properties.ssrf();
    }

    /**
     * A destination that passed every check, together with the addresses it resolved to.
     *
     * @param uri       the parsed, validated URL
     * @param addresses every address the host resolved to, all of them public
     */
    public record Destination(URI uri, List<InetAddress> addresses) {}

    /**
     * Validates a webhook destination.
     *
     * @param rawUrl the URL as supplied by the user
     * @return the parsed URI plus its resolved addresses
     * @throws BlockedDestinationException if any check fails
     */
    public Destination validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BlockedDestinationException("URL is required");
        }
        if (rawUrl.length() > 2000) {
            throw new BlockedDestinationException("URL is too long");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new BlockedDestinationException("URL is not valid: " + e.getReason());
        }

        if (!uri.isAbsolute()) {
            throw new BlockedDestinationException("URL must be absolute and include a scheme");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new BlockedDestinationException("scheme '" + scheme + "' is not allowed; use http or https");
        }

        // The guard can be switched off only for tests that assert on its absence.
        if (!config.enabled()) {
            log.warn("SSRF guard is DISABLED — destination {} accepted without checks", rawUrl);
            return new Destination(uri, List.of());
        }

        if (config.requireHttps() && !"https".equals(scheme)) {
            throw new BlockedDestinationException("https is required");
        }

        if (uri.getRawUserInfo() != null) {
            throw new BlockedDestinationException("credentials in the URL are not allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // getHost() returns null for things like "http://foo_bar/" that URI cannot parse as a
            // registry-based authority. Rejecting is correct: we will not guess.
            throw new BlockedDestinationException("URL host could not be parsed");
        }
        host = host.toLowerCase(Locale.ROOT);
        // Strip the brackets URI keeps around IPv6 literals.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!config.allowedPorts().contains(port)) {
            throw new BlockedDestinationException("port " + port + " is not allowed");
        }

        // Explicit development exemption, checked before anything expensive.
        if (isExplicitlyAllowed(host)) {
            log.debug("host {} is in retryx.ssrf.allow-hosts; skipping address checks", host);
            return new Destination(uri, List.of());
        }

        for (String denied : DENIED_HOST_SUFFIXES) {
            if (host.equals(denied) || (denied.startsWith(".") && host.endsWith(denied))) {
                throw new BlockedDestinationException("host '" + host + "' is not an allowed destination");
            }
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BlockedDestinationException("host '" + host + "' could not be resolved");
        }
        if (resolved.length == 0) {
            throw new BlockedDestinationException("host '" + host + "' resolved to no addresses");
        }

        // Every answer must pass. One private address among several is enough to reject.
        for (InetAddress address : resolved) {
            String rejection = rejectionReason(address);
            if (rejection != null) {
                throw new BlockedDestinationException(
                        "host '" + host + "' resolves to " + address.getHostAddress() + " (" + rejection + ")");
            }
        }

        return new Destination(uri, Arrays.asList(resolved));
    }

    /**
     * Convenience wrapper for callers that only need a yes/no.
     *
     * @param rawUrl candidate URL
     * @return true if {@link #validate} would accept it
     */
    public boolean isAllowed(String rawUrl) {
        try {
            validate(rawUrl);
            return true;
        } catch (BlockedDestinationException e) {
            return false;
        }
    }

    private boolean isExplicitlyAllowed(String host) {
        Set<String> allowed = config.allowHosts();
        return allowed != null && !allowed.isEmpty() && allowed.contains(host);
    }

    /**
     * Classifies a single resolved address.
     *
     * @param address address to inspect
     * @return null if the address is a legitimate public destination, otherwise a short reason
     */
    private String rejectionReason(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return "wildcard address";
        }
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isLinkLocalAddress()) {
            // Covers 169.254.0.0/16, and therefore the 169.254.169.254 cloud metadata service.
            return "link-local";
        }
        if (address.isSiteLocalAddress()) {
            return "private network";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }

        byte[] bytes = address.getAddress();

        if (address instanceof Inet4Address) {
            return rejectionReasonIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            return rejectionReasonIpv6(bytes);
        }
        return "unrecognised address family";
    }

    /** Ranges that {@code InetAddress}'s own predicates do not cover. */
    private String rejectionReasonIpv4(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;

        if (b0 == 0) {
            return "0.0.0.0/8 (this network)";
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return "100.64.0.0/10 (carrier NAT)";
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return "192.0.0.0/24 (IETF protocol assignments)";
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {
            return "192.0.2.0/24 (documentation)";
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return "198.18.0.0/15 (benchmarking)";
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return "198.51.100.0/24 (documentation)";
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return "203.0.113.0/24 (documentation)";
        }
        if (b0 >= 240) {
            return "240.0.0.0/4 (reserved)";
        }
        return null;
    }

    /**
     * IPv6 ranges, plus the transition formats that can smuggle an IPv4 address past an
     * IPv4-only check.
     */
    private String rejectionReasonIpv6(byte[] b) {
        // fc00::/7 — unique local. Java's isSiteLocalAddress() only knows the deprecated fec0::/10.
        if ((b[0] & 0xFE) == 0xFC) {
            return "fc00::/7 (unique local)";
        }
        // 2001:0000::/32 — Teredo tunnels an embedded IPv4 destination.
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && b[2] == 0x00 && b[3] == 0x00) {
            return "2001::/32 (Teredo tunnel)";
        }
        // ::ffff:a.b.c.d — IPv4-mapped. Re-check the embedded address.
        if (isPrefix(b, new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF})) {
            return embeddedIpv4Reason(b, 12, "IPv4-mapped");
        }
        // 64:ff9b::/96 — NAT64. Same trick, different wrapper.
        if (isPrefix(b, new int[] {0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0})) {
            return embeddedIpv4Reason(b, 12, "NAT64-mapped");
        }
        // 2002::/16 — 6to4 carries the IPv4 address in bytes 2-5.
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return embeddedIpv4Reason(b, 2, "6to4-mapped");
        }
        // ::a.b.c.d — IPv4-compatible, deprecated and never legitimate here.
        if (isPrefix(b, new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}) && !isAllZero(b)) {
            return "::/96 (deprecated IPv4-compatible)";
        }
        return null;
    }

    private String embeddedIpv4Reason(byte[] b, int offset, String label) {
        byte[] v4 = new byte[] {b[offset], b[offset + 1], b[offset + 2], b[offset + 3]};
        try {
            InetAddress inner = InetAddress.getByAddress(v4);
            String reason = rejectionReason(inner);
            return reason == null ? null : label + " " + inner.getHostAddress() + " (" + reason + ")";
        } catch (UnknownHostException e) {
            // getByAddress with 4 bytes cannot fail; defensive only.
            return label + " address could not be parsed";
        }
    }

    private boolean isPrefix(byte[] bytes, int[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
