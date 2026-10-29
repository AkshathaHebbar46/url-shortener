package com.akshatha.urlshortener.validation;

import com.akshatha.urlshortener.config.ShortenerProperties;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The security tests. Each rejected case below is a real attack shape, not a
 * tidiness check — see the class comment on SafeUrlValidator.
 *
 * Positive cases use literal public IPs rather than hostnames so the suite
 * does not depend on DNS being reachable from wherever it runs.
 */
class SafeUrlValidatorTest {

    private SafeUrlValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        ShortenerProperties properties = new ShortenerProperties(
                "https://sho.rt", 7, 1000,
                ShortenerProperties.Allocator.SEQUENCE,
                new ShortenerProperties.Cache(false, Duration.ofHours(24),
                        Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofSeconds(30)),
                new ShortenerProperties.RateLimit(true, 10, 100, true));

        validator = new SafeUrlValidator(properties);
        context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34/",
            "https://93.184.216.34/some/path?q=1#frag",
            "https://8.8.8.8",
            "HTTPS://1.1.1.1/upper-case-scheme"
    })
    void acceptsPublicHttpAndHttpsUrls(String url) {
        assertThat(validator.isValid(url, context)).isTrue();
    }

    /** Null-ness belongs to @NotBlank; two messages for one mistake is worse than one. */
    @Test
    void acceptsNullAndLeavesItToNotBlank() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(document.cookie)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///etc/passwd",
            "jar:http://example.com/a.jar!/",
            "ftp://93.184.216.34/pub",
            "gopher://93.184.216.34:70/"
    })
    void rejectsEverySchemeExceptHttpAndHttps(String url) {
        assertThat(validator.isValid(url, context)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin",
            "http://127.0.0.1:8080/",
            "http://[::1]/",
            "http://0.0.0.0/",
            "http://localhost/admin"
    })
    void rejectsLoopbackAndWildcardDestinations(String url) {
        assertThat(validator.isValid(url, context)).isFalse();
    }

    /**
     * The one that matters most on EC2: 169.254.169.254 is the instance
     * metadata endpoint, and reaching it can leak IAM credentials.
     */
    @Test
    void rejectsTheEc2InstanceMetadataEndpoint() {
        assertThat(validator.isValid("http://169.254.169.254/latest/meta-data/", context)).isFalse();
        assertThat(validator.isValid("http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                context)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://[fd00::1]/",      // IPv6 unique-local, the RFC 1918 equivalent
            "http://[fe80::1]/",      // IPv6 link-local
            "http://224.0.0.1/"       // multicast
    })
    void rejectsPrivateAndInternalRanges(String url) {
        assertThat(validator.isValid(url, context)).isFalse();
    }

    /** A code pointing at another code is a redirect loop. */
    @Test
    void rejectsUrlsPointingBackAtThisShortener() {
        assertThat(validator.isValid("https://sho.rt/abc1234", context)).isFalse();
        assertThat(validator.isValid("https://SHO.RT/abc1234", context)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a url at all",
            "example.com/no-scheme",
            "http://",
            "://missing-scheme",
            "http:// space.com/"
    })
    void rejectsMalformedInput(String url) {
        assertThat(validator.isValid(url, context)).isFalse();
    }

    /**
     * Fails closed on an unresolvable host. A DNS blip costs a legitimate user
     * a retry; accepting unresolvable hosts would cost the control entirely.
     */
    @Test
    void rejectsHostsThatDoNotResolve() {
        assertThat(validator.isValid(
                "http://this-host-does-not-exist-9f3a2b1c.invalid/", context)).isFalse();
    }
}
