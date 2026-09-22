package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionDtos.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Sole proposal-to-version bridge. No analysis, Git commit, deletion or automatic approval. */
@Service
public class ReformulationAdoptionService {
    private final ProjectPortfolioService projects;
    private final ProjectRequirementRepository requirements;
    private final ReformulationProposalRepository proposals;
    private final ReformulationService reformulations;
    private final ReformulationAdoptionPreviewRepository previews;
    private final ReformulationAdoptionRepository receipts;
    private final PortfolioJsonCodec json;
    public ReformulationAdoptionService(ProjectPortfolioService projects,ProjectRequirementRepository requirements,
            ReformulationProposalRepository proposals,ReformulationService reformulations,
            ReformulationAdoptionPreviewRepository previews,ReformulationAdoptionRepository receipts,PortfolioJsonCodec json) {
        this.projects=projects;this.requirements=requirements;this.proposals=proposals;this.reformulations=reformulations;
        this.previews=previews;this.receipts=receipts;this.json=json;
    }
    @Transactional
    public Preview preview(Long projectId,Long requirementId,String proposalId,long expected,String actor,WorkspaceContext context) {
        var requirement=lockRequirement(projectId,requirementId,actor,context);
        var proposal=lockProposal(projectId,requirementId,proposalId,requirement.getScopeKey());
        if(proposal.getCurrentRevision()!=expected)throw new ReformulationPreconditionException();
        var offer=reformulations.get(projectId,requirementId,proposalId,actor,context);
        var revision=offer.currentRevision();
        var current=projects.getRequirement(projectId,requirementId,actor,context);
        String text=revision.text().strip();
        if(text.isBlank() || text.length()>100_000)throw invalid("INVALID_ADOPTION_TEXT","Adoption text must contain 1..100000 characters");
        var warnings=new ArrayList<String>();var blocked=new ArrayList<String>();var unresolved=new ArrayList<String>();
        for(var finding:revision.validation().findings()) {
            (finding.kind()==ValidationReport.Kind.STRUCTURAL_LOSS?blocked:warnings).add(finding.code());
        }
        revision.questions().stream().filter(q->q.state()!=DecisionQuestion.State.ANSWERED && q.state()!=DecisionQuestion.State.NOT_APPLICABLE)
                .map(DecisionQuestion::id).forEach(unresolved::add);
        if(!unresolved.isEmpty())warnings.add("UNRESOLVED_QUESTIONS");
        if(!revision.impact().equals(ReformulationImpact.empty()))warnings.add("REVIEW_PENDING_EDITS_AND_DECISIONS");
        if(!Objects.equals(offer.baseline().sourceVersionId(),current.currentVersionId()))warnings.add("SOURCE_DIFFERS_FROM_CURRENT");
        if(!text.equals(revision.text()))warnings.add("EXTERIOR_WHITESPACE_NORMALIZED");
        if(revision.statements().stream().anyMatch(s->s.provenance()!=Statement.Provenance.ORIGINAL))warnings.add("ADDITIONS_REQUIRE_REVIEW");
        checkReferences(revision,offer.baseline().originalText(),blocked);
        var data=new PreviewContent(UUID.randomUUID().toString(),proposalId,offer.baseline().sourceVersionId(),offer.baseline().originalText(),
                offer.baseline().snapshotId(),current,requirement.getRowVersion(),revision,text,warnings.stream().distinct().toList(),
                blocked.stream().distinct().toList(),unresolved,PortfolioScope.username(actor,context),Instant.now());
        String payload=json.write(data);String hash=StableIdentityHash.sha256(payload);
        previews.saveAndFlush(new ReformulationAdoptionPreview(data.id(),proposalId,requirement.getScopeKey(),hash,payload,data.createdAt()));
        return new Preview(data,hash);
    }
    @Transactional(readOnly=true)
    public Preview readPreview(Long projectId,Long requirementId,String proposalId,String id,String actor,WorkspaceContext context) {
        reformulations.get(projectId,requirementId,proposalId,actor,context);
        return storedPreview(id,proposalId,PortfolioScope.key(actor,context));
    }
    @Transactional(readOnly=true)
    public List<Result> history(Long projectId,Long requirementId,String proposalId,String actor,WorkspaceContext context) {
        reformulations.get(projectId,requirementId,proposalId,actor,context);
        return receipts.findByProposalIdAndScopeKeyOrderByCreatedAtDesc(proposalId,PortfolioScope.key(actor,context)).stream()
                .map(r->json.read(r.getPayload(),Result.class)).toList();
    }
    @Transactional
    public Result adopt(Long projectId,Long requirementId,String proposalId,long expected,ConfirmRequest request,String actor,WorkspaceContext context) {
        if(request==null)throw invalid("CONFIRMATION_REQUIRED","Explicit confirmation is required");
        uuid(request.commandId());uuid(request.previewId());
        if(request.previewHash()==null || !request.previewHash().matches("[a-f0-9]{64}"))throw invalid("INVALID_PREVIEW_HASH","A preview hash is required");
        if(request.rationale()==null || request.rationale().isBlank() || request.rationale().length()>1000)throw invalid("INVALID_RATIONALE","A rationale of 1..1000 characters is required");
        var requirement=lockRequirement(projectId,requirementId,actor,context);
        String scope=requirement.getScopeKey();String author=PortfolioScope.username(actor,context);
        var proposal=lockProposal(projectId,requirementId,proposalId,scope);
        String id=StableIdentityHash.sha256(json.write(List.of(scope,request.commandId())));
        String commandHash=StableIdentityHash.sha256(json.write(List.of(projectId,requirementId,proposalId,expected,author,request)));
        // Authorization remains current, but a valid retry never replays the old version mutation.
        var prior=receipts.findById(id);
        if(prior.isPresent()) {
            if(!prior.get().getCommandHash().equals(commandHash))throw conflict("COMMAND_REUSED","Command ID was used with different contents");
            return json.read(prior.get().getPayload(),Result.class);
        }
        var preview=storedPreview(request.previewId(),proposalId,scope);var data=preview.content();
        if(!preview.hash().equals(request.previewHash()))throw conflict("PREVIEW_CHANGED","Confirmation does not match the stored preview");
        if(!data.actor().equals(author))throw conflict("PREVIEW_ACTOR_CHANGED","Create a preview as the current actor");
        if(receipts.existsByPreviewId(data.id()))throw conflict("PREVIEW_ALREADY_ADOPTED","This preview already has an adoption receipt");
        if(expected!=data.revision().number() || expected!=proposal.getCurrentRevision())throw new ReformulationPreconditionException();
        if(requirement.getRowVersion()!=data.requirementRowVersion() || !Objects.equals(requirement.getCurrentVersionId(),data.currentRequirement().currentVersionId()))
            throw conflict("SOURCE_VERSION_CHANGED","The active requirement changed; create and review a new preview");
        if(!request.confirmed())throw invalid("CONFIRMATION_REQUIRED","Confirm this exact preview explicitly");
        if(!data.warnings().isEmpty() && !request.acknowledgeWarnings())throw invalid("WARNINGS_REQUIRE_ACKNOWLEDGMENT","Review the open questions and warnings before adopting a draft");
        if(!data.blockingReasons().isEmpty())throw invalid("STRUCTURALLY_INVALID_PROPOSAL","The stored proposal has structural errors and cannot be adopted");
        boolean changed=!data.finalText().equals(data.currentRequirement().currentVersion().text());
        RequirementVersionView target=data.currentRequirement().currentVersion();
        if(changed) {
            target=projects.addRequirementVersion(projectId,requirementId,
                    new CreateRequirementVersionRequest(data.finalText(),request.rationale(),null),actor,context);
            requirement.updateMetadata(null,RequirementStatus.DRAFT,null,null,null,ReviewStatus.PROPOSED,null,Instant.now());
        }
        // Keep the old immutable snapshot available; it is not evidence for newly adopted text.
        var result=new Result(request.commandId(),data.id(),proposalId,expected,data.currentRequirement().currentVersionId(),
                target.id(),target.versionNumber(),changed,changed,data.unresolvedQuestionIds(),author,Instant.now(),request.rationale(),request.acknowledgeWarnings());
        receipts.saveAndFlush(new ReformulationAdoption(id,proposalId,data.id(),scope,commandHash,requirementId,target.id(),json.write(result),result.adoptedAt()));
        return result;
    }
    private ProjectRequirement lockRequirement(Long projectId,Long requirementId,String actor,WorkspaceContext context) {
        var project=projects.requireProjectForUpdate(projectId,actor,context);
        return requirements.findByIdAndProjectIdAndScopeKeyForUpdate(requirementId,projectId,project.getScopeKey())
                .orElseThrow(()->PortfolioException.notFound("Requirement not found"));
    }
    private ReformulationProposal lockProposal(Long projectId,Long requirementId,String id,String scope) {
        return proposals.lockScoped(id,projectId,requirementId,scope).orElseThrow(()->PortfolioException.notFound("Reformulation proposal not found"));
    }
    private Preview storedPreview(String id,String proposalId,String scope) {
        var row=previews.findByIdAndProposalIdAndScopeKey(id,proposalId,scope).orElseThrow(()->PortfolioException.notFound("Adoption preview not found"));
        if(!StableIdentityHash.sha256(row.getPayload()).equals(row.getContentHash()))throw conflict("INVALID_PREVIEW_EVIDENCE","Stored preview integrity check failed");
        return new Preview(json.read(row.getPayload(),PreviewContent.class),row.getContentHash());
    }
    private static void checkReferences(ReformulationDtos.Revision revision,String original,List<String> blocked) {
        var statements=new HashSet<String>();var questions=new HashSet<String>();var sections=new HashSet<String>();
        revision.statements().forEach(s->{if(!statements.add(s.id()))blocked.add("DUPLICATE_STATEMENT");if(s.sourceSpans().stream().anyMatch(p->!p.matches(original)))blocked.add("INVALID_SOURCE_SPAN");});
        revision.questions().forEach(q->{for(String id:q.referenceIds())if(!questions.add(id))blocked.add("DUPLICATE_QUESTION_REFERENCE");});
        revision.sections().forEach(s->{if(!sections.add(s.id()))blocked.add("DUPLICATE_SECTION");});
        revision.statements().forEach(s->{if(!questions.containsAll(s.questionDependencies()))blocked.add("UNKNOWN_QUESTION_REFERENCE");});
        revision.questions().forEach(q->{if(!statements.containsAll(q.affectedStatementIds()) || !questions.containsAll(q.prerequisites()) || !questions.containsAll(q.dependentQuestionIds()))blocked.add("UNKNOWN_DECISION_REFERENCE");});
        revision.sections().forEach(s->{if(!statements.containsAll(s.statementIds()) || !questions.containsAll(s.questionIds()) || !sections.containsAll(s.children()))blocked.add("UNKNOWN_SECTION_REFERENCE");});
        revision.answers().forEach(a->{if(!questions.contains(a.questionId()))blocked.add("UNKNOWN_ANSWER_REFERENCE");});
    }
    private static void uuid(String value) {
        if(value==null || !value.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw invalid("INVALID_COMMAND_ID","Canonical UUID required");
    }
    private static PortfolioException invalid(String code,String message){return new PortfolioException(PortfolioException.Kind.ANALYSIS_FAILED,code,message,null);}
    private static PortfolioException conflict(String code,String message){return new PortfolioException(PortfolioException.Kind.CONFLICT,code,message,null);}
}
