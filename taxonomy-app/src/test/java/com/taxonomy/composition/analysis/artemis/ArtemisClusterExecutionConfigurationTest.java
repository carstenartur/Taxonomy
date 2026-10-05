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
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.postoffice.QueueBinding;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class ArtemisClusterExecutionConfigurationTest {
    @TempDir Path brokerData;

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

    @ParameterizedTest
    @ValueSource(strings = {"all", "coordinator", "worker"})
    @Timeout(30)
    void lazyApplicationStartsItsRoleSubscriptionsWithoutAnyBeanLookup(String role) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var configuration = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJournalType(JournalType.NIO)
                .setBindingsDirectory(brokerData.resolve("bindings").toString())
                .setJournalDirectory(brokerData.resolve("journal").toString())
                .setPagingDirectory(brokerData.resolve("paging").toString())
                .setLargeMessagesDirectory(brokerData.resolve("large").toString())
                .setThreadPoolMaxSize(2).setScheduledThreadPoolMaxSize(1)
                .addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        var broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        try {
            broker.start();
            runner.withInitializer(context -> context.addBeanFactoryPostProcessor(
                            new LazyInitializationBeanFactoryPostProcessor()))
                    .withPropertyValues("taxonomy.analysis.transport.mode=artemis",
                            "taxonomy.analysis.runtime-role=" + role,
                            "taxonomy.analysis.artemis.broker-url=tcp://127.0.0.1:" + port,
                            "taxonomy.analysis.artemis.require-tls=false",
                            "taxonomy.analysis.artemis.call-timeout-ms=1000",
                            "taxonomy.analysis.worker.shards=CP,IP")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // Inspect the real broker, without getBean() accidentally starting lazy lifecycles.
                        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                            assertThat(consumers(broker, "taxonomy.analysis.subtaxonomy.CP"))
                                    .isEqualTo(role.equals("coordinator") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.relation.IP"))
                                    .isEqualTo(role.equals("coordinator") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.relation.general"))
                                    .isEqualTo(role.equals("worker") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.completion"))
                                    .isEqualTo(role.equals("worker") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.dlq"))
                                    .isEqualTo(role.equals("worker") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.expiry"))
                                    .isEqualTo(role.equals("worker") ? 0 : 1);
                            assertThat(consumers(broker, "taxonomy.analysis.progress")).isEqualTo(1);
                            assertThat(consumers(broker, "taxonomy.analysis.control")).isEqualTo(1);
                        });
                    });
        } finally {
            broker.stop();
        }
    }

    private static int consumers(EmbeddedActiveMQ broker, String address) throws Exception {
        return broker.getActiveMQServer().getPostOffice().getBindingsForAddress(SimpleString.of(address))
                .getBindings().stream().filter(QueueBinding.class::isInstance).map(QueueBinding.class::cast)
                .mapToInt(binding -> binding.getQueue().getConsumerCount()).sum();
    }
}
