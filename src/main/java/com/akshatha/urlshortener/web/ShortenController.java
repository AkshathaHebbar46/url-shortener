package com.akshatha.urlshortener.web;

import com.akshatha.urlshortener.domain.UrlMapping;
import com.akshatha.urlshortener.service.ShortenerService;
import com.akshatha.urlshortener.web.dto.ShortenRequest;
import com.akshatha.urlshortener.web.dto.ShortenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The write path. Low traffic, so correctness beats speed here.
 *
 * Thin by design: there is no business branching in this class, only the
 * HTTP-shaped concerns — status code, Location header, DTO mapping.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Shorten", description = "Create short URLs")
public class ShortenController {

    private final ShortenerService shortenerService;

    public ShortenController(ShortenerService shortenerService) {
        this.shortenerService = shortenerService;
    }

    /**
     * 201 Created with a Location header. 200 would work, but 201 is the
     * correct answer for a call that creates a new, addressable resource.
     *
     * @Valid is what actually triggers the annotations on ShortenRequest.
     * Without it they are decoration and nothing is validated — including
     * @SafeUrl, which is the security control on this endpoint.
     */
    @PostMapping("/shorten")
    @Operation(summary = "Create a short URL for a destination")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Short URL created"),
            @ApiResponse(responseCode = "400", description = "Invalid or unsafe URL"),
            @ApiResponse(responseCode = "409", description = "Custom alias already taken"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        UrlMapping mapping = shortenerService.shorten(
                request.longUrl(), request.customAlias(), request.expiresAt());

        String shortUrl = shortenerService.shortUrlFor(mapping.getShortCode());
        ShortenResponse body = new ShortenResponse(
                mapping.getShortCode(),
                shortUrl,
                mapping.getLongUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt());

        return ResponseEntity.created(URI.create(shortUrl)).body(body);
    }
}
