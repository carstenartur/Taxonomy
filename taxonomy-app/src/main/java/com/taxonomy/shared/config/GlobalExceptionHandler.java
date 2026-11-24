package com.taxonomy.shared.config;

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
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
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
    @ExceptionHandler(com.taxonomy.error.SearchUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleSearchUnavailable(
            com.taxonomy.error.SearchUnavailableException exception, WebRequest request) {
        String message = messageSource.getMessage("error.search.unavailable", null,
                "Search is temporarily unavailable. Please try again.", LocaleContextHolder.getLocale());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, message, request);
    }


    /** A drained or replaced contribution cannot be silently retargeted. */
    @ExceptionHandler(com.taxonomy.extension.api.plugin.ExtensionUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleExtensionUnavailable(
            com.taxonomy.extension.api.plugin.ExtensionUnavailableException failure, WebRequest request) {
        String message = messageSource.getMessage("error.extension.unavailable", null,
                "The selected format is no longer available. Refresh the selection and retry.", LocaleContextHolder.getLocale());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, message, request);
    }

    /**
     * Handles IllegalArgumentException (bad input from client).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            WebRequest request) {
        logFailure(exception, HttpStatus.BAD_REQUEST, "BAD_REQUEST", request);
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                clientErrorMessage(exception, HttpStatus.BAD_REQUEST),
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
        logFailure(exception, HttpStatus.FORBIDDEN, "ACCESS_DENIED", request);
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
        Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(exception);
        if (cause instanceof AccessDeniedException denied && !responseCommitted(request)) {
            return handleAccessDenied(denied, request);
        }
        logFailure(exception, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request);
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
            logFailure(exception, status, "MVC_SERVER_ERROR", request);
            message = internalErrorMessage();
        } else {
            logFailure(exception, status, "MVC_CLIENT_ERROR", request);
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
        String code = safeErrorCode(exception, status);
        if (!"NONE".equals(code)) {
            errorBody.put("code", code);
        }
        String workspaceStatus = safeWorkspaceStatus(exception, code);
        if (!"NONE".equals(workspaceStatus)) {
            errorBody.put("workspaceStatus", workspaceStatus);
        }
        // Retain Spring's uncommitted-response handling and servlet error attributes.
        return super.handleExceptionInternal(exception, errorBody, headers, status, request);
    }

    /** Log application identities only: even route patterns can contain request-derived values. */
    private static void logFailure(Exception exception, HttpStatus status, String type, WebRequest request) {
        String code = safeErrorCode(exception, status);
        boolean readinessConflict = status == HttpStatus.CONFLICT
                && ("WORKSPACE_NOT_READY".equals(code) || "INITIAL_SOURCE_PENDING".equals(code));
        var level = status.is5xxServerError() ? org.slf4j.event.Level.ERROR
                : readinessConflict ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.WARN;
        var handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        String handlerName = handler instanceof HandlerMethod method
                ? method.getBeanType().getSimpleName() + "." + method.getMethod().getName() : "UNMAPPED";
        String methodName = "UNKNOWN";
        if (request instanceof ServletWebRequest servlet) {
            methodName = switch (servlet.getRequest().getMethod()) {
                case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT" ->
                    servlet.getRequest().getMethod();
                case null, default -> "OTHER";
            };
        }
        log.atLevel(level).log(
                "Request failure: status={}, type={}, method={}, handler={}, exception={}, code={}, workspaceStatus={}",
                status.value(), readinessConflict ? "MVC_STATE_CONFLICT" : type, methodName, handlerName,
                exception.getClass().getSimpleName(), code, safeWorkspaceStatus(exception, code));
    }

    private static @Nullable Object errorProperty(Exception exception, String name) {
        if (exception instanceof org.springframework.web.ErrorResponse response
                && response.getBody().getProperties() != null) {
            return response.getBody().getProperties().get(name);
        }
        return null;
    }

    private static String safeErrorCode(Exception exception, HttpStatus status) {
        Object code = errorProperty(exception, "code");
        if (status == HttpStatus.GONE && "ANALYSIS_EXPIRED".equals(code)) {
            return "ANALYSIS_EXPIRED";
        }
        if (status == HttpStatus.CONFLICT) {
            if ("WORKSPACE_NOT_READY".equals(code)) return "WORKSPACE_NOT_READY";
            if ("INITIAL_SOURCE_PENDING".equals(code)) return "INITIAL_SOURCE_PENDING";
        }
        return "NONE";
    }

    private static String safeWorkspaceStatus(Exception exception, String code) {
        if (!"WORKSPACE_NOT_READY".equals(code)) return "NONE";
        Object value = errorProperty(exception, "workspaceStatus");
        String state = value instanceof Enum<?> lifecycle ? lifecycle.name()
                : value instanceof String text ? text : "NONE";
        return switch (state) {
            case "NOT_PROVISIONED", "PROVISIONING", "FAILED" -> state;
            default -> "NONE";
        };
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
