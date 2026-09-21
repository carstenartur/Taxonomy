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
    private final ReformulationCheckpointStore checkpoints;
    public ReformulationService(ProjectPortfolioService projects,
            ProjectRequirementVersionRepository versions,
            RequirementAnalysisSnapshotRepository snapshots,
            PortfolioAnalysisPersistenceService analysis,
            ReformulationBaselineContextPort contextPort,
            ReformulationProposalRepository proposals,
            ReformulationRevisionRepository revisions,
            PortfolioJsonCodec json,
            ReformulationRunRepository runs,
            ReformulationCheckpointStore checkpoints) {
        this.projects = projects;
        this.versions = versions;
        this.snapshots = snapshots;
        this.analysis = analysis;
        this.contextPort = contextPort;
        this.proposals = proposals;
        this.revisions = revisions;
        this.json = json;
        this.runs = runs;
        this.checkpoints = checkpoints;
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
    public List<ProposalSummary> list(Long projectId,Long requirementId,String actor,WorkspaceContext context) {
        var requirement=projects.requireRequirement(projectId,requirementId,actor,context);
        return proposals.findSummaries(projectId, requirementId, requirement.getScopeKey());
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
        var editQuestion=new DecisionQuestion("document-edit",new DecisionQuestion.Key("document","edit","GLOBAL"),"Human document edit",List.of(),
                previous.statements().stream().map(Statement::id).toList(),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"Recheck all sections",DecisionQuestion.State.ANSWERED);
        var impact=ReformulationQuestionService.impact(previous,editQuestion,json.read(proposal.getBaselinePayload(),ReformulationBaseline.class));
        var revision=new Revision(proposal.getCurrentRevision(),previous.number(),request.text(),previous.sections(),statements,
                previous.questions(),previous.answers(),previous.validation(),PortfolioScope.username(actor,context),Instant.now(),request.rationale(),impact,previous.variantOrigin());
        revisions.save(new ReformulationRevision(proposal.getId(),proposal.getScopeKey(),revision.number(),json.write(revision)));
        return view(proposal);
    }
    @Transactional
    public Proposal answer(Long projectId,Long requirementId,String id,long expected,AnswerRequest request,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        if(proposal.getCurrentRevision()!=expected) throw new ReformulationPreconditionException();
        var previous=readRevision(proposal,expected);
        var next=ReformulationQuestionService.answer(id,previous,request,PortfolioScope.username(actor,context),json.read(proposal.getBaselinePayload(),ReformulationBaseline.class));
        proposal.advanceRevision();
        revisions.save(new ReformulationRevision(id,proposal.getScopeKey(),next.number(),json.write(next)));
        return view(proposal);
    }
    @Transactional
    public Proposal statement(Long projectId,Long requirementId,String id,long expected,String statementId,StatementRequest request,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        if(proposal.getCurrentRevision()!=expected) throw new ReformulationPreconditionException();
        if(request==null)throw PortfolioException.validation("Statement operation required");
        ReformulationQuestionService.rationale(request.rationale());
        var previous=readRevision(proposal,expected);
        var old=previous.statements().stream().filter(s->s.id().equals(statementId)).findFirst().orElseThrow(()->PortfolioException.notFound("Statement not found"));
        boolean reject="REJECT".equals(request.action());
        if(!reject && !"EDIT".equals(request.action()))throw PortfolioException.validation("Invalid statement operation");
        if(reject && old.provenance()==Statement.Provenance.ORIGINAL)throw PortfolioException.validation("Original source cannot be rejected");
        if(!reject && (request.text()==null || request.text().isBlank()))throw PortfolioException.validation("Statement text required");
        var updated=new Statement(old.id(),reject?old.wording():request.text(),old.sourceSpans(),old.provenance(),old.architectureLinks(),
                old.questionDependencies(),old.conditionalValidity(),Statement.EditingOrigin.HUMAN,reject?"REJECTED":"UNREVIEWED");
        var statements=previous.statements().stream().map(s->s.id().equals(statementId)?updated:s).toList();
        var question=new DecisionQuestion("edit-"+statementId,new DecisionQuestion.Key(statementId,"edit","local"),"Human statement operation",List.of(),List.of(statementId),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"Review affected architecture",DecisionQuestion.State.ANSWERED);
        var impact=ReformulationQuestionService.impact(previous,question,json.read(proposal.getBaselinePayload(),ReformulationBaseline.class));
        // Recompose by stable IDs only when the entire flat layout can be proven and the
        // selected wording is unambiguous. Never replace matching substrings in manual prose.
        String canonical = String.join("\n\n", previous.statements().stream()
                .filter(s -> !"REJECTED".equals(s.reviewState())).map(Statement::wording).toList());
        int first = previous.text().indexOf(old.wording());
        boolean mapped = !"REJECTED".equals(old.reviewState()) && !old.wording().isEmpty()
                && previous.text().equals(canonical) && first >= 0
                && previous.text().indexOf(old.wording(), first + 1) < 0;
        var findings = new ArrayList<>(previous.validation().findings());
        findings.removeIf(f -> f.code().equals("STATEMENT_TEXT_CONFLICT")
                && f.statementIds().equals(List.of(statementId)));
        String text = previous.text();
        if (mapped) {
            text = String.join("\n\n", statements.stream()
                    .filter(s -> !"REJECTED".equals(s.reviewState())).map(Statement::wording).toList());
        } else {
            findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "STATEMENT_TEXT_CONFLICT",
                    "Statement changed by ID; document wording was retained because its mapping is ambiguous or manually edited",
                    List.of(statementId), old.sourceSpans()));
        }
        var next=new Revision(expected+1,expected,text,previous.sections(),statements,previous.questions(),previous.answers(),new ValidationReport(findings),
                PortfolioScope.username(actor,context),Instant.now(),request.rationale(),impact,previous.variantOrigin());
        proposal.advanceRevision();revisions.save(new ReformulationRevision(id,proposal.getScopeKey(),next.number(),json.write(next)));
        return view(proposal);
    }
    @Transactional
    public Proposal variant(Long projectId,Long requirementId,String id,long expected,VariantRequest request,String actor,WorkspaceContext context) {
        var original=require(projectId,requirementId,id,actor,context,true);
        if(original.getCurrentRevision()!=expected)throw new ReformulationPreconditionException();
        if(request==null)throw PortfolioException.validation("Variant rationale required");
        ReformulationQuestionService.rationale(request.rationale());
        var baseline=json.read(original.getBaselinePayload(),ReformulationBaseline.class);var previous=readRevision(original,expected);
        String author=PortfolioScope.username(actor,context);var now=Instant.now();
        var variant=new ReformulationProposal(UUID.randomUUID().toString(),original.getScopeKey(),projectId,requirementId,baseline.sourceVersionId(),baseline.snapshotId(),original.getBaselinePayload(),author,now);
        proposals.saveAndFlush(variant);
        var revision=new Revision(1,null,previous.text(),previous.sections(),previous.statements(),previous.questions(),previous.answers(),previous.validation(),author,now,request.rationale(),previous.impact(),new VariantOrigin(id,expected));
        revisions.save(new ReformulationRevision(variant.getId(),variant.getScopeKey(),1,json.write(revision)));
        return view(variant);
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
        boolean active=runs.findByProposalIdAndScopeKey(id,proposal.getScopeKey()).stream()
                .map(r->json.read(r.getPayload(),Run.class)).anyMatch(r->Set.of("QUEUED","RUNNING").contains(r.status()));
        if(active) throw PortfolioException.conflict("Cancel the active synthesis run before starting another run");
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
    /** Active-run authority is checked even on cache hits; no provider calls enter this transaction. */
    @Transactional
    public Optional<String> checkpoint(Long projectId,Long requirementId,String id,String runId,String kind,String fingerprint,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        requireActiveRun(proposal,runId);
        return checkpoints.lookup(proposal,kind,fingerprint);
    }
    @Transactional
    public String completeCheckpoint(Long projectId,Long requirementId,String id,String runId,String kind,String fingerprint,String payload,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        requireActiveRun(proposal,runId);
        return checkpoints.complete(proposal,runId,kind,fingerprint,payload);
    }
    private void requireActiveRun(ReformulationProposal proposal,String runId) {
        if(!"RUNNING".equals(json.read(run(proposal,runId).getPayload(),Run.class).status()))
            throw PortfolioException.conflict("REFORMULATION_RUN_NOT_ACTIVE");
    }
    /** Cancellation is explicit and idempotent. Late workers cannot publish or persist more checkpoints. */
    @Transactional
    public Run cancelRun(Long projectId,Long requirementId,String id,String runId,long expectedRevision,String actor,WorkspaceContext context) {
        var proposal=require(projectId,requirementId,id,actor,context,true);
        if(proposal.getCurrentRevision()!=expectedRevision) throw new ReformulationPreconditionException();
        var entity=run(proposal,runId);var old=json.read(entity.getPayload(),Run.class);
        if(!Set.of("QUEUED","RUNNING").contains(old.status())) return old;
        var cancelled=state(old,"CANCELLED",null,null,null);
        entity.recordCancellation(PortfolioScope.username(actor,context),Instant.now());
        entity.setPayload(json.write(cancelled));return cancelled;
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
                document.questions(),previous.answers(),document.validation(),old.actor(),Instant.now(),"Generated requirement offer; not adopted or approved",ReformulationImpact.empty(),previous.variantOrigin());
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
            for(var condition:question.answerSchema().applicability()) {
                var prerequisite=questions.get(condition.questionId());
                if(prerequisite==null || prerequisite.id().equals(question.id()) || !question.prerequisites().contains(condition.questionId()) || condition.anyOf().isEmpty())return false;
                var allowed=prerequisite.answerSchema().kind()==DecisionQuestion.AnswerSchema.Kind.BOOLEAN?List.of("true","false"):prerequisite.answerSchema().options();
                if(!allowed.containsAll(condition.anyOf()))return false;
            }
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
            if(question.state()==DecisionQuestion.State.NOT_APPLICABLE && retained.state()!=DecisionQuestion.State.NOT_APPLICABLE && retained.state()!=DecisionQuestion.State.CONFLICT)return false;
            if(question.state()==DecisionQuestion.State.DEFERRED && retained.state()!=DecisionQuestion.State.DEFERRED && retained.state()!=DecisionQuestion.State.CONFLICT
                    && !(retained.state()==DecisionQuestion.State.ANSWERED && !retained.sourceResolutions().isEmpty()))return false;
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
        // No particular source span was evaluated. The exact original is retained once in the baseline.
        var validation=new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,
                "NOT_SYNTHESIZED","Source coverage has not been evaluated",List.of(),List.of())));
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
