package dev.retryx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.retryx.config.RetryxProperties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF protection.
 *
 * <p>Every case here is an attack someone has actually used against a webhook service. The cloud
 * metadata address in particular is not hypothetical: it is the standard first move, because
 * {@code 169.254.169.254} hands out IAM credentials to anything on the instance that asks.
 *
 * <p>Note what these tests do <em>not</em> need: a network. Hostnames resolve through the JVM's
 * resolver to addresses that are either loopback or well-known public ranges, so the suite does not
 * depend on DNS being reachable — except in the handful of cases that explicitly do, which are kept
 * to names guaranteed by RFC 2606 or to literal addresses.
 */
class SsrfGuardTest {

    private static SsrfGuard guard(boolean requireHttps, Set<String> allowHosts) {
        RetryxProperties properties =
                new RetryxProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new RetryxProperties.Ssrf(true, requireHttps, Set.of(80, 443, 8080, 9090), allowHosts),
                        null,
                        null);
        return new SsrfGuard(properties);
    }

    private final SsrfGuard guard = guard(false, Set.of());

    @Nested
    @DisplayName("scheme")
    class Scheme {

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(
                strings = {
                    "file:///etc/passwd",
                    "gopher://127.0.0.1:6379/_SET%20key%20value",
                    "redis://localhost:6379",
                    "jar:http://example.com!/",
                    "ftp://example.com/",
                    "data:text/plain,hello",
                })
        @DisplayName("only http and https are allowed")
        void rejectsOtherSchemes(String url) {
            // gopher:// in particular is a classic: it can be used to write arbitrary bytes to a
            // TCP port, which turns an SSRF into "run commands on your Redis".
            assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BlockedDestinationException.class);
        }

        @Test
        @DisplayName("https can be made mandatory")
        void requiresHttpsWhenConfigured() {
            SsrfGuard strict = guard(true, Set.of());

            assertThatThrownBy(() -> strict.validate("http://example.com/hook"))
                    .hasMessageContaining("https is required");
        }

        @Test
        @DisplayName("a relative URL is refused")
        void rejectsRelative() {
            assertThatThrownBy(() -> guard.validate("/hooks")).hasMessageContaining("absolute");
        }
    }

    @Nested
    @DisplayName("addresses")
    class Addresses {

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(
                strings = {
                    "http://127.0.0.1/hook",
                    "http://127.0.0.2/hook",
                    "http://[::1]/hook",
                    "http://0.0.0.0/hook",
                    "http://10.0.0.5/hook",
                    "http://172.16.4.2/hook",
                    "http://192.168.1.1/hook",
                    "http://169.254.169.254/latest/meta-data/",
                    "http://100.64.0.1/hook",
                    "http://[fd00::1]/hook",
                    "http://[fe80::1]/hook",
                    "http://224.0.0.1/hook",
                    "http://0.1.2.3/hook",
                    "http://192.0.0.1/hook",
                    "http://198.18.0.1/hook",
                    "http://240.0.0.1/hook",
                })
        @DisplayName("private, loopback, link-local, CGNAT and reserved ranges are refused")
        void rejectsPrivateAddresses(String url) {
            assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BlockedDestinationException.class);
        }

        @Test
        @DisplayName("the cloud metadata address is refused by name and by number")
        void rejectsMetadataService() {
            // The single most valuable SSRF target in any cloud deployment.
            assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data/iam/"))
                    .hasMessageContaining("link-local");
            assertThatThrownBy(() -> guard.validate("http://metadata.google.internal/computeMetadata/"))
                    .hasMessageContaining("not an allowed destination");
        }

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(
                strings = {
                    "http://[::ffff:127.0.0.1]/hook",
                    "http://[::ffff:10.0.0.1]/hook",
                    "http://[64:ff9b::7f00:1]/hook",
                    "http://[2002:7f00:0001::]/hook",
                    "http://[2001:0:1234::]/hook",
                })
        @DisplayName("IPv4 smuggled inside IPv6 is unwrapped and refused")
        void rejectsIpv6WrappedIpv4(String url) {
            // An IPv4-only check accepts ::ffff:127.0.0.1 and then connects to loopback. Same for
            // NAT64, 6to4 and Teredo. Each of these is a working bypass of a naive guard.
            assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BlockedDestinationException.class);
        }
    }

    @Nested
    @DisplayName("hostnames")
    class Hostnames {

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(
                strings = {
                    "http://localhost/hook",
                    "http://api.localhost/hook",
                    "http://db.internal/hook",
                    "http://printer.local/hook",
                    "http://host.localdomain/hook",
                })
        @DisplayName("internal naming conventions are refused")
        void rejectsInternalNames(String url) {
            assertThatThrownBy(() -> guard.validate(url)).isInstanceOf(BlockedDestinationException.class);
        }

        @Test
        @DisplayName("credentials in the URL are refused")
        void rejectsUserInfo() {
            // http://expected-host@actual-host/ reads as two different hosts depending on which
            // parser you ask. Rejecting the ambiguity is safer than picking a reading.
            assertThatThrownBy(() -> guard.validate("https://user:pass@example.com/hook"))
                    .hasMessageContaining("credentials in the URL");
            assertThatThrownBy(() -> guard.validate("https://example.com@127.0.0.1/hook"))
                    .hasMessageContaining("credentials in the URL");
        }

        @Test
        @DisplayName("a host that cannot be resolved is refused, not guessed")
        void rejectsUnresolvable() {
            // .invalid is reserved by RFC 2606 and guaranteed never to resolve.
            assertThatThrownBy(() -> guard.validate("https://nothing-here.invalid/hook"))
                    .hasMessageContaining("could not be resolved");
        }
    }

    @Nested
    @DisplayName("ports")
    class Ports {

        @ParameterizedTest(name = "port {0} is refused")
        @ValueSource(ints = {22, 25, 3306, 5432, 6379, 9200, 11211, 27017})
        @DisplayName("ports outside the allowlist are refused")
        void rejectsDisallowedPorts(int port) {
            // Without this, error messages alone turn Retryx into a port scanner for the VPC.
            assertThatThrownBy(() -> guard.validate("https://example.com:" + port + "/hook"))
                    .hasMessageContaining("port " + port + " is not allowed");
        }

        @Test
        @DisplayName("the scheme's default port is used when none is given")
        void defaultsByScheme() {
            // https://… has no port in the URL but is port 443, and must be checked against the
            // allowlist as 443 rather than skipped. A literal public address is used rather than a
            // hostname so the assertion does not depend on DNS being reachable.
            assertThat(guard.isAllowed("https://8.8.8.8/hook")).isTrue();
            assertThat(guard.isAllowed("http://8.8.8.8/hook")).isTrue();
        }

        @Test
        @DisplayName("an explicitly allowed port is accepted")
        void allowsListedPorts() {
            assertThat(guard.isAllowed("https://8.8.8.8:8080/hook")).isTrue();
        }
    }

    @Nested
    @DisplayName("the development exemption")
    class Exemption {

        @Test
        @DisplayName("allow-hosts lets a listed host through the address checks")
        void allowsListedHosts() {
            // Needed so the bundled receiver on localhost works. Every entry is a hole, which is
            // why the prod profile forces the list empty.
            SsrfGuard lenient = guard(false, Set.of("localhost", "127.0.0.1"));

            assertThat(lenient.isAllowed("http://localhost:9090/hooks")).isTrue();
            assertThat(lenient.isAllowed("http://127.0.0.1:9090/hooks")).isTrue();
        }

        @Test
        @DisplayName("an exemption does not disable the port allowlist")
        void exemptionIsNotCarteBlanche() {
            SsrfGuard lenient = guard(false, Set.of("localhost"));

            // The exemption skips the address checks, not every check. Pointing a webhook at
            // localhost:6379 should still be refused.
            assertThatThrownBy(() -> lenient.validate("http://localhost:6379/hook"))
                    .hasMessageContaining("port 6379");
        }

        @Test
        @DisplayName("an exemption does not allow a non-http scheme")
        void exemptionDoesNotAllowSchemes() {
            SsrfGuard lenient = guard(false, Set.of("localhost"));

            assertThatThrownBy(() -> lenient.validate("file://localhost/etc/passwd"))
                    .hasMessageContaining("not allowed");
        }
    }

    @Nested
    @DisplayName("input handling")
    class Input {

        @Test
        @DisplayName("blank and oversized URLs are refused")
        void rejectsDegenerateInput() {
            assertThatThrownBy(() -> guard.validate(null)).hasMessageContaining("required");
            assertThatThrownBy(() -> guard.validate("")).hasMessageContaining("required");
            assertThatThrownBy(() -> guard.validate("   ")).hasMessageContaining("required");
            assertThatThrownBy(() -> guard.validate("https://example.com/" + "x".repeat(2100)))
                    .hasMessageContaining("too long");
        }

        @Test
        @DisplayName("an unparseable URL is refused rather than throwing")
        void rejectsUnparseable() {
            assertThatThrownBy(() -> guard.validate("https://exa mple.com/hook"))
                    .isInstanceOf(BlockedDestinationException.class);
            assertThatThrownBy(() -> guard.validate("http://"))
                    .isInstanceOf(BlockedDestinationException.class);
        }

        @Test
        @DisplayName("isAllowed never throws")
        void isAllowedIsSafe() {
            assertThat(guard.isAllowed("http://127.0.0.1/")).isFalse();
            assertThat(guard.isAllowed("garbage")).isFalse();
            assertThat(guard.isAllowed(null)).isFalse();
        }
    }
}
