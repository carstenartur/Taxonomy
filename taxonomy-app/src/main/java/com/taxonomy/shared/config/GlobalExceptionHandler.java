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
 * Prevents stack traces from leaking to clients and returns consistent JSON
 * error responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC binding
 * exceptions remain 4xx responses instead of falling into the generic 500
 * handler.</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(
            GlobalExceptionHandler.class);
    private static final String DEFAULT_INTERNAL_MESSAGE =
            "An internal error occurred. Please try again or check the server logs.";

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Handles bad client input without logging or returning its payload. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            WebRequest request) {
        log.warn("Bad request on {} ({})",
                requestPath(request), exception.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientMessage(exception.getMessage(), HttpStatus.BAD_REQUEST),
                request);
    }

    /** Return malformed or oversized working drafts as a stable client error. */
    @ExceptionHandler(AnalysisDraftValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftValidation(
            AnalysisDraftValidationException exception,
            WebRequest request) {
        log.warn("Invalid analysis draft on {} ({})",
                requestPath(request), exception.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientMessage(exception.getMessage(), HttpStatus.BAD_REQUEST),
                request);
    }

    /** Preserve the optimistic-concurrency contract expected by browser tabs. */
    @ExceptionHandler(AnalysisDraftConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftConflict(
            AnalysisDraftConflictException exception,
            WebRequest request) {
        log.warn("Analysis draft conflict on {} ({})",
                requestPath(request), exception.getClass().getSimpleName());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                clientMessage(exception.getMessage(), HttpStatus.CONFLICT),
                request);
    }

    /** Handle post-filter-chain authorization failures with localized text. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException exception,
            WebRequest request) {
        log.warn("Access denied on {} ({})",
                requestPath(request), exception.getClass().getSimpleName());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(
                "error.forbidden", null, "Access denied.", locale);
        return buildErrorResponse(HttpStatus.FORBIDDEN, message, request);
    }

    /** Log unexpected failures server-side and return only a safe message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception exception,
            WebRequest request) {
        log.error("Unhandled exception on {} ({})",
                requestPath(request), exception.getClass().getName(), exception);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                internalMessage(),
                request);
    }

    /**
     * Return the common JSON format for framework exceptions. Framework-originated
     * 5xx exceptions never copy their internal message into the response body.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
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
                    requestPath(request), exception.getClass().getName(), exception);
            message = internalMessage();
        } else {
            log.warn("Spring MVC client exception on {}: {} ({})",
                    requestPath(request), status.value(),
                    exception.getClass().getSimpleName());
            message = clientMessage(exception.getMessage(), status);
        }

        return ResponseEntity.status(status)
                .headers(headers)
                .body(errorBody(status, message, request));
    }

    private String internalMessage() {
        Locale locale = LocaleContextHolder.getLocale();
        String localized = messageSource.getMessage(
                "error.internal", null, DEFAULT_INTERNAL_MESSAGE, locale);
        return localized == null || localized.isBlank()
                ? DEFAULT_INTERNAL_MESSAGE
                : localized;
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

    private static ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String message,
            WebRequest request) {
        return ResponseEntity.status(status)
                .body(errorBody(status, message, request));
    }
}
