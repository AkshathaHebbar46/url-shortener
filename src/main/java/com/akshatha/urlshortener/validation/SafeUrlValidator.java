package com.akshatha.urlshortener.validation;

import com.akshatha.urlshortener.config.ShortenerProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * The security-critical class in this project.
 *
 * An open shortener that will redirect anywhere is an SSRF gadget and a
 * phishing tool. People find these within days of deployment.
 *
 * What this closes:
 *   - non-http(s) schemes (javascript:, data:, file:, jar:)
 *   - loopback, private, link-local, wildcard and multicast destinations.
 *     The one that matters most on EC2 is link-local: 169.254.169.254 is the
 *     instance metadata endpoint, and reaching it can leak IAM credentials.
 *   - self-referential URLs, which are redirect loops.
 *
 * What this knowingly does NOT close: DNS rebinding. Resolving the host here
 * and checking the IP is a TOCTOU check across two separate network calls —
 * an attacker controls their own DNS and can answer with a public address at
 * validation time and 127.0.0.1 when someone clicks. The real mitigation in
 * this design is architectural rather than in this class: the service returns
 * a 302 and never fetches the destination itself, so the request that follows
 * is the browser's, from the browser's network position, not ours. See
 * docs/decisions.md.
 */
public class SafeUrlValidator implements ConstraintValidator<SafeUrl, String> {

    private static final Logger log = LoggerFactory.getLogger(SafeUrlValidator.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final String ownHost;

    public SafeUrlValidator(ShortenerProperties properties) {
        this.ownHost = hostOf(properties.baseUrl());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null-ness is @NotNull/@NotBlank's job. Doubling up gives the user two
        // error messages for one mistake.
        if (value == null) {
            return true;
        }

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            return reject(context, "is not a valid URL");
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return reject(context, "must be an absolute URL including the scheme");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return reject(context, "scheme must be http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Covers "http:///path" and the authority forms URI parses oddly.
            return reject(context, "must include a host");
        }

        String normalisedHost = host.toLowerCase(Locale.ROOT);
        if (normalisedHost.equals(ownHost)) {
            return reject(context, "must not point back at this shortener");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Fail closed: a host we cannot resolve is either a typo or an
            // attempt to get an unresolvable name past the check. Rejecting
            // costs a legitimate user a retry during a DNS blip; accepting
            // costs us the control entirely.
            log.debug("rejecting unresolvable host {}", host);
            return reject(context, "host could not be resolved");
        } catch (SecurityException e) {
            return reject(context, "host could not be resolved");
        }

        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                log.warn("rejected internal destination host={} resolved={}",
                        host, address.getHostAddress());
                return reject(context, "must not point at a private or internal address");
            }
        }

        return true;
    }

    private static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isLinkLocalAddress()  // 169.254.0.0/16 — EC2 metadata
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    /** fc00::/7 — the IPv6 equivalent of RFC 1918, which Java has no predicate for. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = new URI(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            // A malformed base-url is a config error; ShortenerProperties
            // validation catches the blank case, and this only weakens the
            // self-reference check, so do not fail the context over it.
            log.warn("shortener.base-url is not a valid URI: {}", baseUrl);
            return null;
        }
    }

    private static boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
