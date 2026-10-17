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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkspaceViewContextReadPort views;
    private record Authority(String provider, com.taxonomy.extension.api.plugin.PluginInvocation binding,
            String scoringPolicy, String relationPolicy, String catalogueHash) { }

    /** The only HTTP input for an extension is an owned source revision and a new target identity. */
    public AnalysisRequest resolveRelationsRequest(AnalysisRequest decision, String username, WorkspaceContext scope) {
        authorize(username, scope);
        if (!decision.isResumable() || decision.getRelationSourceVersion() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Relations extension requires a durable source revision");
        var input = store.relationInput(decision, scope);
        var saved = input.request();
        boolean replay = saved.getRelationSourceContinuationId() != null;
        if (decision.getBusinessText() != null && !Objects.equals(decision.getBusinessText(), saved.getBusinessText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Requirement text differs from the retained source");
        var frozen = mapper.readValue(mapper.writeValueAsString(saved), AnalysisRequest.class);
        if (!replay) {
            var result = input.result();
            String provider = result.getAnalysisProviderId();
            if (provider == null || provider.isBlank()) provider = saved.getProvider();
            if (provider == null || provider.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The original provider authority is unavailable; imported legacy evidence cannot authorize relation calls");
            var binding = providers == null ? null : providers.captureProviderBinding(provider);
            if (input.clusterOperationId() != null && clusterStore != null) {
                var original = clusterStore.command(clusterStore.authorize(input.clusterOperationId(), username, scope));
                if (!Objects.equals(provider, original.provider())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Saved provider authority differs");
                binding = original.providerBinding();
            } else if (saved.getContinuationAuthority() == null && binding != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The original plugin provider binding is unavailable");
            }
            try (var ignored = providers == null ? null : providers.withRequestProvider(provider, binding);
                 var reasons = AnalysisReasonContext.open(saved.getAnalysisReasonLength())) {
                var tree = catalogue.getFullTree();
                store.verifySourceSignature(decision.getRelationSourceContinuationId(), scope, signature(saved, tree));
                Authority authority = saved.getContinuationAuthority() == null
                        ? authority(provider, binding, tree) : mapper.readValue(saved.getContinuationAuthority(), Authority.class);
                validateAuthority(authority, tree);
                requireCurrentSource(result, scope);
                frozen.setProvider(authority.provider());
                frozen.setContinuationAuthority(mapper.writeValueAsString(authority));
            }
            frozen.setAnalysisScope(new AnalysisScope(saved.getAnalysisScope().taxonomyRoots(), AnalysisMode.FULL));
            frozen.setIncludeArchitectureView(true);
            frozen.setRelationSourceContinuationId(decision.getRelationSourceContinuationId());
            frozen.setRelationSourceVersion(decision.getRelationSourceVersion());
            frozen.setRelationSourcePartial(!"SUCCESS".equals(result.getStatus()) || result.getAnalysisCoverage().hasOpenEvaluations());
        }
        frozen.setResumable(true); frozen.setContinuationId(decision.getContinuationId());
        frozen.setContinuationAction(decision.getContinuationAction()); frozen.setContinuationVersion(decision.getContinuationVersion());
        frozen.setContinuationQuestion(decision.getContinuationQuestion()); frozen.setContinuationQuestions(decision.getContinuationQuestions());
        return frozen;
    }
    private void requireCurrentSource(AnalysisResult result, WorkspaceContext scope) {
        var original = result.getViewContext();
        if (views == null || original == null || original.basedOnCommit() == null || original.basedOnBranch() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The exact original source authority is unavailable");
        var current = views.getViewContext(scope.username(), scope.currentBranch(), scope);
        if (current == null || !Objects.equals(original.basedOnCommit(), current.basedOnCommit())
                || !Objects.equals(original.basedOnBranch(), current.basedOnBranch()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The requirement source changed; the saved analysis cannot be extended");
    }
    private Authority authority(String provider, com.taxonomy.extension.api.plugin.PluginInvocation binding,
            java.util.List<TaxonomyNodeDto> tree) {
        return new Authority(provider, binding, llm.recoveryPolicyFingerprint(provider),
                AnalysisCheckpointSession.digest(relations == null ? "" : relations.recoveryPolicyFingerprint()),
                AnalysisCheckpointSession.digest(mapper.writeValueAsString(tree)));
    }
    private void validateAuthority(Authority saved, java.util.List<TaxonomyNodeDto> tree) {
        if (!saved.equals(authority(saved.provider(), saved.binding(), tree)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Saved provider, catalogue or analysis policy changed; refresh the source context before extending relations");
    }
    private AnalysisRequest frozenRequest(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        if (request.getRelationSourceContinuationId() != null) return resolveRelationsRequest(request, username, scope);
        var frozen = mapper.readValue(mapper.writeValueAsString(request), AnalysisRequest.class);
        frozen.setMaxArchitectureNodes(maxNodes);
        freezeReasonLength(frozen, scope);
        frozen.setRelationSourcePartial(false);
        if (providers != null) {
            String requested = request.getProvider();
            String provider = "MOCK".equalsIgnoreCase(requested) || providers.isMockMode() && !providers.isScenarioReplay(requested) ? "MOCK"
                    : requested == null || requested.isBlank() ? providers.getActiveProviderId().value() : requested.toUpperCase(java.util.Locale.ROOT);
            frozen.setProvider(provider);
            var binding = providers.captureProviderBinding(provider);
            try (var ignored = providers.withRequestProvider(provider, binding);
                 var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength())) {
                frozen.setContinuationAuthority(mapper.writeValueAsString(authority(provider, binding, catalogue.getFullTree())));
            }
        } else frozen.setContinuationAuthority(null);
        return frozen;
    }
    private Execution relationsReplay(AnalysisRequest request, WorkspaceContext scope) {
        if (request.getRelationSourceContinuationId() == null
                || !"START".equals(Objects.toString(request.getContinuationAction(), "START"))) return null;
        var input = store.relationInput(request, scope);
        return input.request().getRelationSourceContinuationId() != null && !"RUNNING".equals(input.recovery().state())
                ? new Execution(new AnalysisContinuationStore.Claim(request.getContinuationId(), null, input.result()), null, input.request(), null) : null;
    }
    public Execution begin(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        var replay = relationsReplay(request, scope); if (replay != null) return replay;
        AnalysisRequest frozen = frozenRequest(request, username, scope, maxNodes);
        var tree = catalogue.getFullTree();
        try {
            frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode)
                    .collect(java.util.stream.Collectors.toSet()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        Authority authority = frozen.getContinuationAuthority() == null ? null : mapper.readValue(frozen.getContinuationAuthority(), Authority.class);
        var binding = authority == null ? null : authority.binding();
        try (var ignored = providers == null ? null : providers.withRequestProvider(frozen.getProvider(), binding);
             var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength())) {
            if (request.getRelationSourceContinuationId() != null) validateAuthority(authority, tree);
            return new Execution(store.begin(frozen, scope, signature(frozen, tree), tree), null, frozen, binding);
        }
    }
    public Execution beginDistributed(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        var replay = relationsReplay(request, scope); if (replay != null) return replay;
        if (providers == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Frozen provider authority is unavailable");
        var frozen = frozenRequest(request, username, scope, maxNodes);
        String provider = frozen.getProvider();
        var tree = catalogue.getFullTree();
        try { frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode).collect(java.util.stream.Collectors.toSet())); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
        Authority authority = frozen.getContinuationAuthority() == null ? null : mapper.readValue(frozen.getContinuationAuthority(), Authority.class);
        var pluginBinding = authority == null ? providers.captureProviderBinding(provider) : authority.binding();
        try (var ignored = providers.withRequestProvider(provider, pluginBinding);
             var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength())) {
            if (request.getRelationSourceContinuationId() != null) validateAuthority(authority, tree);
            String signature = signature(frozen, tree);
            var claim = store.begin(frozen, scope, signature, tree);
            var binding = claim.completedResult() == null ? new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                    llm.recoveryPolicyFingerprint(provider), AnalysisCheckpointSession.digest(relations == null ? "" : relations.recoveryPolicyFingerprint())) : null;
            return new Execution(claim, binding, frozen, pluginBinding);
        }
    }
    private void freezeReasonLength(AnalysisRequest request, WorkspaceContext scope) {
        var original = store.originalRequest(request.getContinuationId(), scope);
        if (original.isPresent()) {
            var saved = original.get().getAnalysisReasonLength();
            if (request.getAnalysisReasonLength() != null
                    && request.getAnalysisReasonLength() != AnalysisReasonContext.effective(saved))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The analysis reason length belongs to the saved run; start a new analysis to change it");
            request.setAnalysisReasonLength(saved);
        } else request.setAnalysisReasonLength(request.getAnalysisReasonLength() != null
                ? request.getAnalysisReasonLength() : AnalysisReasonContext.effective(llm.resolveAnalysisReasonLength(null)));
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
        if (operation != null && clusterStore != null) {
            clusterStore.cancel(clusterStore.authorize(operation, username, scope));
            return store.read(id, scope); // Include relation work committed before cluster cancellation.
        }
        return cancelled;
    }
    public LlmCallDetail detail(String id, String key, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.detail(id, key, scope);
    }
    private String signature(AnalysisRequest request, java.util.List<TaxonomyNodeDto> tree) {
        try (var reasons = AnalysisReasonContext.open(request.getAnalysisReasonLength())) {
        String legacy = AnalysisCheckpointSession.digest("resumable-scoring-v1", request.getBusinessText(),
                Boolean.toString(request.isIncludeArchitectureView()), String.valueOf(request.getMaxArchitectureNodes()),
                llm.recoveryPolicyFingerprint(request.getProvider()), mapper.writeValueAsString(tree),
                relations == null ? "" : relations.recoveryPolicyFingerprint());
        // Preserve existing durable identities while freezing additional semantic inputs.
        if (!request.getAnalysisScope().legacyFull()) legacy = AnalysisCheckpointSession.digest(legacy, "analysis-scope-v1",
                mapper.writeValueAsString(request.getAnalysisScope()));
        String reasoned = request.getAnalysisReasonLength() == null ? legacy : AnalysisCheckpointSession.digest(legacy,
                AnalysisReasonContext.POLICY, request.getAnalysisReasonLength().value());
        return request.getRelationSourceContinuationId() == null ? reasoned
                : AnalysisCheckpointSession.digest(reasoned, "retained-relations-v1", request.getRelationSourceContinuationId(),
                        String.valueOf(request.getRelationSourceVersion()));
        }
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
        private final AnalysisRequest frozenRequest;
        private final com.taxonomy.extension.api.plugin.PluginInvocation providerBinding;
        Execution(AnalysisContinuationStore.Claim claim) { this(claim, null, null, null); }
        Execution(AnalysisContinuationStore.Claim claim, AnalysisContinuationBinding binding, AnalysisRequest request,
                com.taxonomy.extension.api.plugin.PluginInvocation pluginBinding) {
            this.claim = claim; distributedBinding = binding; frozenRequest = request; providerBinding = pluginBinding;
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
        public AnalysisReasonLength analysisReasonLength() {
            return AnalysisReasonContext.effective(frozenRequest == null ? null : frozenRequest.getAnalysisReasonLength());
        }
        public com.taxonomy.analysis.usecase.AnalyzeRequirementCommand bind(com.taxonomy.analysis.usecase.AnalyzeRequirementCommand command) {
            if (frozenRequest == null) return command.withContinuationBinding(distributedBinding);
            return new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand(frozenRequest.getBusinessText(), frozenRequest.isIncludeArchitectureView(),
                    frozenRequest.getMaxArchitectureNodes(), frozenRequest.getProvider(), command.username(), command.workspaceContext(), command.provenance(),
                    frozenRequest.getAnalysisScope(), providerBinding, distributedBinding, analysisReasonLength(), claim.retainedAnalysis());
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
