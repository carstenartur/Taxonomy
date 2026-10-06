package com.taxonomy.shared.config;

import com.taxonomy.analysis.session.AnalysisDraftConflictException;
import com.taxonomy.analysis.session.AnalysisDraftValidationException;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

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

    /** Distinguish an unavailable search from a completed query with no matches. */
    @ExceptionHandler(com.taxonomy.search.SearchUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleSearchUnavailable(
            com.taxonomy.search.SearchUnavailableException exception, WebRequest request) {
        String message = messageSource.getMessage("error.search.unavailable", null,
                "Search is temporarily unavailable. Please try again.", LocaleContextHolder.getLocale());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, message, request);
    }

    @ExceptionHandler(com.taxonomy.architecture.report.WordReportLayoutException.class)
    public ResponseEntity<Map<String,Object>> handleWordLayoutConflict(
            com.taxonomy.architecture.report.WordReportLayoutException exception,WebRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT,exception.getMessage(),request);
    }

    /**
     * Handles IllegalArgumentException (bad input from client).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            WebRequest request) {
        log.warn("Bad request: status=400, type=BAD_REQUEST");
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientErrorMessage(exception, HttpStatus.BAD_REQUEST),
                request);
    }

    /** Return malformed or oversized working drafts as a stable client error. */
    @ExceptionHandler(AnalysisDraftValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftValidation(
            AnalysisDraftValidationException exception,
            WebRequest request) {
        log.warn("Invalid analysis draft: status=400, type=ANALYSIS_DRAFT_VALIDATION");
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientErrorMessage(exception, HttpStatus.BAD_REQUEST),
                request);
    }

    /**
     * Preserve the optimistic-concurrency contract expected by browser tabs.
     * The generic catch-all must never turn a stale draft revision into HTTP 500.
     */
    @ExceptionHandler(AnalysisDraftConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisDraftConflict(
            AnalysisDraftConflictException exception,
            WebRequest request) {
        log.warn("Analysis draft conflict: status=409, type=ANALYSIS_DRAFT_CONFLICT");
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                clientErrorMessage(exception, HttpStatus.CONFLICT),
                request);
    }

    /**
     * Handles authorization failures raised after the security filter chain,
     * for example while validating an explicit browser-tab workspace pin.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException exception,
            WebRequest request) {
        log.warn("Access denied: status=403, type=ACCESS_DENIED");
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(
                "error.forbidden", null, "Access denied.", locale);
        return buildErrorResponse(HttpStatus.FORBIDDEN, message, request);
    }

    /**
     * Catch-all handler for any unhandled exception.
     * Logs real failures server-side but does not write to a disconnected or committed response.
     */
    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Map<String, Object>> handleGenericException(
            Exception exception,
            WebRequest request) {
        if (logClientDisconnect(exception)) {
            return null;
        }
        log.error("Unhandled exception: status=500, type=INTERNAL_ERROR");
        if (responseCommitted(request)) {
            return null;
        }
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                internalErrorMessage(),
                request);
    }

    /**
     * Retain the 405 contract without Spring's warning containing the request method.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        return handleExceptionInternal(exception, null, headers, statusCode, request);
    }

    /**
     * Override the Spring MVC base handler to return our consistent JSON format
     * for framework-level exceptions (missing params, type mismatches, etc.).
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        // JSON converters can wrap a servlet disconnect several causes deep. Do not
        // report a serialization bug or attempt a second write to the closed connection.
        if (logClientDisconnect(exception)) {
            return null;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String message;
        if (status.is5xxServerError()) {
            log.error("Spring MVC exception: status={}, type=MVC_SERVER_ERROR", status.value());
            message = internalErrorMessage();
        } else {
            log.warn("Spring MVC exception: status={}, type=MVC_CLIENT_ERROR", status.value());
            message = clientErrorMessage(exception, status);
        }

        // Spring's committed-response warning formats the exception itself.
        // Log only the metadata above and avoid its secondary private diagnostic.
        if (responseCommitted(request)) {
            return null;
        }

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("timestamp", Instant.now().toString());
        errorBody.put("status", status.value());
        errorBody.put("error", status.getReasonPhrase());
        errorBody.put("message", message);
        errorBody.put("path", request.getDescription(false).replace("uri=", ""));
        // Retain Spring's uncommitted-response handling and servlet error attributes.
        return super.handleExceptionInternal(exception, errorBody, headers, status, request);
    }

    private static boolean logClientDisconnect(Exception exception) {
        if (!DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            return false;
        }
        // Spring's logging helper includes exception payloads at both DEBUG and TRACE.
        // Retain its classification and level choice without logging private causes.
        if (log.isTraceEnabled()) {
            log.trace("Client disconnected: type=CLIENT_DISCONNECTED");
        } else {
            log.debug("Client disconnected: type=CLIENT_DISCONNECTED");
        }
        return true;
    }

    private static boolean responseCommitted(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            HttpServletResponse response = servletRequest.getResponse();
            return response != null && response.isCommitted();
        }
        return false;
    }

    private String internalErrorMessage() {
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(
                "error.internal", null, DEFAULT_INTERNAL_MESSAGE, locale);
        return message == null || message.isBlank()
                ? DEFAULT_INTERNAL_MESSAGE
                : message;
    }

    private static String clientErrorMessage(Exception exception, HttpStatus status) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? status.getReasonPhrase()
                : message;
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String message,
            WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getDescription(false).replace("uri=", ""));
        return ResponseEntity.status(status).body(body);
    }
}
