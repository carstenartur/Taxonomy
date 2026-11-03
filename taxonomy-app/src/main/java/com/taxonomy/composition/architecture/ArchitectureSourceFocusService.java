package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.analysis.session.AnalysisWorkingDraftService;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.dto.*;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Resolves authorized retained evidence before entering the shared, read-only inference engine. */
@com.taxonomy.shared.features.ConditionalOnFeature({"architecture", "analysis"})
@Service
public class ArchitectureSourceFocusService {
    private static final int MAX_PAYLOAD = 2_000_000;
    private final ArchitectureFocusSuggestionService engine;
    private final AnalysisWorkingDraftService drafts;
    private final ClusterAnalysisObservation operations;
    private final AnalysisContinuationService continuations;
    private final PortfolioAnalysisPersistenceService snapshots;
    private final UserWorkspaceRepository workspaces;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService membership;
    private final ObjectMapper json;

    @Autowired
    public ArchitectureSourceFocusService(ArchitectureFocusSuggestionService engine, AnalysisWorkingDraftService drafts,
            ObjectProvider<ClusterAnalysisObservation> operations, ObjectProvider<AnalysisContinuationService> continuations,
            ObjectProvider<PortfolioAnalysisPersistenceService> snapshots, UserWorkspaceRepository workspaces,
            SystemRepositoryService repositories, RepositoryMembershipService membership, ObjectMapper json) {
        this(engine,drafts,operations.getIfAvailable(),continuations.getIfAvailable(),snapshots.getIfAvailable(),
                workspaces,repositories,membership,json);
    }
    public ArchitectureSourceFocusService(ArchitectureFocusSuggestionService engine, AnalysisWorkingDraftService drafts,
            ClusterAnalysisObservation operations, AnalysisContinuationService continuations,
            PortfolioAnalysisPersistenceService snapshots, UserWorkspaceRepository workspaces,
            SystemRepositoryService repositories, RepositoryMembershipService membership, ObjectMapper json) {
        this.engine=engine; this.drafts=drafts; this.operations=operations; this.continuations=continuations;
        this.snapshots=snapshots; this.workspaces=workspaces; this.repositories=repositories; this.membership=membership; this.json=json;
    }
    private record Retained(RequirementArchitectureView graph, Map<String,String> provenance) { }

    public ArchitectureSourceFocusDtos.Response investigate(RepositoryContext selected, ArchitectureSourceFocusDtos.Request request) {
        var source = request.source();
        if (selected == null || selected.username() == null || !source.repositoryId().equals(selected.repositoryId()))
            throw problem("NOT_FOUND","Source repository is not accessible in the selected context");
        String workspace = WorkspaceOverlayScope.CENTRAL_SCOPE_KEY.equals(source.workspaceScopeKey()) ? null : source.workspaceScopeKey();
        UserWorkspace owned = null;
        if (workspace == null) {
            if (!membership.canRead(repositories.getRepository(source.repositoryId()),selected.username()))
                throw problem("NOT_FOUND","Source repository is not readable");
        } else owned = owned(workspace,selected.username(),source.repositoryId());
        var scope = new WorkspaceContext(selected.username(),workspace,source.branch(),source.repositoryId());
        Retained retained;
        if ("ANALYSIS_DRAFT".equals(source.kind())) {
            if (owned == null) throw problem("NOT_FOUND","Analysis drafts require a private owned workspace");
            if (!source.branch().equals(owned.getCurrentBranch())) throw problem("SOURCE_IDENTITY_MISMATCH","The source workspace branch changed");
            retained = draft(source,scope);
        } else retained = snapshot(source,scope);
        var model = model(retained.graph());
        var provenance = new TreeMap<>(retained.provenance());
        provenance.put("x-focus-source-kind",source.kind()); provenance.put("x-analysis-repository-id",source.repositoryId());
        provenance.put("x-analysis-workspace-scope",source.workspaceScopeKey()); provenance.put("x-analysis-branch",source.branch());
        String encoded = json.writeValueAsString(canonical(json.valueToTree(Map.of("source",source,"graph",retained.graph(),"provenance",provenance))));
        if (encoded.length() > MAX_PAYLOAD) throw problem("ITEM_LIMIT","Source graph exceeds the bounded investigation payload");
        String hash = StableIdentityHash.sha256(encoded);
        var result = engine.investigateModel(model,request.sourceId(),request.candidateLimit(),request.useAi(),request.language(),hash,provenance);
        return new ArchitectureSourceFocusDtos.Response(source,request.sourceId(),hash,result.method(),result.outcome(),result.modelCalls(),result.candidateCount(),result.suggestions());
    }

    private Retained draft(ArchitectureSourceFocusDtos.Source source, WorkspaceContext scope) {
        var draft = drafts.read(scope.username(),scope.workspaceId()).orElseThrow(() -> problem("SOURCE_UNAVAILABLE","No retained analysis draft is available"));
        if (!scope.workspaceId().equals(draft.workspaceId()) || !source.branch().equals(draft.branch()))
            throw problem("SOURCE_IDENTITY_MISMATCH","The draft source pins do not match");
        if (source.version() != draft.version()) throw problem("CONTEXT_CHANGED","The analysis draft version changed; refresh before investigating");
        JsonNode payload = draft.payload();
        if (payload == null || !payload.isObject()) throw problem("SOURCE_UNAVAILABLE","Retained draft is unavailable");
        if (json.writeValueAsString(payload).length() > MAX_PAYLOAD) throw problem("ITEM_LIMIT","Draft exceeds its bounded payload");
        JsonNode reference = payload.path("analysisReference");
        if (!reference.isObject() || "EMPTY".equals(payload.path("draftState").asString()))
            throw problem("SOURCE_UNAVAILABLE","The draft has no retained server analysis reference");
        if (!scope.workspaceId().equals(reference.path("workspaceId").asString()))
            throw problem("SOURCE_IDENTITY_MISMATCH","The retained analysis reference belongs to a different workspace");
        String operationId = optionalId(reference,"operationId"), continuationId = optionalId(reference,"continuationId");
        AnalysisResult result;
        var provenance = new TreeMap<String,String>(); provenance.put("x-focus-source-version",Long.toString(draft.version()));
        if (continuationId != null) {
            long recoveryVersion = recoveryVersion(reference);
            if (continuations == null) throw problem("SOURCE_UNAVAILABLE","Retained continuation evidence is unavailable");
            var saved = continuations.readEvidence(continuationId,scope.username(),scope);
            if (saved == null || saved.recovery() == null || !continuationId.equals(saved.recovery().id()) || saved.request() == null)
                throw problem("SOURCE_IDENTITY_MISMATCH","The retained continuation source does not match");
            if (saved.recovery().version() != recoveryVersion)
                throw problem("CONTEXT_CHANGED","The retained continuation version changed; restore and save the displayed analysis before investigating");
            result = saved.result();
            if (operationId != null && (saved.clusterOperationId() != null && !operationId.equals(saved.clusterOperationId())
                    || result != null && result.getAnalysisOperationId() != null && !operationId.equals(result.getAnalysisOperationId())
                    || saved.clusterOperationId() == null && (result == null || result.getAnalysisOperationId() == null)))
                throw problem("SOURCE_IDENTITY_MISMATCH","The retained operation and continuation disagree");
            provenance.put("x-focus-continuation-id",continuationId);
            provenance.put("x-focus-continuation-version",Long.toString(saved.recovery().version()));
        } else if (operationId != null) {
            if (operations == null) throw problem("SOURCE_UNAVAILABLE","Retained operation evidence is unavailable");
            var input = operations.request(operationId,scope.username(),scope);
            if (input == null || !operationId.equals(input.operationId()) || input.scope() == null
                    || !scope.workspaceId().equals(input.scope().workspaceId()) || !source.repositoryId().equals(input.scope().repositoryId())
                    || !source.branch().equals(input.scope().branch()))
                throw problem("SOURCE_IDENTITY_MISMATCH","The retained analysis input source does not match");
            result = operations.result(operationId,scope.username(),scope);
        } else throw problem("SOURCE_UNAVAILABLE","The draft has no retained server analysis reference");
        if (result == null) throw problem("SOURCE_UNAVAILABLE","The retained analysis result is not available yet");
        if (operationId != null && result.getAnalysisOperationId() != null && !operationId.equals(result.getAnalysisOperationId()))
            throw problem("SOURCE_IDENTITY_MISMATCH","The retained analysis operation changed");
        if (!Set.of("SUCCESS","PARTIAL").contains(Objects.toString(result.getStatus(),"")))
            throw problem("SOURCE_UNAVAILABLE","Only retained successful or partial architecture evidence can be investigated");
        if (operationId != null) provenance.put("x-focus-operation-id",operationId);
        provenance.put("x-analysis-status",result.getStatus());
        return new Retained(result.getArchitectureView(),provenance);
    }

    private Retained snapshot(ArchitectureSourceFocusDtos.Source source, WorkspaceContext scope) {
        if (snapshots == null) throw problem("SOURCE_UNAVAILABLE","Snapshot evidence is unavailable");
        var snapshot = snapshots.getSnapshot(source.projectId(),source.snapshotId(),scope.username(),scope);
        var summary = snapshot.summary();
        if (!source.snapshotId().equals(summary.id()) || !source.projectId().equals(summary.projectId())
                || !source.requirementId().equals(summary.requirementId()) || !source.requirementVersionId().equals(summary.requirementVersionId())
                || !source.branch().equals(summary.branchName()) || !Objects.equals(scope.workspaceId(),summary.workspaceId()))
            throw problem("SOURCE_IDENTITY_MISMATCH","Snapshot identity does not match the exact requested source");
        if (summary.status() != AnalysisStatus.SUCCESS && summary.status() != AnalysisStatus.PARTIAL)
            throw problem("SOURCE_UNAVAILABLE","Only retained successful or partial architecture evidence can be investigated");
        var provenance = new TreeMap<String,String>();
        provenance.put("x-analysis-project-id",source.projectId().toString()); provenance.put("x-analysis-requirement-id",source.requirementId().toString());
        provenance.put("x-analysis-requirement-version-id",source.requirementVersionId().toString()); provenance.put("x-analysis-snapshot-id",source.snapshotId());
        provenance.put("x-analysis-status",summary.status().name());
        put(provenance,"x-analysis-commit",summary.commitSha()); put(provenance,"x-analysis-catalogue-fingerprint",summary.taxonomyFingerprint());
        put(provenance,"x-analysis-provider",summary.provider()); put(provenance,"x-analysis-model",summary.modelName());
        put(provenance,"x-analysis-created-at",Objects.toString(summary.createdAt(),null));
        return new Retained(snapshot.analysis() == null ? null : snapshot.analysis().getArchitectureView(),provenance);
    }

    private CanonicalArchitectureModel model(RequirementArchitectureView graph) {
        if (graph == null || graph.getIncludedElements() == null || graph.getIncludedRelationships() == null || graph.getIncludedElements().isEmpty())
            throw problem("SOURCE_UNAVAILABLE","Source contains no retained full architecture graph");
        if (graph.getIncludedElements().size() > 2000 || graph.getIncludedRelationships().size() > 10000)
            throw problem("ITEM_LIMIT","Source graph exceeds investigation limits");
        var model = new CanonicalArchitectureModel(); var ids = new HashSet<String>();
        for (var element : graph.getIncludedElements()) {
            if (element == null) throw problem("SOURCE_IDENTITY_MISMATCH","Source element identity is incomplete");
            String id = element.getNodeCode(), root = element.getTaxonomySheet();
            if (!bounded(id,200) || !TaxonomyRootTypes.ROOT_TO_TYPE.containsKey(Objects.toString(root,""))
                    || !(root.equals(TaxonomyRootTypes.rootFromId(id)) || id.equals(root)) || !ids.add(id))
                throw problem("SOURCE_IDENTITY_MISMATCH","Recorded element identity and taxonomy type disagree or are duplicated");
            if (element.getTitle() == null || element.getTitle().isBlank())
                throw problem("SOURCE_UNAVAILABLE","Source element has no retained title evidence");
            model.getElements().add(new ArchitectureElement(id,TaxonomyRootTypes.typeFor(root),element.getTitle(),
                    Objects.toString(element.getPresenceReason(),Objects.toString(element.getIncludedBecause(),"")),root));
        }
        var relationKeys = new HashSet<String>();
        for (var relation : graph.getIncludedRelationships()) {
            if (relation == null || !ids.contains(relation.getSourceCode()) || !ids.contains(relation.getTargetCode())
                    || !bounded(relation.getRelationType(),40) || !DslValidator.relationTypes().contains(relation.getRelationType()))
                throw problem("SOURCE_IDENTITY_MISMATCH","Source relation has unknown endpoints or type");
            String key = relation.getSourceCode()+" "+relation.getRelationType()+" "+relation.getTargetCode();
            if (!relationKeys.add(key)) throw problem("SOURCE_IDENTITY_MISMATCH","Source relation identity is duplicated");
            model.getRelations().add(new ArchitectureRelation(relation.getSourceCode(),relation.getRelationType(),relation.getTargetCode()));
        }
        return model;
    }
    private UserWorkspace owned(String id,String username,String repositoryId) {
        var workspace = workspaces.findByWorkspaceId(id).orElseThrow(() -> problem("NOT_FOUND","Workspace is not accessible"));
        if (!username.equals(workspace.getUsername()) || workspace.isShared() || workspace.isArchived() || !repositoryId.equals(workspace.getSourceRepositoryId()))
            throw problem("NOT_FOUND","Workspace is not accessible");
        return workspace;
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode(); value.propertyNames().stream().sorted().forEach(key -> result.set(key,canonical(value.get(key)))); return result;
        }
        if (value.isArray()) { var result = json.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        return value;
    }
    private static long recoveryVersion(JsonNode reference) {
        JsonNode value = reference.get("recoveryVersion");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw problem("SOURCE_UNAVAILABLE","The draft has no exact retained continuation recovery version");
        return value.longValue();
    }
    private static String optionalId(JsonNode reference,String key) {
        JsonNode value = reference.get(key); if (value == null || value.isNull()) return null;
        if (!value.isString() || !bounded(value.asString(),255)) throw problem("SOURCE_IDENTITY_MISMATCH","Retained analysis reference is invalid");
        return value.asString();
    }
    private static boolean bounded(String value,int maximum) { return value != null && !value.isBlank() && value.length() <= maximum && value.chars().noneMatch(Character::isISOControl); }
    private static void put(Map<String,String> values,String key,String value) { if (value != null) values.put(key,value); }
    private static CommandProblem problem(String code,String detail) { return new CommandProblem(code,"source",detail,List.of()); }
}
