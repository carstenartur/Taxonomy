package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.session.*;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.context.request.WebRequest;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;

/** Feature-owned errors preserve the existing wire response before the core catch-all. */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnFeature("analysis")
public final class AnalysisDraftExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalysisDraftExceptionHandler.class);
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
