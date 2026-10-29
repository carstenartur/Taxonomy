package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
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

    public AnalysisSnapshotAdoptionService(ArchitectureEditorService editor, PortfolioAnalysisPersistenceService snapshots,
            ProjectPortfolioService projects, UserWorkspaceRepository workspaces, SystemRepositoryService repositories,
            RepositoryMembershipService membership) {
        this.editor = editor; this.snapshots = snapshots; this.projects = projects;
        this.workspaces = workspaces; this.repositories = repositories; this.membership = membership;
    }

    public record SourceRef(String repositoryId, String workspaceScopeKey, String branch, Long projectId,
                            Long requirementId, Long requirementVersionId, String snapshotId) {
        public SourceRef {
            for (String value : Arrays.asList(repositoryId,workspaceScopeKey,branch,snapshotId))
                if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("An explicit bounded source repository, workspace scope, branch and snapshot are required");
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

    private Prepared prepare(RepositoryContext target, Request request) throws IOException {
        SourceRef source = request.source();
        if (target.scope() != RepositoryScope.WORKSPACE) throw problem("READ_ONLY", "Adoption requires a private editable workspace");
        var targetWorkspace = owned(target.workspaceId(), target.username(), target.repositoryId());
        if (targetWorkspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY)
            throw problem("READ_ONLY", "Target workspace is not ready");
        if (!target.branch().equals(targetWorkspace.getCurrentBranch()) || !source.repositoryId().equals(target.repositoryId()))
            throw problem("NOT_FOUND", "The exact source and target repository are not accessible");
        if (!Context.of(target,request.context().commit(),request.context().revision()).equals(request.context()))
            throw problem("CONTEXT_CHANGED", "The target context changed");
        String sourceWorkspace = WorkspaceOverlayScope.CENTRAL_SCOPE_KEY.equals(source.workspaceScopeKey()) ? null : source.workspaceScopeKey();
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
        var version = projects.listRequirementVersions(source.projectId(),source.requirementId(),target.username(),sourceContext)
                .stream().filter(candidate -> source.requirementVersionId().equals(candidate.id())).findFirst()
                .orElseThrow(() -> problem("NOT_FOUND", "The exact retained requirement version is unavailable"));
        var graph = snapshot.analysis() == null ? null : snapshot.analysis().getArchitectureView();
        if (graph == null || graph.getIncludedElements() == null || graph.getIncludedRelationships() == null)
            throw problem("INVALID_SNAPSHOT", "Snapshot contains no retained full architecture graph");
        if (graph.getIncludedElements().isEmpty() || graph.getIncludedElements().size() > 2000 || graph.getIncludedRelationships().size() > 10000)
            throw problem("ITEM_LIMIT", "Snapshot graph exceeds adoption limits");
        List<SnapshotElement> elements = graph.getIncludedElements().stream().map(element -> {
            if (element == null || !TaxonomyRootTypes.ROOT_TO_TYPE.containsKey(element.getTaxonomySheet()))
                throw problem("UNKNOWN_TYPE", "Snapshot has unsupported or missing recorded taxonomy type");
            // Recorded root plus recorded code establish identity; do not synthesize nodes from renderer containers.
            String root = TaxonomyRootTypes.rootFromId(element.getNodeCode());
            if (!Objects.equals(root,element.getTaxonomySheet()) && !Objects.equals(element.getNodeCode(),element.getTaxonomySheet()))
                throw problem("AMBIGUOUS_SNAPSHOT_IDENTITY", "Recorded element identity and taxonomy type disagree");
            return new SnapshotElement(element.getNodeCode(),TaxonomyRootTypes.typeFor(element.getTaxonomySheet()),
                    element.getTitle(),Objects.toString(element.getPresenceReason(), Objects.toString(element.getIncludedBecause(),"")));
        }).sorted(Comparator.comparing(SnapshotElement::id,Comparator.nullsFirst(String::compareTo))).toList();
        List<RelationKey> relations = graph.getIncludedRelationships().stream().map(relation -> {
            if (relation == null || relation.getSourceCode() == null || relation.getTargetCode() == null || relation.getRelationType() == null)
                throw problem("INVALID_SNAPSHOT", "Snapshot relation identity is incomplete");
            return new RelationKey(relation.getSourceCode(),relation.getRelationType(),relation.getTargetCode());
        }).sorted(Comparator.comparing(RelationKey::id)).toList();
        List<String> graphParts = new ArrayList<>();
        elements.forEach(e -> graphParts.addAll(Arrays.asList("element",e.id(),e.type(),e.title(),e.description())));
        relations.forEach(r -> graphParts.addAll(List.of("relation",r.sourceId(),r.relationType(),r.targetId())));
        String graphFingerprint = hash(graphParts);
        String viewId = "analysis-" + hash(Arrays.asList(source.repositoryId(),source.workspaceScopeKey(),source.branch(),
                source.projectId().toString(),source.requirementId().toString(),source.requirementVersionId().toString(),source.snapshotId()));
        Map<String,String> provenance = new TreeMap<>();
        provenance.put("x-analysis-repository-id",source.repositoryId()); provenance.put("x-analysis-workspace-scope",source.workspaceScopeKey());
        provenance.put("x-analysis-branch",source.branch()); provenance.put("x-analysis-project-id",source.projectId().toString());
        provenance.put("x-analysis-requirement-id",source.requirementId().toString()); provenance.put("x-analysis-requirement-version-id",source.requirementVersionId().toString());
        provenance.put("x-analysis-snapshot-id",source.snapshotId()); provenance.put("x-analysis-requirement-text-hash",StableIdentityHash.sha256(version.text()));
        put(provenance,"x-analysis-commit",summary.commitSha()); put(provenance,"x-analysis-catalogue-fingerprint",summary.taxonomyFingerprint());
        put(provenance,"x-analysis-provider",summary.provider()); put(provenance,"x-analysis-model",summary.modelName());
        put(provenance,"x-analysis-status",summary.status().name()); put(provenance,"x-analysis-created-at",Objects.toString(summary.createdAt(),null));
        String title = graph.getViewTitle() == null || graph.getViewTitle().isBlank() ? "Analysis " + summary.requirementKey() : graph.getViewTitle();
        var adoption = new AdoptAnalysisSnapshot(viewId,title,version.text(),graphFingerprint,elements,relations,provenance);
        var document = editor.read(target,null);
        var model = new ArchitectureDslCommands().model(document.dsl());
        List<String> addedElements = new ArrayList<>(), reusedElements = new ArrayList<>(), addedRelations = new ArrayList<>(), reusedRelations = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();
        elements.forEach(e -> {
            var existing = model.getElements().stream().filter(item -> item.getId().equals(e.id())).findFirst();
            if (existing.isEmpty()) addedElements.add(e.id());
            else {
                reusedElements.add(e.id());
                if (!Objects.equals(existing.get().getTitle(),e.title())) differences.add(new Difference(e.id(),"title",existing.get().getTitle(),e.title()));
                if (!Objects.equals(existing.get().getDescription(),e.description())) differences.add(new Difference(e.id(),"description",existing.get().getDescription(),e.description()));
            }
        });
        relations.forEach(r -> {
            boolean exists = model.getRelations().stream().anyMatch(e -> r.sourceId().equals(e.getSourceId()) && r.relationType().equals(e.getRelationType()) && r.targetId().equals(e.getTargetId()));
            (exists ? reusedRelations : addedRelations).add(r.id());
        });
        boolean existing = model.getViews().stream().anyMatch(v -> v.getId().equals(viewId));
        List<String> previewParts = new ArrayList<>(Arrays.asList(viewId,title,version.text(),graphFingerprint,
                request.context().toString(),request.metadata().toString()));
        provenance.forEach((key,value) -> previewParts.addAll(List.of(key,value)));
        return new Prepared(adoption,new Summary(List.copyOf(addedElements),List.copyOf(reusedElements),List.copyOf(addedRelations),
                List.copyOf(reusedRelations),List.copyOf(differences),List.of()),existing,hash(previewParts));
    }
    private UserWorkspace owned(String workspaceId,String username,String repositoryId) {
        var workspace = workspaces.findByWorkspaceId(workspaceId).orElseThrow(() -> problem("NOT_FOUND","Workspace is not accessible"));
        if (!username.equals(workspace.getUsername()) || workspace.isShared() || workspace.isArchived() || !repositoryId.equals(workspace.getSourceRepositoryId()))
            throw problem("NOT_FOUND","Workspace is not accessible");
        return workspace;
    }
    private static void put(Map<String,String> map,String key,String value) { if (value != null) map.put(key,value); }
    private static String hash(List<String> parts) {
        try {
            var bytes = new ByteArrayOutputStream(); var stream = new DataOutputStream(bytes);
            for (String value : parts) {
                if (value == null) stream.writeInt(-1);
                else { byte[] encoded = value.getBytes(StandardCharsets.UTF_8); stream.writeInt(encoded.length); stream.write(encoded); }
            }
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static CommandProblem problem(String code,String message) { return new CommandProblem(code,"source",message,List.of()); }
}
