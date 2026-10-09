package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation;
import com.taxonomy.analysis.service.AnalysisMemoryGuard;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Runs the existing scoring algorithm with only this task's admitted catalogue root. */
public final class FrozenClusterAnalysisComputation implements ClusterAnalysisComputation {
    private final ClusterAnalysisStore store;
    private final CatalogueSnapshotService snapshots;
    private final LlmService llm;
    private final LlmProviderConfig providers;
    private final Supplier<AnalysisMemoryGuard> guards;
    private final ObjectMapper mapper;
    private final LocalEmbeddingService embeddings;

    public FrozenClusterAnalysisComputation(ClusterAnalysisStore store, CatalogueSnapshotService snapshots,
            LlmService llm, LlmProviderConfig providers, Supplier<AnalysisMemoryGuard> guards, ObjectMapper mapper) {
        this(store, snapshots, llm, providers, guards, mapper, null);
    }

    public FrozenClusterAnalysisComputation(ClusterAnalysisStore store, CatalogueSnapshotService snapshots,
            LlmService llm, LlmProviderConfig providers, Supplier<AnalysisMemoryGuard> guards, ObjectMapper mapper,
            LocalEmbeddingService embeddings) {
        this.store = Objects.requireNonNull(store); this.snapshots = Objects.requireNonNull(snapshots);
        this.llm = Objects.requireNonNull(llm); this.providers = Objects.requireNonNull(providers);
        this.guards = Objects.requireNonNull(guards); this.mapper = Objects.requireNonNull(mapper);
        this.embeddings = embeddings;
    }

    @Override
    public AnalysisResult score(ClusterAnalysisStore.Input input, SubtaxonomyAnalysisTask task,
                                BooleanSupplier cancelled) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Root scoring must execute outside a database transaction");
        }
        Objects.requireNonNull(input); Objects.requireNonNull(task); Objects.requireNonNull(cancelled);
        var context = input.context();
        var command = input.command();
        var envelope = task.envelope();
        if (!input.executable() || !context.operationId().equals(envelope.operationId())
                || !context.authority().equals(envelope.authority())
                || !context.requirement().equals(envelope.requirement())
                || !context.requirement().matches(command.businessText())
                || !command.analysisScope().selects(task.root().code())) {
            throw new IllegalArgumentException("Root task does not match the admitted operation");
        }
        DurableClusterAnalysisExecution.requireSupportedProvider(command.provider());
        boolean localOnnx = "LOCAL_ONNX".equalsIgnoreCase(command.provider());
        if (localOnnx && embeddings == null) {
            throw new IllegalArgumentException("LOCAL_ONNX requires frozen embedding worker validation");
        }
        var frozen = mapper.readValue(store.shard(context, task.root()), RootCatalogueSnapshot.class);
        var root = task.root().code();
        try (var catalogue = snapshots.bind(DurableClusterAnalysisExecution.source(context), Set.of(root), List.of(frozen));
             var provider = providers.withRequestProvider(command.provider(), command.providerBinding());
             var run = AnalysisRunControl.worker(context.operationId(), cancelled, guards.get());
             var operation = InProcessAnalysisOperation.open(context, null)) {
            AnalysisRunControl.checkpoint();
            if (localOnnx) {
                embeddings.validateFrozenModel();
                AnalysisRunControl.checkpoint();
            }
            var result = llm.analyzeWithBudget(command.businessText(),
                    new AnalysisScope(Set.of(root), AnalysisMode.TAXONOMIES_ONLY));
            AnalysisRunControl.checkpoint();
            return result;
        }
    }
}
