package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Dedicated append-only proposal aggregate; never calls addRequirementVersion or analysis. */
@Service
public class ReformulationService {
    private final ProjectPortfolioService projects;
    private final ProjectRequirementVersionRepository versions;
    private final RequirementAnalysisSnapshotRepository snapshots;
    private final PortfolioAnalysisPersistenceService analysis;
    private final ReformulationBaselineContextPort contextPort;
    private final ReformulationProposalRepository proposals;
    private final ReformulationRevisionRepository revisions;
    private final PortfolioJsonCodec json;
    public ReformulationService(ProjectPortfolioService projects,ProjectRequirementVersionRepository versions,
            RequirementAnalysisSnapshotRepository snapshots,PortfolioAnalysisPersistenceService analysis,
            ReformulationBaselineContextPort contextPort,ReformulationProposalRepository proposals,
            ReformulationRevisionRepository revisions,PortfolioJsonCodec json) {
        this.projects=projects;this.versions=versions;this.snapshots=snapshots;this.analysis=analysis;
        this.contextPort=contextPort;this.proposals=proposals;this.revisions=revisions;this.json=json;
    }
    @Transactional
    public Proposal create(Long projectId,Long requirementId,CreateRequest request,String actor,WorkspaceContext context) {
        var requirement=projects.requireRequirement(projectId,requirementId,actor,context);
        if(request==null || request.sourceVersionId()==null || request.snapshotId()==null || request.snapshotId().isBlank())
            throw PortfolioException.validation("sourceVersionId and snapshotId are required");
        var version=versions.findByIdAndRequirementIdAndScopeKey(request.sourceVersionId(),requirementId,requirement.getScopeKey())
                .orElseThrow(()->PortfolioException.notFound("Source version not found in requirement"));
        var snapshot=snapshots.findByIdAndProjectIdAndScopeKey(request.snapshotId(),projectId,requirement.getScopeKey())
                .orElseThrow(()->PortfolioException.notFound("Snapshot not found in project"));
        if(!snapshot.getRequirementId().equals(requirementId) || !snapshot.getRequirementVersionId().equals(version.getId()))
            throw PortfolioException.conflict("Snapshot must match the selected requirement version");
        String language=request.language()==null?"de":request.language();
        if(!language.equals("de") && !language.equals("en")) throw PortfolioException.validation("language must be de or en");
        var detail=analysis.getSnapshot(projectId,snapshot.getId(),actor,context);
        var content=new TreeMap<>(contextPort.freeze(detail,actor,context));
        content.put("project",json.write(projects.getProject(projectId,actor,context)));
        content.put("sourceVersion",json.write(projects.toVersionView(version)));
        var scope=new ReformulationBaseline.Scope(PortfolioScope.repositoryId(context),PortfolioScope.workspaceId(context),
                PortfolioScope.branch(context),projectId,requirementId);
        var baseline=ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope,version.getId(),version.getText()),
                new ReformulationBaseline.Snapshot(scope,snapshot.getId(),snapshot.getRequirementVersionId(),snapshot.getAnalysisPayload()),
                content,language,"reformulation-v1");
        String id=UUID.randomUUID().toString();Instant now=Instant.now();String author=PortfolioScope.username(actor,context);
        var proposal=new ReformulationProposal(id,requirement.getScopeKey(),projectId,requirementId,version.getId(),snapshot.getId(),json.write(baseline),author,now);
        proposals.saveAndFlush(proposal);
        // The initial draft deliberately preserves the original verbatim, with no generated claims.
        saveRevision(proposal,version.getText(),"Initial source-preserving draft",author,now);
        return view(proposal);
    }
    @Transactional(readOnly=true)
    public Proposal get(Long projectId,Long requirementId,String id,String actor,WorkspaceContext context) {
        return view(require(projectId,requirementId,id,actor,context,false));
    }
    @Transactional(readOnly=true)
    public List<Proposal> list(Long projectId,Long requirementId,String actor,WorkspaceContext context) {
        var requirement=projects.requireRequirement(projectId,requirementId,actor,context);
        return proposals.findByProjectIdAndRequirementIdAndScopeKeyOrderByCreatedAtDesc(projectId,requirementId,requirement.getScopeKey()).stream().map(this::view).toList();
    }
    @Transactional(readOnly=true)
    public Revision revision(Long projectId,Long requirementId,String id,long number,String actor,WorkspaceContext context) {
        return readRevision(require(projectId,requirementId,id,actor,context,false),number);
    }
    @Transactional
    public Proposal saveDraft(Long projectId,Long requirementId,String id,long expectedRevision,SaveDraftRequest request,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        if(proposal.getCurrentRevision()!=expectedRevision) throw new ReformulationPreconditionException();
        if(request==null || request.text()==null || request.text().isBlank() || request.rationale()==null || request.rationale().isBlank())
            throw PortfolioException.validation("Draft text and rationale are required");
        if(request.rationale().length()>1000) throw PortfolioException.validation("Rationale exceeds 1000 characters");
        proposal.advanceRevision();
        saveRevision(proposal,request.text(),request.rationale(),PortfolioScope.username(actor,context),Instant.now());
        return view(proposal);
    }
    private void saveRevision(ReformulationProposal proposal,String text,String rationale,String actor,Instant now) {
        long number=proposal.getCurrentRevision();
        var original=json.read(proposal.getBaselinePayload(),ReformulationBaseline.class).originalText();
        var spans=original.isEmpty()?List.<Statement.SourceSpan>of():List.of(new Statement.SourceSpan(0,original.length(),original));
        var validation=new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,
                "NOT_SYNTHESIZED","Source coverage has not been evaluated",List.of(),spans)));
        var revision=new Revision(number,number==1?null:number-1,text,List.of(),List.of(),List.of(),List.of(),validation,actor,now,rationale);
        revisions.save(new ReformulationRevision(proposal.getId(),proposal.getScopeKey(),number,json.write(revision)));
    }
    private ReformulationProposal require(Long projectId,Long requirementId,String id,String actor,WorkspaceContext context,boolean lock) {
        projects.requireRequirement(projectId,requirementId,actor,context);
        String scope=PortfolioScope.key(actor,context);
        return (lock?proposals.lockScoped(id,projectId,requirementId,scope):proposals.findByIdAndProjectIdAndRequirementIdAndScopeKey(id,projectId,requirementId,scope))
                .orElseThrow(()->PortfolioException.notFound("Reformulation proposal not found"));
    }
    private Revision readRevision(ReformulationProposal proposal,long number) {
        return json.read(revisions.findByProposalIdAndNumberAndScopeKey(proposal.getId(),number,proposal.getScopeKey())
                .orElseThrow(()->PortfolioException.notFound("Proposal revision not found")).getPayload(),Revision.class);
    }
    private Proposal view(ReformulationProposal proposal) {
        return new Proposal(proposal.getId(),json.read(proposal.getBaselinePayload(),ReformulationBaseline.class),proposal.getCreatedBy(),proposal.getCreatedAt(),"SAVED",readRevision(proposal,proposal.getCurrentRevision()));
    }
}
