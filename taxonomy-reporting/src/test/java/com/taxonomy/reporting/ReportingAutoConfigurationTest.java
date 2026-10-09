package com.taxonomy.reporting;

import com.taxonomy.reporting.config.ReportingAutoConfiguration;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.reporting.templates.DecisionRationaleTemplateHealthIndicator;
import com.taxonomy.templates.api.DocumentTemplates;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportingAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReportingAutoConfiguration.class));

    @Test
    void aDatabaseOnlyContextDoesNotStartAnUnassembledReportingFeature() {
        context.run(application -> assertThat(application).hasNotFailed()
                .doesNotHaveBean(DecisionRationaleTemplateHealthIndicator.class)
                .doesNotHaveBean(ReportRendererRegistry.class));
    }

    @Test
    void assembledReportingRetainsItsRendererAndHealthAdapters() {
        context.withBean(DocumentTemplates.class, () -> mock(DocumentTemplates.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(application -> assertThat(application).hasNotFailed()
                        .hasSingleBean(DecisionRationaleTemplateHealthIndicator.class)
                        .hasSingleBean(ReportRendererRegistry.class));
    }
}
