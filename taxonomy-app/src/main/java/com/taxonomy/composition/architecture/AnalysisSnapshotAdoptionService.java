package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.SnapshotEvidence;
import com.taxonomy.dto.RelationSearchModel;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver.Node;
import com.taxonomy.dsl.model.CatalogueReference;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Composes retained portfolio evidence into the existing semantic editor boundary. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@Service
public class AnalysisSnapshotAdoptionService {
    private final ArchitectureEditorService editor;
    private final PortfolioAnalysisPersistenceService snapshots;
    private final ProjectPortfolioService projects;
    private final UserWorkspaceRepository workspaces;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService membership;
    private WorkspaceActorAccess actorAccess = WorkspaceActorAccess.unavailable();
    private CatalogueDefinitionResolver catalogues = CatalogueDefinitionResolver.standard();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setActorAccess(WorkspaceActorAccess actorAccess) { this.actorAccess = Objects.requireNonNull(actorAccess); }

    @org.springframework.beans.factory.annotation.Autowired
    public void setCatalogueDefinitionResolver(CatalogueDefinitionResolver catalogues) {
        this.catalogues = Objects.requireNonNull(catalogues);
    }

    public AnalysisSnapshotAdoptionService(ArchitectureEditorService editor, PortfolioAnalysisPersistenceService snapshots,
            ProjectPortfolioService projects, UserWorkspaceRepository workspaces, SystemRepositoryService repositories,
            RepositoryMembershipService membership) {
        this.editor = editor; this.snapshots = snapshots; this.projects = projects;
        this.workspaces = workspaces; this.repositories = repositories; this.membership = membership;
    }

    public record SourceRef(String repositoryId, String workspaceScopeKey, String branch, Long projectId,
                            Long requirementId, Long requirementVersionId, String snapshotId,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String workspaceId) {
        public SourceRef(String repositoryId, String workspaceScopeKey, String branch, Long projectId,
                         Long requirementId, Long requirementVersionId, String snapshotId) {
            this(repositoryId, workspaceScopeKey, branch, projectId, requirementId, requirementVersionId, snapshotId, null);
        }
        public SourceRef {
            for (String value : Arrays.asList(repositoryId,workspaceScopeKey,branch,snapshotId))
                if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("An explicit bounded source repository, workspace scope, branch and snapshot are required");
            WorkspaceOverlayScope.resolveWorkspaceId(workspaceScopeKey, workspaceId);
            for (Long id : Arrays.asList(projectId,requirementId,requirementVersionId))
                if (id == null || id < 1) throw new IllegalArgumentException("Exact source project, requirement and version are required");
        }
    }
    public record Request(Context context, Metadata metadata, SourceRef source, String previewFingerprint) {
        public Request { Objects.requireNonNull(context); Objects.requireNonNull(metadata); Objects.requireNonNull(source); }
    }
    public record Difference(String id, String field, String existingValue, String sourceValue) {}
    public record Summary(List<String> addedElements, List<String> reusedElements, List<String> addedRelations,
                          List<String> reusedRelations, List<Difference> differences, List<String> conflicts) {}
    public record PreviewResult(SourceRef source, String viewId, String fingerprint, Summary summary,
                                boolean existing, Preview preview) {}
    public record Adopted(SourceRef source, String viewId, Summary summary, boolean existing, Accepted accepted) {}
    private record Prepared(AdoptAnalysisSnapshot adoption, Summary summary, boolean existing, String fingerprint) {}

    public PreviewResult preview(RepositoryContext target, Request request) throws IOException {
        Prepared prepared = prepare(target, request);
        Preview preview;
        try { preview = editor.preview(target, command(request, prepared)); }
        catch (CommandProblem error) {
            if (!Set.of("SNAPSHOT_TYPE_CONFLICT", "SNAPSHOT_IDENTITY_CONFLICT").contains(error.code())) throw error;
            Summary prior = prepared.summary();
            var summary = new Summary(prior.addedElements(),prior.reusedElements(),prior.addedRelations(),prior.reusedRelations(),
                    prior.differences(),List.of(error.getMessage()));
            return new PreviewResult(request.source(),prepared.adoption().viewId(),prepared.fingerprint(),summary,prepared.existing(),null);
        }
        return new PreviewResult(request.source(), prepared.adoption().viewId(), prepared.fingerprint(),
                prepared.summary(), prepared.existing(), preview);
    }
    public Adopted adopt(RepositoryContext target, Request request) throws IOException {
        // Rebuild from the retained snapshot, never from the browser's preview graph.
        Prepared prepared = prepare(target, request);
        if (!prepared.fingerprint().equals(request.previewFingerprint()))
            throw problem("PREVIEW_CHANGED", "Preview this exact frozen source and target revision again");
        try {
            Accepted accepted = editor.execute(target, command(request, prepared));
            return new Adopted(request.source(),prepared.adoption().viewId(),prepared.summary(),false,accepted);
        } catch (CommandProblem error) {
            if (!"NO_CHANGE".equals(error.code()) || !prepared.existing()) throw error;
            // A new intent for an already-adopted source opens its canonical view without a duplicate revision.
            return new Adopted(request.source(),prepared.adoption().viewId(),prepared.summary(),true,null);
        }
    }
    private Command command(Request request, Prepared prepared) {
        return new Command(request.context(),request.metadata(),new SemanticCommand(prepared.adoption()));
    }

    /** Read access to the exact immutable requirement version used by a frozen snapshot. */
    String requirementText(Long projectId, Long requirementId, Long versionId, String actor, WorkspaceContext scope) {
        return projects.listRequirementVersions(projectId, requirementId, actor, scope).stream()
                .filter(candidate -> versionId.equals(candidate.id())).findFirst()
                .orElseThrow(() -> problem("NOT_FOUND", "The exact retained requirement version is unavailable")).text();
    }

    private Prepared prepare(RepositoryContext target, Request request) throws IOException {
        SourceRef source = request.source();
        if (target.scope() != RepositoryScope.WORKSPACE) throw problem("READ_ONLY", "Adoption requires an editable working copy");
        var targetWorkspace = owned(target.workspaceId(), target.username(), target.repositoryId());
        if (targetWorkspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY)
            throw problem("READ_ONLY", "Target workspace is not ready");
        if (!target.branch().equals(targetWorkspace.getCurrentBranch()) || !source.repositoryId().equals(target.repositoryId()))
            throw problem("NOT_FOUND", "The exact source and target repository are not accessible");
        if (!Context.of(target,request.context().commit(),request.context().revision()).equals(request.context()))
            throw problem("CONTEXT_CHANGED", "The target context changed");
        String sourceWorkspace = WorkspaceOverlayScope.resolveWorkspaceId(source.workspaceScopeKey(), source.workspaceId());
        if (sourceWorkspace == null) {
            if (!membership.canRead(repositories.getRepository(source.repositoryId()),target.username()))
                throw problem("NOT_FOUND", "Source repository is not readable");
        } else owned(sourceWorkspace,target.username(),source.repositoryId());
        WorkspaceContext sourceContext = new WorkspaceContext(target.username(),sourceWorkspace,source.branch(),source.repositoryId());
        var snapshot = snapshots.getSnapshot(source.projectId(),source.snapshotId(),target.username(),sourceContext);
        var summary = snapshot.summary();
        if (!source.snapshotId().equals(summary.id()) || !source.projectId().equals(summary.projectId())
                || !source.requirementId().equals(summary.requirementId()) || !source.requirementVersionId().equals(summary.requirementVersionId())
                || !source.branch().equals(summary.branchName()) || !Objects.equals(sourceWorkspace,summary.workspaceId()))
            throw problem("SOURCE_IDENTITY_MISMATCH", "Snapshot identity does not match the exact requested source");
        if (summary.status() != AnalysisStatus.SUCCESS && summary.status() != AnalysisStatus.PARTIAL)
            throw problem("INVALID_SNAPSHOT", "Only retained successful or partial architecture evidence can be adopted");
        String requirementText = requirementText(source.projectId(), source.requirementId(), source.requirementVersionId(),
                target.username(), sourceContext);
        SnapshotEvidence.requireUtf8(requirementText);
        var graph = snapshot.analysis() == null ? null : snapshot.analysis().getArchitectureView();
        if (graph == null || graph.getIncludedElements() == null || graph.getIncludedRelationships() == null)
            throw problem("INVALID_SNAPSHOT", "Snapshot contains no retained full architecture graph");
        if (graph.getIncludedElements().isEmpty() || graph.getIncludedElements().size() > 2000 || graph.getIncludedRelationships().size() > 10000)
            throw problem("ITEM_LIMIT", "Snapshot graph exceeds adoption limits");
        var binding = snapshot.analysis().getModelBinding();
        String catalogueRevision = binding == null ? null : binding.analysisCatalogueRevision();
        CatalogueDefinition definition = CatalogueDefinition.standard();
        Map<String, Node> recordedNodes = new HashMap<>();
        if (catalogueRevision != null) {
            try {
                definition = catalogues.forRevision(catalogueRevision);
                catalogues.analysisNodes(catalogueRevision).forEach(node -> recordedNodes.put(node.code(), node));
            } catch (IllegalStateException | NoSuchElementException unavailable) {
                throw problem("CATALOGUE_UNAVAILABLE", "The catalogue recorded for the retained analysis is unavailable");
            }
        }
        CatalogueDefinition recordedDefinition = definition;
        List<SnapshotElement> elements = graph.getIncludedElements().stream().map(element -> {
            String sheet = element == null ? null : element.getTaxonomySheet();
            String type = sheet == null ? null : recordedDefinition.typeFor(sheet);
            if (type == null)
                throw problem("UNKNOWN_TYPE", "Snapshot has unsupported or missing recorded taxonomy type");
            // Recorded root plus recorded code establish identity; do not synthesize nodes from renderer containers.
            var node = recordedNodes.get(element.getNodeCode());
            String root = node == null ? recordedDefinition.rootFromId(element.getNodeCode()) : node.root();
            if (!Objects.equals(root, sheet))
                throw problem("AMBIGUOUS_SNAPSHOT_IDENTITY", "Recorded element identity and taxonomy type disagree");
            var assigned = element.getResponsibility();
            var responsibility = assigned == null ? null
                    : new com.taxonomy.dsl.command.ArchitectureCommand.SnapshotResponsibility(
                            assigned.unitId(), assigned.name(), assigned.sourceId());
            return new SnapshotElement(element.getNodeCode(), type,
                    element.getTitle(),Objects.toString(element.getPresenceReason(), Objects.toString(element.getIncludedBecause(),"")),
                    responsibility, node == null ? null : CatalogueReference.from(node.uuid(), node.nameEn(), node.nameDe()));
        }).sorted(Comparator.comparing(SnapshotElement::id,Comparator.nullsFirst(String::compareTo))).toList();
        List<RelationKey> relations = graph.getIncludedRelationships().stream().map(relation -> {
            if (relation == null || relation.getSourceCode() == null || relation.getTargetCode() == null || relation.getRelationType() == null)
                throw problem("INVALID_SNAPSHOT", "Snapshot relation identity is incomplete");
            return new RelationKey(relation.getSourceCode(),relation.getRelationType(),relation.getTargetCode());
        }).sorted(Comparator.comparing(RelationKey::id)).toList();
        List<String> graphParts = new ArrayList<>();
        elements.forEach(e -> {
            graphParts.addAll(Arrays.asList("element",e.id(),e.type(),e.title(),e.description()));
            if (e.responsibility() != null) graphParts.addAll(List.of("responsibility", e.responsibility().unitId(),
                    e.responsibility().name(), e.responsibility().sourceId()));
        });
        relations.forEach(r -> graphParts.addAll(List.of("relation",r.sourceId(),r.relationType(),r.targetId())));
        String graphFingerprint = hash(graphParts);
        var viewParts = new ArrayList<>(List.of(source.repositoryId(),source.workspaceScopeKey(),source.branch(),
                source.projectId().toString(),source.requirementId().toString(),source.requirementVersionId().toString(),source.snapshotId()));
        Map<String,String> provenance = new TreeMap<>();
        if (WorkspaceOverlayScope.CENTRAL_SCOPE_KEY.equals(sourceWorkspace)) {
            viewParts.add(sourceWorkspace);
            provenance.put("x-analysis-workspace-id", sourceWorkspace);
        }
        String viewId = "analysis-" + hash(viewParts);
        provenance.put("x-analysis-repository-id",source.repositoryId()); provenance.put("x-analysis-workspace-scope",source.workspaceScopeKey());
        provenance.put("x-analysis-branch",source.branch()); provenance.put("x-analysis-project-id",source.projectId().toString());
        provenance.put("x-analysis-requirement-id",source.requirementId().toString()); provenance.put("x-analysis-requirement-version-id",source.requirementVersionId().toString());
        provenance.put("x-analysis-snapshot-id",source.snapshotId()); provenance.put("x-analysis-requirement-text-hash",StableIdentityHash.sha256(requirementText));
        put(provenance,"x-analysis-commit",summary.commitSha()); put(provenance,"x-analysis-catalogue-fingerprint",summary.taxonomyFingerprint());
        put(provenance,"x-analysis-provider",summary.provider()); put(provenance,"x-analysis-model",summary.modelName());
        put(provenance,"x-analysis-status",summary.status().name()); put(provenance,"x-analysis-created-at",Objects.toString(summary.createdAt(),null));
        String title = graph.getViewTitle() == null || graph.getViewTitle().isBlank() ? "Analysis " + summary.requirementKey() : graph.getViewTitle();
        var document = editor.read(target,null);
        var model = new ArchitectureDslCommands().model(document.dsl());
        var existingView = model.getViews().stream().filter(v -> v.getId().equals(viewId)).findFirst();
        if (existingView.isEmpty() || existingView.get().getExtensions().containsKey("x-analysis-catalogue-revision"))
            put(provenance, "x-analysis-catalogue-revision", catalogueRevision);
        // A pre-evidence adoption has only the original graph proof. Reopening uses its original command fingerprint.
        boolean legacyView = existingView.isPresent() && !existingView.get().getExtensions().containsKey(SnapshotEvidence.FINGERPRINT);
        AdoptAnalysisSnapshot adoption;
        if (legacyView) {
            adoption = new AdoptAnalysisSnapshot(viewId, title, requirementText, graphFingerprint, elements, relations, provenance);
        } else {
            Map<String, String> sheets = new HashMap<>();
            graph.getIncludedElements().forEach(element -> sheets.put(element.getNodeCode(), element.getTaxonomySheet()));
            var observations = frozenEvidence(graph.getIncludedRelationships(), requirementText, sheets);
            adoption = new AdoptAnalysisSnapshot(viewId, title, requirementText, graphFingerprint, elements, relations, provenance, observations);
        }
        Map<String, String> organizationNames = new HashMap<>();
        model.getOrganizations().forEach(unit -> organizationNames.put(unit.id(), unit.title()));
        List<String> addedElements = new ArrayList<>(), reusedElements = new ArrayList<>(), addedRelations = new ArrayList<>(), reusedRelations = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();
        elements.forEach(e -> {
            var existing = model.getElements().stream().filter(item -> item.getId().equals(e.id())).findFirst();
            if (existing.isEmpty()) addedElements.add(e.id());
            else {
                reusedElements.add(e.id());
                if (!Objects.equals(existing.get().getTitle(),e.title())) differences.add(new Difference(e.id(),"title",existing.get().getTitle(),e.title()));
                if (!Objects.equals(existing.get().getDescription(),e.description())) differences.add(new Difference(e.id(),"description",existing.get().getDescription(),e.description()));
                var currentResponsibility = responsibility(e.id(), existing.get().getExtensions(), organizationNames);
                if (currentResponsibility != null && !currentResponsibility.equals(e.responsibility())) {
                    differences.add(new Difference(e.id(), "responsibility", responsibilityLabel(currentResponsibility),
                            responsibilityLabel(e.responsibility())));
                }
            }
        });
        relations.forEach(r -> {
            boolean exists = model.getRelations().stream().anyMatch(e -> r.sourceId().equals(e.getSourceId()) && r.relationType().equals(e.getRelationType()) && r.targetId().equals(e.getTargetId()));
            (exists ? reusedRelations : addedRelations).add(r.id());
        });
        boolean existing = existingView.isPresent();
        List<String> previewParts = new ArrayList<>(Arrays.asList(viewId,title,requirementText,graphFingerprint,
                request.context().toString(),request.metadata().toString()));
        for (var element : elements) {
            var reference = element.catalogueReference();
            if (reference != null)
                previewParts.addAll(Arrays.asList("catalogue-entry", element.id(), reference.uuid(), reference.titleEn(), reference.titleDe()));
        }
        new TreeMap<>(adoption.provenance()).forEach((key,value) -> previewParts.addAll(List.of(key,value)));
        return new Prepared(adoption,new Summary(List.copyOf(addedElements),List.copyOf(reusedElements),List.copyOf(addedRelations),
                List.copyOf(reusedRelations),List.copyOf(differences),List.of()),existing,hash(previewParts));
    }

    private static List<SnapshotEvidence> frozenEvidence(List<RequirementRelationshipView> relations, String requirement,
                                                       Map<String, String> sheets) {
        List<SnapshotEvidence> result = new ArrayList<>();
        for (var relation : relations) {
            var key = new RelationKey(relation.getSourceCode(), relation.getRelationType(), relation.getTargetCode());
            var origin = relation.getOrigin() == null ? null : relation.getOrigin().name();
            var confidence = relation.getRecordedConfidence();
            Map<String, String> fields = new TreeMap<>();
            put(fields, "x-analysis-derivation", relation.getDerivationReason());
            put(fields, "x-analysis-presence", relation.getPresenceReason());
            put(fields, "x-analysis-included-because", relation.getIncludedBecause());
            put(fields, "x-analysis-seed-type", Objects.toString(relation.getSeedType(), null));
            var witnesses = relation.getRequirementEvidence();
            if ((long) result.size() + Math.max(1, witnesses.size()) > SnapshotEvidence.MAX_RECORDS)
                throw SnapshotEvidence.invalid("At most 10000 evidence records can be adopted");
            if (witnesses.isEmpty()) {
                if (origin != null || confidence != null || !fields.isEmpty()) {
                    put(fields, "summary", Optional.ofNullable(relation.getDerivationReason()).orElse(
                            relation.getPresenceReason() != null ? relation.getPresenceReason() : relation.getIncludedBecause()));
                    result.add(new SnapshotEvidence(key, origin, confidence, fields));
                }
                continue;
            }
            for (var witness : witnesses) {
                validateWitness(key, witness, requirement, sheets);
                var scoped = new TreeMap<>(fields);
                var assessed = witness.contribution();
                var decision = witness.evidence();
                put(scoped, "summary", decision.rationale());
                put(scoped, "x-analysis-assessed-id", assessed.source().id());
                put(scoped, "x-analysis-assessed-contribution", assessed.text());
                put(scoped, "x-analysis-assessed-quote", assessed.quote());
                put(scoped, "x-analysis-assessed-condition", assessed.condition());
                put(scoped, "x-analysis-candidate-id", decision.targetId());
                put(scoped, "x-analysis-candidate-contribution", decision.contribution());
                put(scoped, "x-analysis-candidate-quote", decision.quote());
                put(scoped, "x-analysis-candidate-condition", decision.condition());
                put(scoped, "x-analysis-direction", witness.direction().name());
                put(scoped, "x-analysis-outcome", decision.outcome().name());
                put(scoped, "x-analysis-necessity", decision.necessity().name());
                put(scoped, "x-analysis-alternative-group", decision.alternativeGroup());
                put(scoped, "x-analysis-question", decision.question());
                result.add(new SnapshotEvidence(key, origin, confidence, scoped));
            }
        }
        return result;
    }

    private static void validateWitness(RelationKey key, RelationSearchModel.Edge witness, String requirement,
                                        Map<String, String> sheets) {
        if (witness == null || witness.direction() == null || witness.contribution() == null || witness.target() == null
                || witness.evidence() == null || !key.relationType().equals(witness.type())
                || !key.sourceId().equals(witness.sourceId()) || !key.targetId().equals(witness.targetId()))
            throw SnapshotEvidence.invalid("Witness does not identify the same directed snapshot relation");
        var assessed = witness.contribution().source();
        var candidate = witness.target();
        var decision = witness.evidence();
        if (assessed.container() || candidate.container() || assessed.id().equals(candidate.id())
                || !assessed.root().equals(sheets.get(assessed.id())) || !candidate.root().equals(sheets.get(candidate.id()))
                || !candidate.id().equals(decision.targetId()) || decision.outcome() != RelationSearchModel.Outcome.VERIFIED
                || decision.necessity() == null || decision.contribution() == null || decision.contribution().isBlank()
                || decision.quote() == null || decision.quote().isBlank() || requirement == null
                || !requirement.contains(witness.contribution().quote()) || !requirement.contains(decision.quote()))
            throw SnapshotEvidence.invalid("Witness does not match the recorded endpoints, decision and exact requirement quotes");
    }

    private static SnapshotResponsibility responsibility(String code, Map<String, String> fields, Map<String, String> names) {
        String id = fields.get(ArchitectureOrganization.RESPONSIBLE_ID);
        String name = id == null ? null : names.get(id);
        if (name != null) return new SnapshotResponsibility(id, name, code);
        String legacy = fields.get("x-responsible-organization");
        if (legacy != null && !legacy.isBlank()) return new SnapshotResponsibility("legacy:" + legacy, legacy, code);
        String captured = fields.get(ArchitectureOrganization.CAPTURED_NAME);
        String unit = fields.get(ArchitectureOrganization.CAPTURED_UNIT);
        String source = fields.get(ArchitectureOrganization.CAPTURED_SOURCE);
        return captured == null || unit == null || source == null ? null : new SnapshotResponsibility(unit, captured, source);
    }

    private static String responsibilityLabel(SnapshotResponsibility responsibility) {
        return responsibility == null ? null : responsibility.name() + " (" + responsibility.unitId() + ") · " + responsibility.sourceId();
    }
    private UserWorkspace owned(String workspaceId,String username,String repositoryId) {
        var workspace = workspaces.findByWorkspaceId(workspaceId).orElseThrow(() -> problem("NOT_FOUND","Workspace is not accessible"));
        if (workspace.isShared() || workspace.isArchived() || !repositoryId.equals(workspace.getSourceRepositoryId())
                || (!actorAccess.owns(username, workspace) && !(workspace.isCollaborative()
                && membership.canCollaborate(repositories.getRepository(repositoryId), username))))
            throw problem("NOT_FOUND","Workspace is not accessible");
        if (workspace.isCollaborative()) actorAccess.requireActive(username);
        return workspace;
    }
    private static void put(Map<String,String> map,String key,String value) { if (value != null) map.put(key,value); }
    private static String hash(List<String> parts) {
        try {
            var bytes = new ByteArrayOutputStream(); var stream = new DataOutputStream(bytes);
            for (String value : parts) {
                if (value == null) stream.writeInt(-1);
                else {
                    SnapshotEvidence.requireUtf8(value);
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    stream.writeInt(encoded.length);
                    stream.write(encoded);
                }
            }
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static CommandProblem problem(String code,String message) { return new CommandProblem(code,"source",message,List.of()); }
}
