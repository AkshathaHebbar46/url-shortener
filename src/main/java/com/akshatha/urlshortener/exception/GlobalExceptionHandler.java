package com.akshatha.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One place where exceptions become HTTP responses, using ProblemDetail
 * (RFC 7807).
 *
 * Extends ResponseEntityExceptionHandler deliberately. That base class already
 * maps Spring MVC's own exceptions — no handler for the path, wrong method,
 * unsupported media type, unreadable body — onto the right 4xx statuses.
 * Without it, the catch-all below swallows every one of them and returns 500,
 * so an ordinary crawler asking for /favicon.ico gets a server error and a
 * full stack trace in the error log. That was the behaviour before this class
 * extended anything; see docs/decisions.md.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI VALIDATION_TYPE = URI.create("urn:shortener:validation-failed");
    private static final URI NOT_FOUND_TYPE = URI.create("urn:shortener:code-not-found");
    private static final URI GONE_TYPE = URI.create("urn:shortener:code-expired");
    private static final URI CONFLICT_TYPE = URI.create("urn:shortener:alias-taken");
    private static final URI RATE_LIMIT_TYPE = URI.create("urn:shortener:rate-limited");
    private static final URI UNAVAILABLE_TYPE = URI.create("urn:shortener:dependency-unavailable");
    private static final URI INTERNAL_TYPE = URI.create("urn:shortener:internal-error");

    /**
     * Replaces Spring's default body for bean-validation failures, which is
     * verbose and leaks class names. Field errors come back as a flat map so a
     * client can attach each message to its own input.
     *
     * Overrides the base class rather than declaring a second handler for the
     * same exception type, which would be an ambiguous mapping.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.merge(fieldError.getField(),
                        fieldError.getDefaultMessage(),
                        (a, b) -> a + "; " + b));
        ex.getBindingResult().getGlobalErrors().forEach(globalError ->
                errors.put(globalError.getObjectName(), globalError.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION_TYPE);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Malformed JSON, or a body that will not bind at all. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        log.debug("unreadable request body", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body could not be parsed as JSON.");
        problem.setTitle("Malformed request");
        problem.setType(VALIDATION_TYPE);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ShortCodeNotFoundException.class)
    public ProblemDetail handleNotFound(ShortCodeNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No destination is registered for this short code.");
        problem.setTitle("Short code not found");
        problem.setType(NOT_FOUND_TYPE);
        return problem;
    }

    @ExceptionHandler(ShortCodeExpiredException.class)
    public ProblemDetail handleExpired(ShortCodeExpiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE, "This short code has expired.");
        problem.setTitle("Short code expired");
        problem.setType(GONE_TYPE);
        return problem;
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ProblemDetail handleAliasTaken(AliasAlreadyExistsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "That alias is already in use. Choose another.");
        problem.setTitle("Alias unavailable");
        problem.setType(CONFLICT_TYPE);
        return problem;
    }

    /**
     * 429 with Retry-After. Returning 429 with no hint about when to come back
     * just produces tight retry loops.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Retry in "
                        + e.getRetryAfterSeconds() + "s.");
        problem.setTitle("Too many requests");
        problem.setType(RATE_LIMIT_TYPE);
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * 503, not 500: the write could not be served because a dependency is
     * down, which is a transient condition the caller should retry — not a bug
     * in the request. Logged at WARN without a stack trace, because a Redis
     * outage is an operational event and one trace per rejected write would
     * bury the one that matters.
     */
    @ExceptionHandler(RedisUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(RedisUnavailableException e) {
        log.warn("rejecting a write: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Short URLs cannot be created right now. Redirects are unaffected.");
        problem.setTitle("Temporarily unavailable");
        problem.setType(UNAVAILABLE_TYPE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem);
    }

    /**
     * Catch-all for genuinely unexpected failures. The response carries a
     * correlation id and nothing else; the stack trace goes to the log under
     * that same id. Stack traces in responses tell an attacker the framework
     * versions and package layout.
     *
     * Everything Spring MVC raises for a malformed or misrouted request is
     * handled by the base class above, so anything reaching here is a real
     * defect and deserves the ERROR level it gets.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.error("unhandled exception correlationId={} method={} path={}",
                correlationId, request.getMethod(), request.getRequestURI(), e);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Quote the correlation id if you contact support.");
        problem.setTitle("Internal server error");
        problem.setType(INTERNAL_TYPE);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
