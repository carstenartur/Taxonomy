package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.relations.RelationRecoveryPolicy;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AdmittedCatalogue;
import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.analysis.usecase.AnalysisRequestModelAdmission;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
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
    private com.taxonomy.workspace.service.WorkspaceActorAccess workspaceActors =
            com.taxonomy.workspace.service.WorkspaceActorAccess.unavailable();

    @org.springframework.beans.factory.annotation.Autowired
    void configureWorkspaceActors(com.taxonomy.workspace.service.WorkspaceActorAccess actors) {
        this.workspaceActors = java.util.Objects.requireNonNull(actors);
    }

    private final AnalysisContinuationStore store;
    private final TaxonomyService catalogue;
    private final LlmService llm;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnalysisAdmissionModelPort admissionModels;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CatalogueSnapshotService catalogueSnapshots;
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
        if (!decision.isResumable())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Relations extension requires a durable source revision");
        if (decision.getRelationSourceVersion() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Relations extension requires a durable source revision");
        var input = store.relationInput(decision, scope);
        var saved = input.request();
        AnalysisRequestModelAdmission.requireExpected(saved.getModelBinding(),
                decision.getExpectedCommit(), decision.getExpectedSemanticRevision());
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
                 var reasons = AnalysisReasonContext.open(saved.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(saved.getAnalysisLanguage())) {
                var tree = treeFor(saved, scope);
                store.verifySourceSignature(decision.getRelationSourceContinuationId(), scope, signature(saved, tree));
                Authority authority = saved.getContinuationAuthority() == null
                        ? authority(provider, binding, tree) : mapper.readValue(saved.getContinuationAuthority(), Authority.class);
                requireProvider(authority, provider, binding);
                validateAuthority(authority, tree);
                if (saved.getModelBinding() == null) requireCurrentSource(result, scope);
                else AdmittedCatalogue.requireWorkspace(saved.getModelBinding(), scope);
                frozen.setProvider(authority.provider());
                frozen.setContinuationAuthority(saved.getContinuationAuthority() == null
                        ? mapper.writeValueAsString(authority) : saved.getContinuationAuthority());
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
                AnalysisEvidenceFingerprint.digestJson(mapper, tree));
    }
    private void validateAuthority(Authority saved, java.util.List<TaxonomyNodeDto> tree) {
        var binding = providers == null ? null : providers.captureProviderBinding(saved.provider());
        var current = authority(saved.provider(), binding, tree);
        if (!Objects.equals(saved.provider(), current.provider()) || !Objects.equals(saved.binding(), current.binding())
                || !Objects.equals(saved.scoringPolicy(), current.scoringPolicy())
                || !Objects.equals(saved.catalogueHash(), current.catalogueHash())) throw changedAuthority();
        relationPolicy(saved);
    }
    private static void requireProvider(Authority saved, String provider,
            com.taxonomy.extension.api.plugin.PluginInvocation binding) {
        if (!Objects.equals(saved.provider(), provider) || !Objects.equals(saved.binding(), binding)) throw changedAuthority();
    }
    private static ResponseStatusException changedAuthority() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Saved provider, catalogue or analysis policy changed; refresh the source context before extending relations");
    }
    private RelationRecoveryPolicy.Compatible relationPolicy(Authority saved) {
        String current = relations == null ? "" : relations.recoveryPolicyFingerprint();
        if (saved == null || Objects.equals(saved.relationPolicy(), AnalysisCheckpointSession.digest(current)))
            return new RelationRecoveryPolicy.Compatible(current, 0);
        var compatible = relations == null ? null : relations.compatibleRecoveryPolicy(saved.relationPolicy());
        if (compatible == null) throw changedAuthority();
        return compatible;
    }
    private AnalysisRequest frozenRequest(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes,
            AnalysisAdmissionModelPort initialModel) {
        if (request.getRelationSourceContinuationId() != null) return resolveRelationsRequest(request, username, scope);
        var frozen = mapper.readValue(mapper.writeValueAsString(request), AnalysisRequest.class);
        frozen.setMaxArchitectureNodes(maxNodes);
        var original = freezeGenerationContract(frozen, scope);
        var model = original == null
                ? AnalysisRequestModelAdmission.capture(initialModel, scope, request.getExpectedCommit(),
                        request.getExpectedSemanticRevision(), request.getAnalysisScope())
                : AnalysisRequestModelAdmission.requireExpected(original.getModelBinding(),
                        request.getExpectedCommit(), request.getExpectedSemanticRevision());
        frozen.setModelBinding(model);
        String originalAuthority = original == null ? null : original.getContinuationAuthority();
        Authority savedAuthority = originalAuthority == null ? null : mapper.readValue(originalAuthority, Authority.class);
        frozen.setRelationSourcePartial(false);
        if (providers != null) {
            String requested = request.getProvider();
            String provider = "MOCK".equalsIgnoreCase(requested) || providers.isMockMode() && !providers.isScenarioReplay(requested) ? "MOCK"
                    : requested == null || requested.isBlank() ? providers.getActiveProviderId().value() : requested.toUpperCase(java.util.Locale.ROOT);
            frozen.setProvider(provider);
            var binding = providers.captureProviderBinding(provider);
            try (var ignored = providers.withRequestProvider(provider, binding);
                 var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(frozen.getAnalysisLanguage())) {
                var tree = treeFor(frozen, scope);
                if (savedAuthority == null) frozen.setContinuationAuthority(mapper.writeValueAsString(authority(provider, binding, tree)));
                else {
                    requireProvider(savedAuthority, provider, binding);
                    validateAuthority(savedAuthority, tree);
                    frozen.setContinuationAuthority(originalAuthority);
                }
            }
        } else if (savedAuthority != null) {
            requireProvider(savedAuthority, frozen.getProvider(), null);
            try (var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(frozen.getAnalysisLanguage())) {
                validateAuthority(savedAuthority, treeFor(frozen, scope));
                frozen.setContinuationAuthority(originalAuthority);
            }
        } else frozen.setContinuationAuthority(null);
        return frozen;
    }
    private Execution relationsReplay(AnalysisRequest request, WorkspaceContext scope) {
        if (request.getRelationSourceContinuationId() == null
                || !"START".equals(Objects.toString(request.getContinuationAction(), "START"))) return null;
        var input = store.relationInput(request, scope);
        AnalysisRequestModelAdmission.requireExpected(input.request().getModelBinding(),
                request.getExpectedCommit(), request.getExpectedSemanticRevision());
        return input.request().getRelationSourceContinuationId() != null && !"RUNNING".equals(input.recovery().state())
                ? new Execution(new AnalysisContinuationStore.Claim(request.getContinuationId(), null, input.result()), null, input.request(), null, scope) : null;
    }
    public Execution begin(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        var replay = relationsReplay(request, scope); if (replay != null) return replay;
        return beginFrozen(frozenRequest(request, username, scope, maxNodes, admissionModels), scope);
    }

    /** A claimed portfolio item uses the model stored with its authorized job, including on a background thread. */
    public Execution beginPortfolio(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes,
            AnalysisModelBinding model) {
        return beginPortfolio(request, username, scope, maxNodes, model, workspaceActors::owns);
    }

    public Execution beginPortfolio(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes,
            AnalysisModelBinding model, WorkspaceActorAccess.AnalysisOwner owner) {
        authorize(username, scope, owner);
        if (model != null) {
            AdmittedCatalogue.requireWorkspace(model, scope);
            if (!Objects.equals(model.scope(), request.getAnalysisScope())) throw changedAuthority();
        }
        var frozen = frozenRequest(request, username, scope, maxNodes, (workspace, commit, revision, roots) -> model);
        if (!Objects.equals(frozen.getModelBinding(), model)) throw changedAuthority();
        return beginFrozen(frozen, scope);
    }

    private Execution beginFrozen(AnalysisRequest frozen, WorkspaceContext scope) {
        var tree = treeFor(frozen, scope);
        try {
            frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode)
                    .collect(java.util.stream.Collectors.toSet()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        Authority authority = frozen.getContinuationAuthority() == null ? null : mapper.readValue(frozen.getContinuationAuthority(), Authority.class);
        var binding = authority == null ? null : authority.binding();
        try (var ignored = providers == null ? null : providers.withRequestProvider(frozen.getProvider(), binding);
             var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(frozen.getAnalysisLanguage())) {
            // A claim may authorize execution only after its server-captured authority matches.
            // Standalone local callers without a provider envelope bind their policy in this scope.
            validateAuthority(authority == null ? authority(frozen.getProvider(), binding, tree) : authority, tree);
            return new Execution(store.begin(frozen, scope, signature(frozen, tree), tree), null, frozen, binding, scope);
        }
    }
    public Execution beginDistributed(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
        authorize(username, scope);
        var replay = relationsReplay(request, scope); if (replay != null) return replay;
        if (providers == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Frozen provider authority is unavailable");
        var frozen = frozenRequest(request, username, scope, maxNodes, admissionModels);
        String provider = frozen.getProvider();
        var tree = treeFor(frozen, scope);
        try { frozen.getAnalysisScope().validateRoots(tree.stream().map(TaxonomyNodeDto::getCode).collect(java.util.stream.Collectors.toSet())); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
        Authority authority = frozen.getContinuationAuthority() == null ? null : mapper.readValue(frozen.getContinuationAuthority(), Authority.class);
        var pluginBinding = authority == null ? providers.captureProviderBinding(provider) : authority.binding();
        try (var ignored = providers.withRequestProvider(provider, pluginBinding);
             var reasons = AnalysisReasonContext.open(frozen.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(frozen.getAnalysisLanguage())) {
            validateAuthority(authority == null ? authority(provider, pluginBinding, tree) : authority, tree);
            String signature = signature(frozen, tree);
            var claim = store.begin(frozen, scope, signature, tree);
            var budget = captureBudgetPolicy(claim, frozen);
            var binding = claim.completedResult() == null ? new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                    llm.recoveryPolicyFingerprint(provider), AnalysisCheckpointSession.digest(relations == null ? "" : relations.recoveryPolicyFingerprint()),
                    budget == null ? 0 : budget.sourceCohortSize(), budget) : null;
            return new Execution(claim, binding, frozen, pluginBinding, scope);
        }
    }
    private AnalysisRequest freezeGenerationContract(AnalysisRequest request, WorkspaceContext scope) {
        var original = store.originalRequest(request.getContinuationId(), scope);
        if (original.isPresent()) {
            var saved = original.get().getAnalysisReasonLength();
            if (request.getAnalysisReasonLength() != null
                    && request.getAnalysisReasonLength() != AnalysisReasonContext.effective(saved))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The analysis reason length belongs to the saved run; start a new analysis to change it");
            request.setAnalysisReasonLength(saved);
            var savedLanguage = original.get().getAnalysisLanguage();
            if (request.getAnalysisLanguage() != null && request.getAnalysisLanguage() != savedLanguage)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The analysis language belongs to the saved run; start a new analysis to change it");
            request.setAnalysisLanguage(savedLanguage);
        } else {
            request.setAnalysisReasonLength(request.getAnalysisReasonLength() != null
                    ? request.getAnalysisReasonLength() : AnalysisReasonContext.effective(llm.resolveAnalysisReasonLength(null)));
            request.setAnalysisLanguage(AnalysisLanguageContext.resolveCurrentRequest(request.getAnalysisLanguage()));
        }
        return original.orElse(null);
    }
    private RequirementRelationSearch.BudgetPolicy captureBudgetPolicy(AnalysisContinuationStore.Claim claim, AnalysisRequest request) {
        if (claim.completedResult() != null || relations == null) return null;
        var budget = relations.executionBudget();
        var previous = claim.previousRelations();
        int cohort = previous != null && previous.budget() != null ? previous.budget().sourceCohortSize()
                : relationPolicy(request == null || request.getContinuationAuthority() == null ? null
                        : mapper.readValue(request.getContinuationAuthority(), Authority.class)).sourceCohortSize();
        if (cohort == 0) cohort = budget.maxSources();
        cohort = store.freezeRelationSourceCohort(claim, cohort);
        return new RequirementRelationSearch.BudgetPolicy(budget.maxCalls(), budget.maxTotalCalls(), store.relationCallsSpent(claim),
                budget.autoContinue(), budget.maxSources(), budget.maxWorkItems(), cohort);
    }
    /** Authorized read-only evidence lookup; ordinary recovery reads retain their existing expiry lifecycle. */
    public AnalysisContinuationStore.Snapshot readEvidence(String id, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.readEvidence(id, scope);
    }
    public AnalysisContinuationStore.Snapshot read(String id, String username, WorkspaceContext scope) {
        authorize(username, scope); return store.read(id, scope);
    }
    public boolean hasBlockingBudgetQuestions(String id, String username, WorkspaceContext scope) {
        return hasBlockingBudgetQuestions(id, username, scope, workspaceActors::owns);
    }
    public boolean hasBlockingBudgetQuestions(String id, String username, WorkspaceContext scope,
            WorkspaceActorAccess.AnalysisOwner owner) {
        authorize(username, scope, owner);
        return store.hasBlockingBudgetQuestions(id, scope);
    }
    public AnalysisContinuationStore.Snapshot cancel(String id, String username, WorkspaceContext scope) {
        authorize(username, scope);
        var snapshot = store.read(id, scope);
        if (snapshot.result() == null || snapshot.result().getTree() == null) {
            var tree = treeFor(snapshot.request(), scope);
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
    private java.util.List<TaxonomyNodeDto> treeFor(AnalysisRequest request, WorkspaceContext workspace) {
        try (var ignored = AdmittedCatalogue.open(catalogueSnapshots,
                request == null ? null : request.getModelBinding(), workspace)) {
            return catalogue.getFullTree();
        }
    }

    private String signature(AnalysisRequest request, java.util.List<TaxonomyNodeDto> tree) {
        try (var reasons = AnalysisReasonContext.open(request.getAnalysisReasonLength());
                 var language = AnalysisLanguageContext.open(request.getAnalysisLanguage())) {
        var relationPolicy = relationPolicy(request.getContinuationAuthority() == null ? null
                : mapper.readValue(request.getContinuationAuthority(), Authority.class));
        String legacy = AnalysisEvidenceFingerprint.digestJson(mapper, new String[]{"resumable-scoring-v1", request.getBusinessText(),
                Boolean.toString(request.isIncludeArchitectureView()), String.valueOf(request.getMaxArchitectureNodes()),
                llm.recoveryPolicyFingerprint(request.getProvider())}, tree,
                relationPolicy.fingerprint());
        // Preserve existing durable identities while freezing additional semantic inputs.
        if (!request.getAnalysisScope().legacyFull()) legacy = AnalysisCheckpointSession.digest(legacy, "analysis-scope-v1",
                mapper.writeValueAsString(request.getAnalysisScope()));
        String reasoned = request.getAnalysisReasonLength() == null ? legacy : AnalysisCheckpointSession.digest(legacy,
                AnalysisReasonContext.POLICY, request.getAnalysisReasonLength().value());
        if (request.getAnalysisLanguage() != null) reasoned = AnalysisCheckpointSession.digest(reasoned,
                AnalysisLanguageContext.POLICY, request.getAnalysisLanguage().value());
        if (request.getModelBinding() != null) reasoned = AnalysisCheckpointSession.digest(reasoned,
                "architecture-model-v1", mapper.writeValueAsString(request.getModelBinding()));
        return request.getRelationSourceContinuationId() == null ? reasoned
                : AnalysisCheckpointSession.digest(reasoned, "retained-relations-v1", request.getRelationSourceContinuationId(),
                        String.valueOf(request.getRelationSourceVersion()));
        }
    }
    private void authorize(String username, WorkspaceContext scope) {
        authorize(username, scope, workspaceActors::owns);
    }
    private void authorize(String username, WorkspaceContext scope, WorkspaceActorAccess.AnalysisOwner owner) {
        if (scope == null || !Objects.equals(username, scope.username()) || scope.workspaceId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An owned workspace is required");
        var workspace = workspaces.getWorkspaceById(scope.workspaceId());
        if (workspace == null || workspace.isArchived() || workspace.isShared()
                || !owner.owns(username, workspace))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis workspace not found");
    }
    public final class Execution implements AutoCloseable {
        private final AnalysisContinuationStore.Claim claim;
        private final AnalysisCheckpointSession session;
        private boolean completed;
        private final AnalysisContinuationBinding distributedBinding;
        private final AnalysisRequest frozenRequest;
        private final WorkspaceContext workspace;
        private final com.taxonomy.extension.api.plugin.PluginInvocation providerBinding;
        private final RequirementRelationSearch.BudgetPolicy relationBudget;
        Execution(AnalysisContinuationStore.Claim claim) { this(claim, null, null, null, null); }
        Execution(AnalysisContinuationStore.Claim claim, AnalysisContinuationBinding binding, AnalysisRequest request,
                com.taxonomy.extension.api.plugin.PluginInvocation pluginBinding, WorkspaceContext workspace) {
            this.workspace = workspace;
            this.claim = claim; distributedBinding = binding; frozenRequest = request; providerBinding = pluginBinding;
            relationBudget = binding == null ? captureBudgetPolicy(claim, request) : binding.relationBudget();
            session = claim.completedResult() != null || binding != null ? null : new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                    return store.prepare(claim, q);
                }
                public RelationSearchReport previousRelations() {
                    return claim.previousRelations();
                }
                public int sourceCohortSize() { return relationBudget == null ? 0 : relationBudget.sourceCohortSize(); }
                public RequirementRelationSearch.BudgetPolicy relationBudget() { return relationBudget; }
                public int relationCallsSpent() { return store.relationCallsSpent(claim); }
                public void beforeRelationCall(int maximum) { store.beforeRelationCall(claim, maximum); }
                public void supersedeEvidence(String kind, String provider, java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
                    store.supersedeEvidence(claim, kind, provider, covered, null);
                }
                public void supersedeEvidence(String kind, String provider, java.util.Set<String> singletonCodes,
                        java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
                    store.supersedeEvidence(claim, kind, provider, singletonCodes, covered, null);
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
        public AnalysisLanguage analysisLanguage() {
            return frozenRequest == null ? null : frozenRequest.getAnalysisLanguage();
        }
        public AnalysisReasonLength analysisReasonLength() {
            return AnalysisReasonContext.effective(frozenRequest == null ? null : frozenRequest.getAnalysisReasonLength());
        }
        public com.taxonomy.analysis.usecase.AnalyzeRequirementCommand bind(com.taxonomy.analysis.usecase.AnalyzeRequirementCommand command) {
            if (frozenRequest == null) return command.withContinuationBinding(distributedBinding);
            return new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand(frozenRequest.getBusinessText(), frozenRequest.isIncludeArchitectureView(),
                    frozenRequest.getMaxArchitectureNodes(), frozenRequest.getProvider(), command.username(), command.workspaceContext(), command.provenance(),
                    frozenRequest.getAnalysisScope(), providerBinding, distributedBinding, analysisReasonLength(), claim.retainedAnalysis(), analysisLanguage(), frozenRequest.getModelBinding());
        }
        public AnalysisResult replay() { return claim.completedResult(); }
        public AnalysisResult complete(AnalysisResult result) {
            AnalysisResult completedResult = store.completeWithFallback(claim, result, () -> treeFor(frozenRequest, workspace));
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
