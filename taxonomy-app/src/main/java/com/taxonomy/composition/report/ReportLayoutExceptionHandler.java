package com.taxonomy.composition.report;

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
@ConditionalOnFeature("reporting")
public final class ReportLayoutExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReportLayoutExceptionHandler.class);
    @ExceptionHandler(com.taxonomy.reporting.render.document.WordReportLayoutException.class)
    public ResponseEntity<Map<String,Object>> handleWordLayoutConflict(
            com.taxonomy.reporting.render.document.WordReportLayoutException exception,WebRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT,exception.getMessage(),request);
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
