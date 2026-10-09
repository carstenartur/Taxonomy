package com.taxonomy.composition.plugins;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class FeatureAssemblyPartialContextTest {
    @Test void unrelatedPartialContextsDoNotStartFeatureServicesOrPersistence() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                com.taxonomy.templates.config.TemplatesFeatureAutoConfiguration.class,
                com.taxonomy.architecture.config.ArchitectureFeatureAutoConfiguration.class,
                com.taxonomy.analysis.config.AnalysisFeatureAutoConfiguration.class,
                com.taxonomy.portfolio.config.PortfolioFeatureAutoConfiguration.class,
                com.taxonomy.interop.config.InteropFeatureAutoConfiguration.class,
                com.taxonomy.reporting.config.ReportingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("documentTemplateService")
                            .doesNotHaveBean("llmService").doesNotHaveBean("integrationService");
                });
    }
}
