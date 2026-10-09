package com.taxonomy.reporting.templates;

import com.taxonomy.templates.OoxmlTemplatePackageCodec;
import com.taxonomy.templates.api.DocumentTemplates;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionRationaleTemplateHealthIndicatorTest {

    @Test
    void invalidTemplateDegradesOnlyTheReportCapabilityWithoutDisclosingDetails()
            throws IOException {
        DocumentTemplates templates = mock(DocumentTemplates.class);
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenThrow(new IOException(
                        "private-template-path contained "
                                + "jdbc:postgresql://internal-user:secret@database/taxonomy"));

        Health health = new DecisionRationaleTemplateHealthIndicator(templates).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("availability", DecisionReportAvailabilityContract.DEGRADED)
                .containsEntry("affectedCapability",
                        DecisionReportAvailabilityContract.CAPABILITY)
                .containsEntry("problemCode",
                        DecisionReportAvailabilityContract.PROBLEM_CODE)
                .containsEntry("templateId", DecisionRationaleTemplateContract.TEMPLATE_ID)
                .containsEntry("summary",
                        DecisionReportAvailabilityContract.SAFE_UNAVAILABLE_SUMMARY)
                .containsEntry("remediation",
                        DecisionReportAvailabilityContract.REMEDIATION);
        assertThat(health.getDetails().toString())
                .doesNotContain("private-template-path")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("internal-user")
                .doesNotContain("secret");
    }
}
