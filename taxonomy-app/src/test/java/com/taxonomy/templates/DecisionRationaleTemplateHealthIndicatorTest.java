package com.taxonomy.templates;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionRationaleTemplateHealthIndicatorTest {

    @Test
    void invalidTemplateDegradesOnlyTheReportCapability() {
        DocumentTemplateService templates = mock(DocumentTemplateService.class);
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenThrow(new IllegalStateException("word/document.xml is invalid"));

        Health health = new DecisionRationaleTemplateHealthIndicator(templates).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("availability", "DEGRADED")
                .containsEntry("affectedCapability", "decision-report-docx")
                .containsEntry("templateId", DecisionRationaleTemplateContract.TEMPLATE_ID);
        assertThat(health.getDetails().get("remediation"))
                .asString()
                .contains("valid decision-report template");
    }
}
