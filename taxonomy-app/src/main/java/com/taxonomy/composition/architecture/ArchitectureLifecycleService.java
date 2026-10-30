package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.dsl.requirements.RequirementTrace;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** The persistent architecture owns its history; projects contribute version-bound requirements. */
@Service
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
public class ArchitectureLifecycleService {
    private final ArchitectureEditorService editor;
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;
    private final UserWorkspaceRepository workspaces;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService membership;
    private final TransactionTemplate transaction;

    public ArchitectureLifecycleService(ArchitectureEditorService editor, ProjectPortfolioService projects,
            PortfolioGitService portfolio, UserWorkspaceRepository workspaces, SystemRepositoryService repositories,
            RepositoryMembershipService membership, PlatformTransactionManager transactions) {
        this.editor=editor; this.projects=projects; this.portfolio=portfolio; this.workspaces=workspaces;
        this.repositories=repositories; this.membership=membership; this.transaction=new TransactionTemplate(transactions);
    }

    public record Project(Long id, String projectKey, String title, String status) {}
    public record Requirement(String id, Long projectId, String projectKey, Long requirementId, String requirementKey,
            String title, int version, Long versionId, String contentHash, String text,
            RequirementRealization.Assessment realization, List<RequirementTrace.Contribution> contributors,
            List<RequirementTrace.Link> links) {}
    public record Dependency(String id, String kind, String title) {}
    public record Overview(Context context, String architectureTitle, String source, String projectionState,
            String publicationState, boolean mayEdit, List<Project> projects, List<Requirement> requirements,
            List<Dependency> dependencies) {
        public Overview readOnly() { return new Overview(context,architectureTitle,source,projectionState,
                publicationState,false,projects,requirements,dependencies); }
    }
    public record Request(Context context, Metadata metadata, Long projectId, Long requirementId,
            Long expectedVersionId, String expectedContentHash, Long contributorProjectId, String status,
            List<String> dependencyIds, String previewFingerprint) {
        public Request {
            Objects.requireNonNull(context); Objects.requireNonNull(metadata);
            if(projectId==null || requirementId==null || expectedVersionId==null || expectedContentHash==null)
                throw new IllegalArgumentException("An exact requirement version is required");
            if(!Set.of("OPEN","PARTIAL","COMPLETE").contains(Objects.toString(status,"")))
                throw new IllegalArgumentException("Unknown realization status");
            dependencyIds=dependencyIds==null ? List.of() : List.copyOf(new TreeSet<>(dependencyIds));
            if(dependencyIds.size()>1000) throw new IllegalArgumentException("Too many dependencies");
        }
    }
    public record ReviewPreview(Context context, String requirementId, String fingerprint,
            RequirementRealization.Assessment realization, List<ArchitectureSemanticPatch.BlockChange> changes) {}
    public record Publication(Context context, String state, CheckpointAccepted checkpoint, String code, String detail) {}
    private record Prepared(String id,String fingerprint,String source,RequirementRealization.Assessment realization) {}

    public Overview overview(RepositoryContext context, String commit, Long revision) throws IOException {
        authorize(context,false);
        var document=editor.read(context,commit,revision);
        boolean live="WORKSPACE_REVISION".equals(document.source()) && !"HISTORICAL".equals(document.projectionState());
        String source=live ? portfolio.contributeTo(document.dsl(),context.username(),workspace(context)) : document.dsl();
        Map<String,BlockAst> blocks=ArchitectureSemanticPatch.index(source);
        Map<String,ProjectView> currentProjects=new HashMap<>();
        Map<String,RequirementView> currentRequirements=new HashMap<>();
        if(live) for(var project:projects.listProjects(context.username(),workspace(context))) {
            currentProjects.put(project.projectKey(),project);
            for(var requirement:projects.listRequirements(project.id(),context.username(),workspace(context)))
                currentRequirements.put(project.projectKey()+"\u0000"+requirement.requirementKey(),requirement);
        }
        List<Project> projectViews=new ArrayList<>();
        List<Requirement> requirementViews=new ArrayList<>();
        List<Dependency> dependencies=new ArrayList<>();
        for(var entry:blocks.entrySet()) {
            var block=entry.getValue(); String id=block.getHeaderTokens().getFirst();
            if("project".equals(block.getKind())) {
                var current=currentProjects.get(id);
                projectViews.add(new Project(current==null?null:current.id(),id,block.property("title"),block.property("status")));
            }
            if("requirement".equals(block.getKind())) {
                String projectKey=block.property("x-project-key"), requirementKey=block.property("x-requirement-key");
                var current=currentRequirements.get(projectKey+"\u0000"+requirementKey);
                requirementViews.add(new Requirement(id,current==null?null:current.projectId(),projectKey,
                        current==null?null:current.id(),requirementKey,block.property("title"),number(block.property("x-version")),
                        current==null?null:current.currentVersionId(),block.property("x-content-hash"),block.property("text"),
                        RequirementRealization.evaluate(blocks,id),RequirementTrace.contributions(block),RequirementTrace.links(block)));
            }
            if(Set.of("element","relation","mapping").contains(block.getKind()))
                dependencies.add(new Dependency(entry.getKey(),block.getKind(),Objects.toString(block.property("title"),String.join(" ",block.getHeaderTokens()))));
        }
        String publication=document.projectionState();
        if("READY".equals(publication)) publication=!source.equals(document.dsl())?"PORTFOLIO_CHANGES_PENDING"
                :document.context().revision()>document.checkpointRevision()?"CHECKPOINT_REQUIRED":"CHECKPOINTED";
        boolean mayEdit=live && "READY".equals(document.projectionState()) && context.scope()==RepositoryScope.WORKSPACE;
        return new Overview(document.context(),repositories.getRepository(context.repositoryId()).getDisplayName(),document.source(),
                document.projectionState(),publication,mayEdit,List.copyOf(projectViews),List.copyOf(requirementViews),List.copyOf(dependencies));
    }

    public ReviewPreview preview(RepositoryContext context, Request request) throws IOException {
        authorize(context,true); requireContext(context,request.context());
        var document=editor.read(context,null);
        requireStand(request.context(),document.context());
        if(!"READY".equals(document.projectionState())) throw problem("VERSION_CHANGED","Refresh or reconcile the current architecture first");
        Prepared prepared=prepare(context,request,document.dsl());
        return new ReviewPreview(document.context(),prepared.id(),prepared.fingerprint(),prepared.realization(),
                ArchitectureSemanticPatch.between(document.dsl(),prepared.source()));
    }

    public Publication accept(RepositoryContext context, Request request) throws IOException {
        authorize(context,true); requireContext(context,request.context());
        Metadata checkpoint=checkpointMetadata(request.metadata());
        String intent=fingerprint(request.context(),request.metadata(),request.projectId(),request.requirementId(),request.expectedVersionId(),
                request.expectedContentHash(),request.contributorProjectId(),request.status(),request.dependencyIds(),request.previewFingerprint());
        Context accepted;
        try {
            accepted=transaction.execute(status -> {
                try { return editor.acceptIntegration(context,request.context(),request.metadata(),intent,List.of(),source -> {
                    lockProjects(context,request.projectId(),request.contributorProjectId());
                    Prepared prepared=prepare(context,request,source);
                    if(!Objects.equals(prepared.fingerprint(),request.previewFingerprint()))
                        throw problem("PREVIEW_CHANGED","Preview this exact requirement, contribution and architecture stand again");
                    return prepared.source();
                },checkpoint); } catch(IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch(UncheckedIOException failure) { throw failure.getCause(); }
        return finish(context,accepted,checkpoint);
    }

    private Prepared prepare(RepositoryContext context, Request request,String source) {
        var scope=workspace(context);
        var requirement=projects.getRequirement(request.projectId(),request.requirementId(),context.username(),scope);
        requireVersion(requirement,request.expectedVersionId(),request.expectedContentHash());
        var project=projects.getProject(request.projectId(),context.username(),scope);
        var contributor=projects.getProject(request.contributorProjectId()==null?request.projectId():request.contributorProjectId(),context.username(),scope);
        String contributed=portfolio.contributeTo(source,context.username(),scope);
        String id=portfolio.planRequirementIdentity(project.projectKey(),requirement.requirementKey(),contributed,context.username(),scope);
        String fingerprint=fingerprint(request.context(),request.projectId(),request.requirementId(),request.expectedVersionId(),
                request.expectedContentHash(),contributor.projectKey(),request.status(),request.dependencyIds(),request.metadata().rationale(),contributed);
        List<String> dependencies=expandDependencies(contributed,request.dependencyIds());
        String changed=RequirementRealization.record(contributed,id,new RequirementRealization.Review(requirement.currentVersion().versionNumber(),
                requirement.currentVersion().contentHash(),request.status(),contributor.projectKey(),context.username(),Instant.now(),request.metadata().rationale(),dependencies));
        changed=RequirementTrace.contribute(changed,id,contributor.projectKey());
        return new Prepared(id,fingerprint,changed,RequirementRealization.evaluate(changed,id));
    }

    static List<String> expandDependencies(String source,List<String> selected) {
        var blocks=ArchitectureSemanticPatch.index(source); Set<String> expanded=new TreeSet<>();
        for(String id:selected) {
            var block=blocks.get(id);
            if(block==null || !Set.of("element","relation","mapping").contains(block.getKind()))
                throw problem("INVALID_DEPENDENCY","Select an existing architecture element, relation or mapping");
            expanded.add(id); var tokens=block.getHeaderTokens();
            if("relation".equals(block.getKind())) {
                expanded.add("element:"+tokens.getFirst()); expanded.add("element:"+tokens.getLast());
            } else if("mapping".equals(block.getKind())) expanded.add("element:"+tokens.getLast());
        }
        return List.copyOf(expanded);
    }

    public Publication finish(RepositoryContext context, Context accepted,Metadata checkpoint) {
        try {
            CheckpointAccepted receipt;
            try {
                // Prefer the operation's own durable receipt even if a later unrelated operation was published.
                receipt=editor.checkpoint(context,new CreateCheckpointCommand(accepted,checkpoint));
            } catch(CommandProblem failure) {
                if(!Set.of("VERSION_CHANGED","CONTEXT_CHANGED","CHECKPOINT_REJECTED","CHECKPOINT_CONFLICT").contains(failure.code())) throw failure;
                // An explicitly reconciled operation may have been published by the reconciliation checkpoint.
                var proven=editor.integrationCheckpointForOperation(context,checkpoint.causationId());
                if(proven.isEmpty()) return new Publication(accepted,"RECONCILIATION_REQUIRED",null,failure.code(),failure.getMessage());
                receipt=proven.get();
            }
            return new Publication(receipt.context(),"CHECKPOINTED",receipt,null,null);
        } catch(IOException failure) {
            return new Publication(accepted,"CHECKPOINT_PENDING",null,"GIT_UNAVAILABLE","The change is retained. Retry the same operation to finish its Git checkpoint.");
        }
    }

    public void authorize(RepositoryContext context,boolean write) {
        var repository=repositories.getRepository(context.repositoryId());
        if(!membership.canRead(repository,context.username()) || repository.getLifecycleState()!=RepositoryLifecycleState.ACTIVE)
            throw problem("NOT_FOUND","The selected architecture is unavailable");
        if(context.scope()==RepositoryScope.WORKSPACE) {
            var workspace=workspaces.findByWorkspaceId(context.workspaceId()).orElseThrow(()->problem("NOT_FOUND","Workspace unavailable"));
            if(workspace.isArchived() || workspace.isShared() || !context.username().equals(workspace.getUsername())
                    || !context.repositoryId().equals(workspace.getSourceRepositoryId())) throw problem("NOT_FOUND","Workspace unavailable");
            if(workspace.getProvisioningStatus()!=WorkspaceProvisioningStatus.READY) throw problem("READ_ONLY","Workspace is not ready");
            if(!context.branch().equals(workspace.getCurrentBranch())) throw problem("CONTEXT_CHANGED","Select the exact workspace branch");
        } else if(write) throw problem("READ_ONLY","Create a private working copy before editing");
    }
    void lockProjects(RepositoryContext context,Long... ids) {
        Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted()
                .forEach(id->projects.requireProjectForUpdate(id,context.username(),workspace(context)));
    }
    static void requireVersion(RequirementView requirement,Long versionId,String contentHash) {
        if(!Objects.equals(requirement.currentVersionId(),versionId) || requirement.currentVersion()==null
                || !Objects.equals(requirement.currentVersion().contentHash(),contentHash))
            throw problem("REQUIREMENT_VERSION_CHANGED","The requirement text changed; compare the current version before accepting");
    }
    static void requireContext(RepositoryContext actual,Context expected) {
        if(!Context.of(actual,expected.commit(),expected.revision()).equals(expected))
            throw problem("CONTEXT_CHANGED","The exact repository, workspace, branch or actor changed");
    }
    static void requireStand(Context expected,Context actual) {
        if(expected.revision()!=actual.revision()) throw new com.taxonomy.editor.persistence.EditorJournal.RevisionConflict(expected.revision(),actual.revision());
        if(!Objects.equals(expected.commit(),actual.commit())) throw problem("VERSION_CHANGED","The architecture checkpoint changed");
    }
    static WorkspaceContext workspace(RepositoryContext context) { return new WorkspaceContext(context.username(),context.workspaceId(),context.branch(),context.repositoryId()); }
    static Metadata checkpointMetadata(Metadata original) {
        String id=UUID.nameUUIDFromBytes(("architecture-lifecycle:"+original.commandId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new Metadata(id,original.correlationId(),original.commandId(),original.rationale());
    }
    static String fingerprint(Object... parts) {
        StringBuilder text=new StringBuilder();
        for(Object part:parts) {String value=Objects.toString(part,"<null>");text.append(value.length()).append(':').append(value);}
        return StableIdentityHash.sha256(text.toString());
    }
    static CommandProblem problem(String code,String detail) { return new CommandProblem(code,"lifecycle",detail,List.of()); }
    private static int number(String value) { try{return Integer.parseInt(value);}catch(RuntimeException failure){return 0;} }
}
