package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the mandatory decision-report template is present and structurally valid.
 *
 * <p>A missing or invalid template degrades only the DOCX decision-report capability. It must
 * not make the complete application unavailable or expose package, filesystem or validation
 * details through an operational health endpoint.</p>
 */
@Component
public final class DecisionRationaleTemplateHealthIndicator implements HealthIndicator {

    private final DocumentTemplateService templates;

    public DecisionRationaleTemplateHealthIndicator(DocumentTemplateService templates) {
        this.templates = templates;
    }

    @Override
    public Health health() {
        String templateId = DecisionRationaleTemplateContract.TEMPLATE_ID;
        try {
            TemplateFile file = templates.downloadCurrentValidated(templateId);
            return Health.up()
                    .withDetail("templateId", templateId)
                    .withDetail("availability", DecisionReportAvailabilityContract.AVAILABLE)
                    .withDetail("commit", file.commitId())
                    .withDetail("packageSha256", file.manifest().packageSha256())
                    .withDetail("updatedBy", file.manifest().updatedBy())
                    .build();
        } catch (Exception ignored) {
            return Health.up()
                    .withDetail("templateId", templateId)
                    .withDetail("availability", DecisionReportAvailabilityContract.DEGRADED)
                    .withDetail("affectedCapability",
                            DecisionReportAvailabilityContract.CAPABILITY)
                    .withDetail("problemCode",
                            DecisionReportAvailabilityContract.PROBLEM_CODE)
                    .withDetail("summary",
                            DecisionReportAvailabilityContract.SAFE_UNAVAILABLE_SUMMARY)
                    .withDetail("remediation",
                            DecisionReportAvailabilityContract.REMEDIATION)
                    .build();
        }
    }
}
