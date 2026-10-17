package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;

@Service
public class AnalysisContinuationService {
    private final AnalysisContinuationStore store;
    private final TaxonomyService catalogue;
    private final LlmService llm;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.taxonomy.analysis.relations.RequirementRelationSearchService relations;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LlmProviderConfig providers;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.taxonomy.analysis.cluster.ClusterAnalysisStore clusterStore;
    public AnalysisContinuationService(AnalysisContinuationStore store, TaxonomyService catalogue,
            LlmService llm, WorkspaceManager workspaces, ObjectMapper mapper) {
        this.store = store; this.catalogue = catalogue; this.llm = llm; this.workspaces = workspaces; this.mapper = mapper;
    }
    public Execution begin(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        AnalysisRequest frozen = mapper.readValue(mapper.writeValueAsString(request), AnalysisRequest.class);
        frozen.setMaxArchitectureNodes(maxNodes);
        var tree = catalogue.getFullTree();
        try {
            frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode)
                    .collect(java.util.stream.Collectors.toSet()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        return new Execution(store.begin(frozen, scope, signature(frozen, tree), tree));
    }
    public Execution beginDistributed(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        if (providers == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Frozen provider authority is unavailable");
        var frozen = mapper.readValue(mapper.writeValueAsString(request), AnalysisRequest.class);
        frozen.setMaxArchitectureNodes(maxNodes);
        String requested = request.getProvider();
        String provider = "MOCK".equalsIgnoreCase(requested) || providers.isMockMode() ? "MOCK"
                : requested == null || requested.isBlank() ? providers.getActiveProviderId().value() : requested.toUpperCase(java.util.Locale.ROOT);
        frozen.setProvider(provider);
        var tree = catalogue.getFullTree();
        try { frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode).collect(java.util.stream.Collectors.toSet())); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
        var pluginBinding = providers.captureProviderBinding(provider);
        try (var ignored = providers.withRequestProvider(provider, pluginBinding)) {
            String signature = signature(frozen, tree);
            var claim = store.begin(frozen, scope, signature, tree);
            var binding = claim.completedResult() == null ? new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                    llm.recoveryPolicyFingerprint(provider), AnalysisCheckpointSession.digest(relations == null ? "" : relations.recoveryPolicyFingerprint())) : null;
            return new Execution(claim, binding, provider, pluginBinding);
        }
    }
    public AnalysisContinuationStore.Snapshot read(String id, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.read(id, scope);
    }
    public AnalysisContinuationStore.Snapshot cancel(String id, String username, WorkspaceContext scope) {
        authorize(username, scope);
        var snapshot = store.read(id, scope);
        if (snapshot.result() == null || snapshot.result().getTree() == null) {
            var tree = catalogue.getFullTree();
            snapshot = store.cancel(id, scope, tree, signature(snapshot.request(), tree));
        } else snapshot = store.cancel(id, scope);
        var cancelled = snapshot;
        String operation = store.boundOperation(id);
        if (operation != null && clusterStore != null) clusterStore.cancel(clusterStore.authorize(operation, username, scope));
        return cancelled;
    }
    public LlmCallDetail detail(String id, String key, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.detail(id, key, scope);
    }
    private String signature(AnalysisRequest request, java.util.List<TaxonomyNodeDto> tree) {
        String legacy = AnalysisCheckpointSession.digest("resumable-scoring-v1", request.getBusinessText(),
                Boolean.toString(request.isIncludeArchitectureView()), String.valueOf(request.getMaxArchitectureNodes()),
                llm.recoveryPolicyFingerprint(request.getProvider()), mapper.writeValueAsString(tree),
                relations == null ? "" : relations.recoveryPolicyFingerprint());
        // Preserve existing durable identities while freezing additional semantic inputs.
        return request.getAnalysisScope().legacyFull() ? legacy
                : AnalysisCheckpointSession.digest(legacy, "analysis-scope-v1",
                        mapper.writeValueAsString(request.getAnalysisScope()));
    }
    private void authorize(String username, WorkspaceContext scope) {
        if (scope == null || !Objects.equals(username, scope.username()) || scope.workspaceId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An owned workspace is required");
        var workspace = workspaces.getWorkspaceById(scope.workspaceId());
        if (workspace == null || workspace.isArchived() || workspace.isShared()
                || !Objects.equals(username, workspace.getUsername()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis workspace not found");
    }
    public final class Execution implements AutoCloseable {
        private final AnalysisContinuationStore.Claim claim;
        private final AnalysisCheckpointSession session;
        private boolean completed;
        private final AnalysisContinuationBinding distributedBinding;
        private final String frozenProvider;
        private final com.taxonomy.extension.api.plugin.PluginInvocation providerBinding;
        Execution(AnalysisContinuationStore.Claim claim) { this(claim, null, null, null); }
        Execution(AnalysisContinuationStore.Claim claim, AnalysisContinuationBinding binding, String provider,
                com.taxonomy.extension.api.plugin.PluginInvocation pluginBinding) {
            this.claim = claim; distributedBinding = binding; frozenProvider = provider; providerBinding = pluginBinding;
            session = claim.completedResult() != null || binding != null ? null : new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                    return store.prepare(claim, q);
                }
                public RelationSearchReport previousRelations() {
                    return claim.previousRelations();
                }
                public void supersedeEvidence(String kind, String provider, java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
                    store.supersedeEvidence(claim, kind, provider, covered, null);
                }
                public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                    store.finish(claim, q, state, detail);
                    if ("SUCCESS".equals(state) && "PAUSED".equals(store.state(claim)))
                        throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
                }
                public void checkActive() {
                    String state = store.state(claim);
                    if ("CANCELLED".equals(state)) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                    if ("PAUSED".equals(state)) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION);
                }
            });
        }
        public AnalysisContinuationBinding binding() { return distributedBinding; }
        public com.taxonomy.analysis.usecase.AnalyzeRequirementCommand bind(com.taxonomy.analysis.usecase.AnalyzeRequirementCommand command) {
            return new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand(command.businessText(), command.includeArchitectureView(),
                    command.maxArchitectureNodes(), frozenProvider, command.username(), command.workspaceContext(), command.provenance(),
                    command.analysisScope(), providerBinding, distributedBinding);
        }
        public AnalysisResult replay() { return claim.completedResult(); }
        public AnalysisResult complete(AnalysisResult result) {
            AnalysisResult completedResult = store.complete(claim, result, catalogue.getFullTree());
            completed = true; return completedResult;
        }
        @Override public void close() {
            if (session != null) session.close();
            if (!completed && claim.completedResult() == null) {
                String operation = distributedBinding == null ? null : store.boundOperation(claim.id());
                if (operation == null || !store.owns(claim, operation)) store.interrupted(claim);
            }
        }
    }
}
