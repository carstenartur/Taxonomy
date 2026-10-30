package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.requirements.RequirementTrace;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.taxonomy.composition.architecture.ArchitectureLifecycleService.*;

/** An explicit user choice creates or changes one requirement and journals the exact contribution atomically. */
@Service
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
public class ArchitectureRequirementService {
    private final ArchitectureEditorService editor;
    private final ArchitectureLifecycleService lifecycle;
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;
    private final TransactionTemplate transaction;

    public ArchitectureRequirementService(ArchitectureEditorService editor,ArchitectureLifecycleService lifecycle,
            ProjectPortfolioService projects,PortfolioGitService portfolio,PlatformTransactionManager transactions) {
        this.editor=editor;this.lifecycle=lifecycle;this.projects=projects;this.portfolio=portfolio;
        this.transaction=new TransactionTemplate(transactions);
    }
    public record Request(Context context,Metadata metadata,String choice,Long projectId,CreateProjectRequest newProject,
            Long contributorProjectId,Long requirementId,CreateRequirementRequest requirement,Long expectedVersionId,
            String expectedContentHash,String inputHash) {
        public Request {
            Objects.requireNonNull(context); Objects.requireNonNull(metadata); Objects.requireNonNull(requirement);
            if(!Set.of("NEW","CHANGE","LINK").contains(Objects.toString(choice,""))) throw new IllegalArgumentException("Choose NEW, CHANGE or LINK explicitly");
            if(requirement.text()==null || !StableIdentityHash.sha256(requirement.text()).equals(inputHash))
                throw new IllegalArgumentException("The reviewed intake text changed");
            if("NEW".equals(choice)) {
                if((projectId==null)==(newProject==null)) throw new IllegalArgumentException("Choose an existing or a new project");
                if(requirementId!=null) throw new IllegalArgumentException("A new requirement cannot replace an existing identity");
            } else if(projectId==null || requirementId==null || expectedVersionId==null || expectedContentHash==null)
                throw new IllegalArgumentException("An exact existing project, requirement and text version are required");
        }
    }
    public record Saved(Long projectId,Long requirementId,RequirementView requirement,Publication publication) {}

    public Saved save(RepositoryContext context,Request request) throws IOException {
        lifecycle.authorize(context,true); requireContext(context,request.context());
        Metadata checkpoint=checkpointMetadata(request.metadata());
        String intent=fingerprint(request.context(),request.metadata(),request.choice(),request.projectId(),request.newProject(),
                request.contributorProjectId(),request.requirementId(),request.requirement(),request.expectedVersionId(),request.expectedContentHash(),request.inputHash());
        AtomicReference<RequirementView> changed=new AtomicReference<>();
        Context accepted;
        try {
            accepted=transaction.execute(status -> {
                try { return editor.acceptIntegration(context,request.context(),request.metadata(),intent,List.of(),source -> {
                    lifecycle.lockProjects(context,request.projectId(),request.contributorProjectId());
                    var scope=workspace(context);
                    Long projectId=request.projectId();
                    if(projectId==null) projectId=projects.createProject(request.newProject(),context.username(),scope).id();
                    var project=projects.getProject(projectId,context.username(),scope);
                    RequirementView result;
                    if("NEW".equals(request.choice())) result=projects.createRequirement(projectId,request.requirement(),context.username(),scope);
                    else {
                        result=projects.getRequirement(projectId,request.requirementId(),context.username(),scope);
                        requireVersion(result,request.expectedVersionId(),request.expectedContentHash());
                        if("CHANGE".equals(request.choice())) {
                            var input=request.requirement();
                            projects.addRequirementVersion(projectId,result.id(),new CreateRequirementVersionRequest(input.text(),input.changeReason(),input.source()),context.username(),scope);
                            if(input.title()!=null && !input.title().isBlank()) projects.updateRequirement(projectId,result.id(),
                                    new UpdateRequirementRequest(input.title(),null,null,null,null,null,null),context.username(),scope);
                            result=projects.getRequirement(projectId,result.id(),context.username(),scope);
                        }
                    }
                    Long contributorId=request.contributorProjectId();
                    if(!"NEW".equals(request.choice()) && request.newProject()!=null) {
                        if(contributorId!=null) throw new IllegalArgumentException("Choose one contribution project");
                        contributorId=projects.createProject(request.newProject(),context.username(),scope).id();
                    }
                    var contributor=contributorId==null?project:projects.getProject(contributorId,context.username(),scope);
                    String next=portfolio.contributeTo(source,context.username(),scope);
                    String id=portfolio.planRequirementIdentity(project.projectKey(),result.requirementKey(),next,context.username(),scope);
                    next=RequirementTrace.contribute(next,id,contributor.projectKey());
                    changed.set(result);
                    return next;
                },checkpoint); } catch(IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch(UncheckedIOException failure) { throw failure.getCause(); }
        // A transport retry returns the original durable operation; its callback deliberately does not run again.
        RequirementView result=changed.get();
        if(result==null) {
            Long projectId=request.projectId();
            if(projectId==null) projectId=projects.listProjects(context.username(),workspace(context)).stream()
                    .filter(p->p.projectKey().equalsIgnoreCase(request.newProject().projectKey())).map(ProjectView::id).findFirst()
                    .orElseThrow(()->problem("NOT_FOUND","The retained project is unavailable"));
            if(request.requirementId()!=null) result=projects.getRequirement(projectId,request.requirementId(),context.username(),workspace(context));
            else result=projects.listRequirements(projectId,context.username(),workspace(context)).stream()
                    .filter(r->r.requirementKey().equalsIgnoreCase(request.requirement().requirementKey())).findFirst()
                    .orElseThrow(()->problem("NOT_FOUND","The retained requirement is unavailable"));
        }
        return new Saved(result.projectId(),result.id(),result,lifecycle.finish(context,accepted,checkpoint));
    }
}
