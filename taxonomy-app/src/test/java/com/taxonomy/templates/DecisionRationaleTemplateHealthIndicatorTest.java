package com.taxonomy.templates;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionRationaleTemplateHealthIndicatorTest {

    @Test
    void invalidTemplateDegradesOnlyTheReportCapabilityWithoutLeakingInternals()
            throws Exception {
        DocumentTemplateService templates = mock(DocumentTemplateService.class);
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenThrow(new IOException(
                        "word/document.xml failed at /srv/private/template.dotx"));

        Health health = new DecisionRationaleTemplateHealthIndicator(templates).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("availability", "DEGRADED")
                .containsEntry(
                        "affectedCapability",
                        DecisionRationaleTemplateHealthIndicator.AFFECTED_CAPABILITY)
                .containsEntry(
                        "problemCode",
                        DecisionRationaleTemplateHealthIndicator.PROBLEM_CODE)
                .containsEntry("templateId", DecisionRationaleTemplateContract.TEMPLATE_ID)
                .containsEntry(
                        "error",
                        DecisionRationaleTemplateHealthIndicator.SAFE_ERROR)
                .containsEntry(
                        "remediation",
                        DecisionRationaleTemplateHealthIndicator.REMEDIATION);
        assertThat(health.getDetails().toString())
                .doesNotContain("word/document.xml")
                .doesNotContain("/srv/private")
                .doesNotContain("template.dotx");
    }
}
