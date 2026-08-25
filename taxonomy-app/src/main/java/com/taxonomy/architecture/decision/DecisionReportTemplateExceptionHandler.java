package com.taxonomy.architecture.decision;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable REST contract for a missing or invalid mandatory decision-report template. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class DecisionReportTemplateExceptionHandler {

    public static final String PROBLEM_CODE =
            "DECISION_REPORT_TEMPLATE_UNAVAILABLE";
    public static final String CLIENT_MESSAGE =
            "Decision report generation is temporarily unavailable because the "
                    + "required template is missing or invalid. An administrator must "
                    + "restore a valid decision-report template.";

    private static final Logger log = LoggerFactory.getLogger(
            DecisionReportTemplateExceptionHandler.class);

    @ExceptionHandler(DecisionReportTemplateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailableTemplate(
            DecisionReportTemplateUnavailableException exception,
            HttpServletRequest request) {
        log.warn("Decision report template unavailable on {}",
                request.getRequestURI(), exception);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("code", PROBLEM_CODE);
        body.put("message", CLIENT_MESSAGE);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
