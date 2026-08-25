package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the mandatory decision-report template is present and
 * structurally valid without making unrelated application capabilities
 * unavailable or exposing validation internals through aggregate health data.
 */
@Component
public final class DecisionRationaleTemplateHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            DecisionRationaleTemplateHealthIndicator.class);

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
                    .withDetail("availability", "AVAILABLE")
                    .withDetail(
                            "affectedCapability",
                            DecisionReportAvailabilityContract.CAPABILITY)
                    .withDetail("templateId", templateId)
                    .withDetail("commit", file.commitId())
                    .withDetail("packageSha256", file.manifest().packageSha256())
                    .withDetail("updatedBy", file.manifest().updatedBy())
                    .build();
        } catch (Exception exception) {
            log.warn("Decision-report template capability is degraded ({})",
                    exception.getClass().getSimpleName());
            return Health.up()
                    .withDetail("availability", "DEGRADED")
                    .withDetail(
                            "affectedCapability",
                            DecisionReportAvailabilityContract.CAPABILITY)
                    .withDetail(
                            "problemCode",
                            DecisionReportAvailabilityContract.PROBLEM_CODE)
                    .withDetail("templateId", templateId)
                    .withDetail(
                            "error",
                            DecisionReportAvailabilityContract.HEALTH_ERROR)
                    .withDetail(
                            "remediation",
                            DecisionReportAvailabilityContract.REMEDIATION)
                    .build();
        }
    }
}
