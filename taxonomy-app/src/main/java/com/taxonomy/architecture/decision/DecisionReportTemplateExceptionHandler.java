package com.taxonomy.architecture.decision;

import com.taxonomy.templates.DecisionReportAvailabilityContract;
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

    private static final Logger log = LoggerFactory.getLogger(
            DecisionReportTemplateExceptionHandler.class);

    @ExceptionHandler(DecisionReportTemplateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailableTemplate(
            HttpServletRequest request) {
        // Detailed template validation belongs to the authorized administration surface.
        // This boundary records only the route and returns the shared bounded contract.
        log.warn("Decision report template unavailable on {}", request.getRequestURI());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("code", DecisionReportAvailabilityContract.PROBLEM_CODE);
        body.put("message", DecisionReportAvailabilityContract.SAFE_UNAVAILABLE_SUMMARY);
        body.put("remediation", DecisionReportAvailabilityContract.REMEDIATION);
        body.put("capability", DecisionReportAvailabilityContract.CAPABILITY);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
