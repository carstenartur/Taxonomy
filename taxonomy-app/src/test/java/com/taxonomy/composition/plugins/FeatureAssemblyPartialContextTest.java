package com.taxonomy.composition.plugins;

import com.taxonomy.composition.architecture.ArchitectureSummaryApiController;
import com.taxonomy.shared.features.FeatureAssembly;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class FeatureAssemblyPartialContextTest {
    @Test void absentArchitectureDoesNotRegisterSummaryHttpBridge() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().registerSingleton(
                        "featureSet", new FeatureAssembly.FeatureSet(Set.of())))
                .withUserConfiguration(ArchitectureSummaryApiController.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ArchitectureSummaryApiController.class));
    }

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
