package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the mandatory decision-report template is present and structurally valid.
 *
 * <p>A missing or invalid template degrades only the DOCX decision-report feature. It must
 * not make the complete application unavailable or prevent an administrator from repairing
 * the template through the normal administration surface.</p>
 */
@Component
public final class DecisionRationaleTemplateHealthIndicator implements HealthIndicator {

    private static final String DEGRADED = "DEGRADED";

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
                    .withDetail("availability", "AVAILABLE")
                    .withDetail("commit", file.commitId())
                    .withDetail("packageSha256", file.manifest().packageSha256())
                    .withDetail("updatedBy", file.manifest().updatedBy())
                    .build();
        } catch (Exception exception) {
            String message = exception.getMessage();
            return Health.up()
                    .withDetail("templateId", templateId)
                    .withDetail("availability", DEGRADED)
                    .withDetail("affectedCapability", "decision-report-docx")
                    .withDetail("remediation", "Restore or upload a valid decision-report template")
                    .withDetail("error", message == null
                            ? "Required decision-report template is unavailable"
                            : message)
                    .build();
        }
    }
}
