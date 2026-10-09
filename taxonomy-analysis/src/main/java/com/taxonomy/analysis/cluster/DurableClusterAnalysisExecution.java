package com.taxonomy.analysis.cluster;

import com.taxonomy.extension.api.llm.ProviderId;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.UnknownAnalysisProviderException;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueEmbeddingService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Captures immutable input, admits once, and reconciles durable state only on live events/reconnect. */
public final class DurableClusterAnalysisExecution implements ClusterAnalysisExecution {
    private final ClusterAnalysisStore store;
    private final CatalogueSnapshotService catalogue;
    private final ClusterAnalysisSignals signals;
    private final LlmProviderConfig providers;
    private final ObjectMapper mapper;
    private final WorkspaceViewContextReadPort workspaceViews;
    private final FrozenCatalogueEmbeddingService embeddingSnapshots;

    public DurableClusterAnalysisExecution(ClusterAnalysisStore store, CatalogueSnapshotService catalogue,
            ClusterAnalysisSignals signals, LlmProviderConfig providers, ObjectMapper mapper,
            WorkspaceViewContextReadPort workspaceViews) {
        this(store, catalogue, signals, providers, mapper, workspaceViews, null);
    }

    public DurableClusterAnalysisExecution(ClusterAnalysisStore store, CatalogueSnapshotService catalogue,
            ClusterAnalysisSignals signals, LlmProviderConfig providers, ObjectMapper mapper,
            WorkspaceViewContextReadPort workspaceViews, FrozenCatalogueEmbeddingService embeddingSnapshots) {
        this.store = Objects.requireNonNull(store); this.catalogue = Objects.requireNonNull(catalogue);
        this.signals = Objects.requireNonNull(signals); this.providers = Objects.requireNonNull(providers);
        this.mapper = Objects.requireNonNull(mapper);
        this.workspaceViews = Objects.requireNonNull(workspaceViews);
        this.embeddingSnapshots = embeddingSnapshots;
    }

    @Override
    public AnalysisResult execute(AnalysisOperationContext context, AnalyzeRequirementCommand command, ViewContext view,
                                  Consumer<ClusterAnalysisStore.Snapshot> progress) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cluster analysis must execute outside a database transaction");
        }
        Objects.requireNonNull(progress, "progress");
        requireInput(context, command, view);
        String provider = frozenProvider(command.provider());
        boolean localOnnx = "LOCAL_ONNX".equals(provider);
        if (localOnnx && embeddingSnapshots == null) {
            throw new IllegalArgumentException("LOCAL_ONNX requires frozen embedding admission support");
        }
        var admitted = new AnalyzeRequirementCommand(command.businessText(), command.includeArchitectureView(),
                command.maxArchitectureNodes(), provider, command.username(), command.workspaceContext(),
                command.provenance(), command.analysisScope());
        var available = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).collect(Collectors.toSet());
        command.analysisScope().validateRoots(available);
        Map<TaxonomyShardRoot, String> scoring = new LinkedHashMap<>();
        Map<TaxonomyShardRoot, String> targets = new LinkedHashMap<>();
        var source = source(context);
        boolean allTargets = command.analysisScope().includesRelations() || command.includeArchitectureView();
        var required = available.stream().filter(root -> allTargets || command.analysisScope().selects(root))
                .collect(Collectors.toSet());
        // All roots belong to one official catalogue generation, independently of the operation's Git source.
        // ONNX text can name relation endpoints in other roots. Capture them atomically, then retain only required roots.
        var frozenRoots = catalogue.captureRoots(source, localOnnx ? available : required);
        if (localOnnx) {
            var repository = source.workspaceId() == null
                    ? RepositoryContext.centralRead(source.repositoryId(), source.branch(), command.username())
                    : RepositoryContext.workspace(source.repositoryId(), source.workspaceId(), source.branch(), command.username());
            frozenRoots = embeddingSnapshots.decorate(repository, source, frozenRoots, required);
        }
        var captured = frozenRoots.stream().collect(Collectors.toMap(
                RootCatalogueSnapshot::rootCode, java.util.function.Function.identity()));
        if (!captured.keySet().equals(required) || captured.values().stream().anyMatch(root -> !source.equals(root.source()))) {
            throw new IllegalStateException("Captured catalogue does not match the admitted source and roots");
        }
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            if (!required.contains(root.code())) continue;
            String frozen = mapper.writeValueAsString(captured.get(root.code()));
            if (command.analysisScope().selects(root.code())) scoring.put(root, frozen);
            if (allTargets) targets.put(root, frozen);
        }
        var wake = new Semaphore(0);
        // Register before admitting: even immediate completion cannot fall into a listener gap.
        try (var subscription = signals.listen(context, ignored -> wake.release())) {
            subscription.onReconnect(wake::release);
            requireCurrentSource(context, command);
            store.admit(context, admitted, view, scoring, targets);
            while (true) {
                // Draining before the read coalesces events without losing a post-read notification.
                wake.drainPermits();
                var snapshot = store.snapshot(context);
                progress.accept(snapshot);
                if (snapshot.state().terminal()) {
                    return Objects.requireNonNull(snapshot.result(), "Terminal operation has no durable result");
                }
                try {
                    wake.acquire();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ClusterAnalysisObservationDetachedException();
                }
            }
        }
    }

    private void requireCurrentSource(AnalysisOperationContext context, AnalyzeRequirementCommand command) {
        var workspace = command.workspaceContext();
        // Recheck the request's authorized branch, not another tab's active workspace.
        String branch = workspace.currentBranch();
        ViewContext current = workspaceViews.getViewContext(workspace.username(), branch, workspace);
        if (!Objects.equals(context.authority().branch(), branch)
                || !Objects.equals(context.authority().sourceCommit(), current == null ? null : current.basedOnCommit())
                || (current != null && !Objects.equals(context.authority().branch(), current.basedOnBranch()))) {
            throw new IllegalStateException("Analysis source changed before durable admission");
        }
    }

    private String frozenProvider(String requested) {
        String explicit = requested == null || requested.isBlank() ? null : requested.toUpperCase(Locale.ROOT);
        if (explicit != null && !"MOCK".equals(explicit)) {
            try { providers.requireRegisteredProvider(explicit); }
            catch (IllegalArgumentException invalid) { throw new UnknownAnalysisProviderException(requested); }
            requireSupportedProvider(explicit);
        }
        String effective = "MOCK".equals(explicit) || providers.isMockMode() ? "MOCK"
                : explicit == null ? providers.getActiveProviderId().value() : explicit;
        requireSupportedProvider(effective);
        return effective;
    }

    static void requireSupportedProvider(String provider) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("Frozen provider is required");
        if (!"MOCK".equalsIgnoreCase(provider)) new ProviderId(provider);
    }

    static CatalogueSourceIdentity source(AnalysisOperationContext context) {
        var source = context.authority();
        return new CatalogueSourceIdentity(source.repositoryId(), source.workspaceId(), source.branch(), source.sourceCommit());
    }

    private static void requireInput(AnalysisOperationContext context, AnalyzeRequirementCommand command, ViewContext view) {
        Objects.requireNonNull(context); Objects.requireNonNull(command);
        var workspace = Objects.requireNonNull(command.workspaceContext());
        var authority = context.authority();
        if (!context.requirement().matches(command.businessText())
                || !Objects.equals(command.username(), workspace.username())
                || !authority.repositoryId().equals(workspace.repositoryId())
                || !Objects.equals(authority.workspaceId(), workspace.workspaceId())
                || !Objects.equals(authority.branch(), workspace.currentBranch())
                || (view != null && (!Objects.equals(authority.sourceCommit(), view.basedOnCommit())
                || (view.basedOnBranch() != null && !Objects.equals(authority.branch(), view.basedOnBranch()))))) {
            throw new IllegalArgumentException("Analysis input does not match exact source authority");
        }
    }
}
