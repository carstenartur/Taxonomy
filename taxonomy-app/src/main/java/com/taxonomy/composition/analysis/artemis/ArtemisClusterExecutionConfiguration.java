package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.FrozenCatalogueEmbeddingService;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.util.function.Supplier;

/** Complete production composition; local mode never creates distributed execution beans. */
@com.taxonomy.shared.features.ConditionalOnFeature({"analysis"})
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taxonomy.analysis.transport.mode", havingValue = "artemis")
public class ArtemisClusterExecutionConfiguration {
    @Bean ArtemisAnalysisMetrics artemisAnalysisMetrics(ObjectProvider<MeterRegistry> registries) {
        return new ArtemisAnalysisMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean ClusterAnalysisSignals clusterAnalysisSignals(ArtemisAnalysisMetrics metrics) {
        var signals = new ClusterAnalysisSignals();
        signals.cancellationLatency(metrics::cancellationObserved);
        return signals;
    }

    @Bean ClusterAnalysisStore clusterAnalysisStore(EntityManager em, PlatformTransactionManager transactions,
            ObjectMapper mapper, AnalysisDispatchService dispatch, ClusterAnalysisSignals signals,
            ArtemisAnalysisTaskPublisher publisher, ArtemisAnalysisMetrics metrics) {
        var store = new ClusterAnalysisStore(em, transactions, mapper, dispatch);
        store.eventPublisher(event -> {
            signals.progress(event); metrics.progress(event); publisher.progress(event);
        });
        return store;
    }

    @Bean Supplier<AnalysisMemoryGuard> clusterAnalysisGuards(Environment environment) {
        var policy = new AnalysisMemoryGuard.Policy(
                environment.getProperty("taxonomy.analysis.runtime.warning-percent", Integer.class, 80),
                environment.getProperty("taxonomy.analysis.runtime.stop-percent", Integer.class, 92),
                Math.multiplyExact(environment.getProperty("taxonomy.analysis.runtime.minimum-headroom-mb", Long.class, 16L), 1024L * 1024),
                Math.multiplyExact(environment.getProperty("taxonomy.analysis.runtime.pressure-seconds", Long.class, 5L), 1000L),
                Math.multiplyExact(environment.getProperty("taxonomy.analysis.runtime.maximum-duration-seconds", Long.class, 1800L), 1000L));
        return () -> new AnalysisMemoryGuard(policy, AnalysisMemoryGuard::heapSample, () -> System.nanoTime() / 1_000_000);
    }

    @Bean FrozenClusterAnalysisComputation frozenClusterAnalysisComputation(ClusterAnalysisStore store,
            CatalogueSnapshotService catalogue, LlmService llm, LlmProviderConfig providers,
            Supplier<AnalysisMemoryGuard> guards, ObjectMapper mapper, LocalEmbeddingService embeddings) {
        return new FrozenClusterAnalysisComputation(store, catalogue, llm, providers, guards, mapper, embeddings);
    }

    @Bean ClusterAnalysisService clusterAnalysisService(ClusterAnalysisStore store,
            FrozenClusterAnalysisComputation computation, ClusterAnalysisSignals signals) {
        return new ClusterAnalysisService(store, computation, signals);
    }

    @Bean FrozenClusterRelationComputation frozenClusterRelationComputation(ClusterAnalysisStore store,
            CatalogueSnapshotService catalogue, TaxonomyService taxonomy, RequirementRelationSearchService relations,
            LlmProviderConfig providers, ObjectMapper mapper, Supplier<AnalysisMemoryGuard> guards) {
        return new FrozenClusterRelationComputation(store, catalogue, taxonomy, relations, providers, mapper, guards);
    }

    @Bean ClusterRelationService clusterRelationService(ClusterAnalysisStore store,
            FrozenClusterRelationComputation computation, ClusterAnalysisSignals signals) {
        return new ClusterRelationService(store, computation, signals);
    }

    @Bean AnalysisTaskHandlers clusterAnalysisTaskHandlers(ClusterAnalysisService roots, ClusterRelationService relations,
            ArtemisAnalysisMetrics metrics) {
        return new AnalysisTaskHandlers(task -> metrics.prepare(task, () -> roots.prepare(task)),
                task -> metrics.prepare(task, () -> relations.prepare(task)));
    }

    @Bean DurableClusterAnalysisExecution durableClusterAnalysisExecution(ClusterAnalysisStore store,
            CatalogueSnapshotService catalogue, ClusterAnalysisSignals signals, LlmProviderConfig providers,
            ObjectMapper mapper, WorkspaceViewContextReadPort workspaceViews, FrozenCatalogueEmbeddingService embeddings) {
        return new DurableClusterAnalysisExecution(store, catalogue, signals, providers, mapper, workspaceViews, embeddings);
    }

    @Bean FrozenClusterAnalysisFinalizer frozenClusterAnalysisFinalizer(ClusterAnalysisStore store,
            CatalogueSnapshotService catalogue, RequirementArchitectureViewService architecture,
            ArchitectureReportMetadataPort metadata, ObjectMapper mapper) {
        return new FrozenClusterAnalysisFinalizer(store, catalogue, architecture, metadata, mapper);
    }

    @Bean(destroyMethod = "close") DurableClusterAnalysisObservation durableClusterAnalysisObservation(
            ClusterAnalysisStore store, ClusterAnalysisSignals signals, ArtemisAnalysisTaskPublisher publisher) {
        return new DurableClusterAnalysisObservation(store, signals, publisher);
    }

    @Lazy(false) // Completion and cancellation subscriptions cannot depend on a first HTTP request.
    @Bean(initMethod = "attach", destroyMethod = "close") ArtemisClusterCoordinator artemisClusterCoordinator(
            ArtemisAnalysisConnection connection, AnalysisDestinations destinations,
            com.taxonomy.analysis.dag.json.AnalysisMessageCodec codec, ClusterAnalysisStore store,
            AnalysisTaskCompletionStore ledger, ClusterAnalysisSignals signals, FrozenClusterAnalysisFinalizer finalizer,
            ArtemisAnalysisWorker worker, ArtemisAnalysisMetrics metrics,
            @Value("${taxonomy.analysis.runtime-role:all}") String role) {
        var coordinator = new ArtemisClusterCoordinator(connection, destinations, codec, store, ledger, signals,
                ArtemisAnalysisTransportConfiguration.RuntimeRole.parse(role) != ArtemisAnalysisTransportConfiguration.RuntimeRole.WORKER);
        coordinator.finalizer(finalizer); coordinator.metrics(metrics); metrics.bindTransport(worker, coordinator);
        return coordinator;
    }
}
