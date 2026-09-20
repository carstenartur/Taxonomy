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
    private final ReformulationRunRepository runs;
    public ReformulationService(ProjectPortfolioService projects,ProjectRequirementVersionRepository versions,
            RequirementAnalysisSnapshotRepository snapshots,PortfolioAnalysisPersistenceService analysis,
            ReformulationBaselineContextPort contextPort,ReformulationProposalRepository proposals,
            ReformulationRevisionRepository revisions,PortfolioJsonCodec json,ReformulationRunRepository runs) {
        this.projects=projects;this.versions=versions;this.snapshots=snapshots;this.analysis=analysis;
        this.runs=runs;this.contextPort=contextPort;this.proposals=proposals;this.revisions=revisions;this.json=json;
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
        var previous=readRevision(proposal,proposal.getCurrentRevision());
        proposal.advanceRevision();
        var statements=new ArrayList<>(previous.statements());
        statements.add(new Statement("human-"+UUID.randomUUID(),request.text(),List.of(),Statement.Provenance.HUMAN_DECISION,
                List.of(),List.of(),null,Statement.EditingOrigin.HUMAN,"UNREVIEWED"));
        var revision=new Revision(proposal.getCurrentRevision(),previous.number(),request.text(),previous.sections(),statements,
                previous.questions(),previous.answers(),previous.validation(),PortfolioScope.username(actor,context),Instant.now(),request.rationale());
        revisions.save(new ReformulationRevision(proposal.getId(),proposal.getScopeKey(),revision.number(),json.write(revision)));
        return view(proposal);
    }
    @Transactional
    public Run beginRun(Long projectId,Long requirementId,String id,long expectedRevision,String provider,String model,
            String promptVersion,String schemaVersion,String promptContent,String actor,WorkspaceContext context) {
        return beginRun(projectId,requirementId,id,expectedRevision,provider,model,promptVersion,schemaVersion,promptContent,Map.of(),actor,context);
    }
    @Transactional
    public Run beginRun(Long projectId,Long requirementId,String id,long expectedRevision,String provider,String model,
            String promptVersion,String schemaVersion,String promptContent,Map<String,String> reconcileContext,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        if(proposal.getCurrentRevision()!=expectedRevision) throw new ReformulationPreconditionException();
        var run=new Run(UUID.randomUUID().toString(),id,expectedRevision,"QUEUED",provider,model,promptVersion,schemaVersion,promptContent,
                null,null,null,PortfolioScope.username(actor,context),Instant.now(),reconcileContext);
        runs.saveAndFlush(new ReformulationRun(run.id(),id,proposal.getScopeKey(),json.write(run)));
        return run;
    }
    @Transactional(readOnly=true)
    public List<Run> runs(Long projectId,Long requirementId,String id,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,false);
        return runs.findByProposalIdAndScopeKey(id,proposal.getScopeKey()).stream().map(r->json.read(r.getPayload(),Run.class))
                .sorted(Comparator.comparing(Run::createdAt)).toList();
    }
    @Transactional
    public void running(Long projectId,Long requirementId,String id,String runId,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);var entity=run(proposal,runId);
        var old=json.read(entity.getPayload(),Run.class);
        if(!old.status().equals("QUEUED")) throw PortfolioException.conflict("Run is not queued");
        entity.setPayload(json.write(state(old,"RUNNING",null,null,null)));
    }
    @Transactional
    public void finishRun(Long projectId,Long requirementId,String id,String runId,ReformulationDocument document,String failureCode,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);var entity=run(proposal,runId);
        var old=json.read(entity.getPayload(),Run.class);
        if(!old.status().equals("RUNNING") && !old.status().equals("QUEUED")) return;
        if(document==null) {entity.setPayload(json.write(state(old,"FAILED",failureCode,null,null)));return;}
        var previous=readRevision(proposal,proposal.getCurrentRevision());
        boolean human=previous.statements().stream().anyMatch(s->s.editingOrigin()==Statement.EditingOrigin.HUMAN);
        if(previous.number()!=old.sourceRevision() || human) {
            entity.setPayload(json.write(state(old,"PARTIAL",human?"MANUAL_DRAFT_PROTECTED":"PROPOSAL_REVISION_CHANGED",null,document)));return;
        }
        if(!hasClosedReferences(document,previous,json.read(proposal.getBaselinePayload(),ReformulationBaseline.class))) {
            entity.setPayload(json.write(state(old,"PARTIAL","INVALID_REFERENCE_CLOSURE",null,document)));return;
        }
        proposal.advanceRevision();
        var revision=new Revision(proposal.getCurrentRevision(),previous.number(),document.text(),document.sections(),document.statements(),
                document.questions(),previous.answers(),document.validation(),old.actor(),Instant.now(),"Generated requirement offer; not adopted or approved");
        revisions.save(new ReformulationRevision(id,proposal.getScopeKey(),revision.number(),json.write(revision)));
        entity.setPayload(json.write(state(old,"COMPLETED",null,revision.number(),document)));
    }
    /** Publication boundary: references resolve within this exact immutable candidate revision. */
    private static boolean hasClosedReferences(ReformulationDocument document,Revision previous,ReformulationBaseline baseline) {
        var statements=new HashMap<String,Statement>();
        for(var statement:document.statements()) if(statements.put(statement.id(),statement)!=null) return false;
        var questions=new HashMap<String,DecisionQuestion>();
        for(var question:document.questions()) {
            if(question.id()==null || question.id().isBlank())return false;
            for(String ref:question.referenceIds())if(ref==null || ref.isBlank() || questions.put(ref,question)!=null)return false;
            for(String alias:question.aliases())if(question.origins().stream().noneMatch(o->o.id().equals(alias)))return false;
            for(var origin:question.origins()) {
                var previousForm=new DecisionQuestion(origin.id(),origin.key(),origin.wording(),origin.discoveries(),origin.affectedStatementIds(),origin.answerSchema(),origin.prerequisites(),origin.dependentQuestionIds(),origin.consequences(),origin.state());
                if(!question.retains(previousForm))return false;
            }
            for(var resolution:question.sourceResolutions()) {
                if(resolution.sourceSpans().isEmpty() || resolution.values().isEmpty() || resolution.rationale()==null || resolution.rationale().isBlank()
                        || resolution.sourceSpans().stream().anyMatch(span->!span.matches(baseline.originalText()))
                        || question.state()!=DecisionQuestion.State.ANSWERED && question.state()!=DecisionQuestion.State.CONFLICT)return false;
            }
        }
        var sections=new HashSet<String>();
        for(var section:document.sections()) if(section.id()==null || section.id().isBlank() || !sections.add(section.id())) return false;
        for(var statement:statements.values()) if(!questions.keySet().containsAll(statement.questionDependencies())) return false;
        for(var question:questions.values()) {
            if(!statements.keySet().containsAll(question.affectedStatementIds()) || !questions.keySet().containsAll(question.prerequisites())
                    || !questions.keySet().containsAll(question.dependentQuestionIds())) return false;
        }
        for(var section:document.sections()) {
            if(!sections.containsAll(section.children()) || !statements.keySet().containsAll(section.statementIds())
                    || !questions.keySet().containsAll(section.questionIds())) return false;
        }
        for(var answer:previous.answers()) if(!questions.containsKey(answer.questionId())) return false;
        for(var finding:document.validation().findings()) if(!statements.keySet().containsAll(finding.statementIds())) return false;
        for(var node:document.nodeResults()) {
            if(!statements.keySet().containsAll(node.preservedStatementIds()) || !questions.keySet().containsAll(node.preservedQuestionIds())) return false;
            for(var statement:node.statementProposals()) if(!statement.equals(statements.get(statement.id()))) return false;
            for(var question:node.questionProposals()) if(!question.equals(questions.get(question.id()))) return false;
            for(var finding:node.conflictCandidates()) if(!statements.keySet().containsAll(finding.statementIds())) return false;
        }
        // A retained question cannot silently change meaning or point at rewritten old evidence.
        var previousStatements=new HashMap<String,Statement>();previous.statements().forEach(s->previousStatements.put(s.id(),s));
        for(var evidence:previous.statements())if(!evidence.equals(statements.get(evidence.id())))return false;
        for(var question:previous.questions()) {
            var retained=questions.get(question.id());
            if(retained==null || !retained.retains(question)) return false;
            if(question.state()==DecisionQuestion.State.ANSWERED && retained.state()!=DecisionQuestion.State.ANSWERED && retained.state()!=DecisionQuestion.State.CONFLICT)return false;
            if(question.state()==DecisionQuestion.State.CONFLICT && retained.state()!=DecisionQuestion.State.CONFLICT)return false;
            for(String id:question.affectedStatementIds()) {
                var evidence=previousStatements.get(id);
                if(evidence==null || !evidence.equals(statements.get(id))) return false;
            }
        }
        return true;
    }
    private ReformulationRun run(ReformulationProposal proposal,String runId) {
        return runs.lockScoped(runId,proposal.getId(),proposal.getScopeKey()).orElseThrow(()->PortfolioException.notFound("Synthesis run not found"));
    }
    private static Run state(Run old,String status,String failure,Long revision,ReformulationDocument candidate) {
        return new Run(old.id(),old.proposalId(),old.sourceRevision(),status,old.provider(),old.model(),old.promptVersion(),old.schemaVersion(),old.promptContent(),failure,revision,candidate,old.actor(),old.createdAt(),old.reconcileContext());
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
