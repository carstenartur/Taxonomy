package com.taxonomy.architecture.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preserves the explicit temporary-unavailability contract for DOCX decision
 * reports ahead of the global catch-all exception handler.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class DecisionReportAvailabilityExceptionHandler {

    static final String PROBLEM_CODE = "DECISION_REPORT_TEMPLATE_UNAVAILABLE";
    static final String SAFE_MESSAGE =
            "Decision report generation is temporarily unavailable because its "
                    + "template requires administrator attention.";

    private static final Logger log = LoggerFactory.getLogger(
            DecisionReportAvailabilityExceptionHandler.class);

    @ExceptionHandler(DecisionReportTemplateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailableTemplate(
            DecisionReportTemplateUnavailableException exception,
            WebRequest request) {
        String path = requestPath(request);
        log.warn("Decision report template unavailable on {} ({})",
                path, exception.getClass().getSimpleName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("code", PROBLEM_CODE);
        body.put("message", SAFE_MESSAGE);
        body.put("path", path);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static String requestPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=")
                ? description.substring("uri=".length())
                : description;
    }
}
