package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the mandatory decision-report template is present and structurally valid.
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
                    .withDetail("commit", file.commitId())
                    .withDetail("packageSha256", file.manifest().packageSha256())
                    .withDetail("updatedBy", file.manifest().updatedBy())
                    .build();
        } catch (Exception exception) {
            String message = exception.getMessage();
            return Health.down()
                    .withDetail("templateId", templateId)
                    .withDetail("error", message == null
                            ? "Required decision-report template is unavailable"
                            : message)
                    .build();
        }
    }
}
