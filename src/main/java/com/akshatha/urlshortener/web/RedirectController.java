package com.akshatha.urlshortener.web;

import com.akshatha.urlshortener.service.ShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The read path. This is the hot one — roughly 100x the write traffic — and it
 * sits between a user's click and the page they wanted. Every millisecond here
 * is a millisecond of blank tab.
 */
@RestController
@Tag(name = "Redirect", description = "Public redirect endpoint")
public class RedirectController {

    private final ShortenerService shortenerService;

    public RedirectController(ShortenerService shortenerService) {
        this.shortenerService = shortenerService;
    }

    /**
     * The path pattern is constrained to the Base62 alphabet and a plausible
     * length. An unconstrained /{code} at the application root is greedy: it
     * would swallow /swagger-ui.html, /actuator/health and /favicon.ico. The
     * regex excludes all three (they contain '.', '-' or a second segment) and
     * it also means a malformed code 404s at routing time, before any service
     * or database call.
     *
     * 302 FOUND, not 301. A 301 is cached by the browser indefinitely, so
     * repeat clicks never come back to us: fastest for the user, and it
     * destroys the click analytics that are the actual product of a shortener.
     * The explicit no-store also stops intermediaries caching the redirect.
     * See docs/decisions.md.
     */
    @GetMapping("/{code:[0-9A-Za-z]{4,10}}")
    @Operation(summary = "Redirect to the destination URL")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirect to the destination"),
            @ApiResponse(responseCode = "404", description = "Unknown short code"),
            @ApiResponse(responseCode = "410", description = "Short code has expired"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        String longUrl = shortenerService.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
