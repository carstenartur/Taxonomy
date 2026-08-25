package com.taxonomy.shared.config;

import com.taxonomy.analysis.session.AnalysisDraftConflictException;
import com.taxonomy.analysis.session.AnalysisDraftValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Global exception handler for all REST controllers.
 * Prevents stack traces from leaking to clients and returns
 * consistent JSON error responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that Spring MVC binding
 * exceptions (e.g. missing required parameters, type mismatches) are correctly
 * returned as 4xx responses rather than being caught by the generic 500 handler.
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String DEFAULT_INTERNAL_MESSAGE =
            "An internal error occurred. Please try again or check the server logs.";

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Handles IllegalArgumentException (bad input from client).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException ex,
            WebRequest request) {
        log.warn("Bad request on {} ({})", requestPath(request), ex.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientMessage(ex.getMessage(), HttpStatus.BAD_REQUEST),
                request);
    }

    /** Return malformed or oversized working drafts as a stable client error. */
    @ExceptionHandler(AnalysisDraftValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftValidation(
            AnalysisDraftValidationException ex,
            WebRequest request) {
        log.warn("Invalid analysis draft on {} ({})",
                requestPath(request), ex.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientMessage(ex.getMessage(), HttpStatus.BAD_REQUEST),
                request);
    }

    /**
     * Preserve the optimistic-concurrency contract expected by browser tabs.
     * The generic catch-all must never turn a stale draft revision into HTTP 500.
     */
    @ExceptionHandler(AnalysisDraftConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftConflict(
            AnalysisDraftConflictException ex,
            WebRequest request) {
        log.warn("Analysis draft conflict on {} ({})",
                requestPath(request), ex.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                clientMessage(ex.getMessage(), HttpStatus.CONFLICT),
                request);
    }

    /**
     * Handles authorization failures raised after the security filter chain,
     * for example while validating an explicit browser-tab workspace pin.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex,
            WebRequest request) {
        log.warn("Access denied on {} ({})",
                requestPath(request), ex.getClass().getSimpleName());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(
                "error.forbidden", null, "Access denied.", locale);
        return buildErrorResponse(HttpStatus.FORBIDDEN, message, request);
    }

    /**
     * Catch-all handler for any unhandled exception.
     * Logs the full stack trace server-side but only returns a safe message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex,
            WebRequest request) {
        log.error("Unhandled exception on {} ({})",
                requestPath(request), ex.getClass().getName(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                internalMessage(),
                request);
    }

    /**
     * Override the Spring MVC base handler to return our consistent JSON format
     * for framework-level exceptions (missing params, type mismatches, etc.).
     * Framework-originated 5xx exceptions must not copy their internal message
     * into the response body.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String message;
        if (status.is5xxServerError()) {
            log.error("Spring MVC exception on {} ({})",
                    requestPath(request), ex.getClass().getName(), ex);
            message = internalMessage();
        } else {
            log.warn("Spring MVC client exception on {}: {} ({})",
                    requestPath(request), status.value(), ex.getClass().getSimpleName());
            message = clientMessage(ex.getMessage(), status);
        }

        Map<String, Object> errorBody = errorBody(status, message, request);
        return ResponseEntity.status(status).headers(headers).body(errorBody);
    }

    private String internalMessage() {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(
                "error.internal",
                null,
                DEFAULT_INTERNAL_MESSAGE,
                locale);
    }

    private static String clientMessage(String message, HttpStatus fallbackStatus) {
        return message == null || message.isBlank()
                ? fallbackStatus.getReasonPhrase()
                : message;
    }

    private static String requestPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=")
                ? description.substring("uri=".length())
                : description;
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String message,
            WebRequest request) {
        return ResponseEntity.status(status).body(errorBody(status, message, request));
    }

    private static Map<String, Object> errorBody(
            HttpStatus status,
            String message,
            WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", requestPath(request));
        return body;
    }
}
