package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.architecture.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArtemisClusterExecutionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ArtemisAnalysisTransportConfiguration.class, ArtemisClusterExecutionConfiguration.class)
            .withInitializer(context -> {
                // Composition dependencies are already initialized: do not inject production
                // field annotations inherited by Mockito subclasses into these test doubles.
                for (Class<?> type : new Class<?>[]{EntityManager.class, PlatformTransactionManager.class,
                        AnalysisDispatchStore.class, AnalysisTaskCompletionStore.class,
                        CatalogueSnapshotService.class, TaxonomyService.class, LlmService.class,
                        LlmProviderConfig.class, RequirementRelationSearchService.class,
                        RequirementArchitectureViewService.class, ArchitectureReportMetadataPort.class,
                        WorkspaceViewContextReadPort.class, com.taxonomy.catalog.service.LocalEmbeddingService.class,
                        com.taxonomy.catalog.snapshot.FrozenCatalogueEmbeddingService.class}) {
                    context.getBeanFactory().registerSingleton(type.getSimpleName(), mock(type));
                }
                context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
            });

    @Test void localModeKeepsExistingAnalysisPath() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClusterAnalysisExecution.class);
            assertThat(context).doesNotHaveBean(AnalysisTaskHandlers.class);
        });
    }

    @Test void artemisBindsProductionRootRelationFinalizationAndObservationOnEveryRuntimeRole() {
        for (String role : new String[]{"all", "coordinator", "worker"}) {
            runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis", "taxonomy.analysis.runtime-role=" + role,
                    "taxonomy.analysis.artemis.broker-url=tcp://127.0.0.1:1", "taxonomy.analysis.artemis.require-tls=false",
                    "taxonomy.analysis.artemis.call-timeout-ms=1000", "taxonomy.analysis.worker.shards=CP,IP")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(ClusterAnalysisExecution.class).hasSingleBean(ClusterAnalysisObservation.class)
                                .hasSingleBean(FrozenClusterAnalysisComputation.class).hasSingleBean(FrozenClusterRelationComputation.class)
                                .hasSingleBean(FrozenClusterAnalysisFinalizer.class).hasSingleBean(ArtemisClusterCoordinator.class);
                        var handlers = context.getBean(AnalysisTaskHandlers.class);
                        assertThat(handlers.handles(AnalysisTaskType.SUBTAXONOMY_ANALYSIS)).isTrue();
                        assertThat(handlers.handles(AnalysisTaskType.RELATION_ANALYSIS)).isTrue();
                    });
        }
    }
}
