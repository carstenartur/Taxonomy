package com.taxonomy.architecture.decision;

import com.taxonomy.templates.DecisionReportAvailabilityContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionReportAvailabilityExceptionHandlerTest {

    @Test
    void returnsTypedServiceUnavailableWithoutTemplateInternals() {
        DecisionReportAvailabilityExceptionHandler handler =
                new DecisionReportAvailabilityExceptionHandler();
        DecisionReportTemplateUnavailableException exception =
                new DecisionReportTemplateUnavailableException(
                        "word/document.xml failed at /srv/private/template.dotx",
                        new IllegalArgumentException("private template payload"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleUnavailableTemplate(
                        exception,
                        new ServletWebRequest(
                                new MockHttpServletRequest(
                                        "GET", "/api/decision-report/example.docx")));

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", 503)
                .containsEntry(
                        "code",
                        DecisionReportAvailabilityContract.PROBLEM_CODE)
                .containsEntry(
                        "message",
                        DecisionReportAvailabilityContract.RESPONSE_MESSAGE)
                .containsEntry(
                        "remediation",
                        DecisionReportAvailabilityContract.REMEDIATION)
                .containsEntry("path", "/api/decision-report/example.docx");
        assertThat(response.getBody().toString())
                .doesNotContain("word/document.xml")
                .doesNotContain("/srv/private")
                .doesNotContain("private template payload");
    }
}
