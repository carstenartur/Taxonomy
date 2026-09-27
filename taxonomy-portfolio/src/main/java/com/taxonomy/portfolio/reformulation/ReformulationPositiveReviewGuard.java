package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.ValidationReport;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Invoked only after the requirement aggregate lock; neither adoption nor a draft is approval. */
@Service
public class ReformulationPositiveReviewGuard {
    private final ReformulationAdoptionRepository adoptions;
    private final ReformulationPortableEvidenceRepository imports;
    private final PortfolioJsonCodec json;
    private final ReformulationEvidenceCodec codec;
    private final ReformulationProposalRepository proposals;

    public ReformulationPositiveReviewGuard(ReformulationAdoptionRepository adoptions,
            ReformulationPortableEvidenceRepository imports, PortfolioJsonCodec json,
            ReformulationEvidenceCodec codec, ReformulationProposalRepository proposals) {
        this.adoptions = adoptions;
        this.imports = imports;
        this.json = json;
        this.codec = codec;
        this.proposals = proposals;
    }

    public void requireReviewable(ArchitectureProject project, ProjectRequirement requirement, ProjectRequirementVersion current) {
        String scope = requirement.getScopeKey();
        for (var receipt : adoptions.findByRequirementIdAndTargetVersionIdAndScopeKey(requirement.getId(), current.getId(), scope)) {
            var preview = receipt.getPreview();
            if (preview == null || !StableIdentityHash.sha256(preview.getPayload()).equals(preview.getContentHash()))
                throw PortfolioException.conflict("Stored adoption review evidence is inconsistent");
            var content = json.read(preview.getPayload(), ReformulationAdoptionDtos.PreviewContent.class);
            var previous = content == null || content.currentRequirement() == null ? null
                    : content.currentRequirement().currentVersion();
            var proposal = proposals.findByIdAndProjectIdAndRequirementIdAndScopeKey(
                    receipt.getProposalId(), project.getId(), requirement.getId(), scope)
                    .orElseThrow(() -> PortfolioException.conflict("Stored adoption proposal is missing"));
            ReformulationBaseline baseline = json.read(proposal.getBaselinePayload(), ReformulationBaseline.class);
            var result = json.read(receipt.getPayload(), ReformulationAdoptionDtos.Result.class);
            if (content == null || content.revision() == null || previous == null || baseline == null || result == null
                    || !Objects.equals(receipt.getScopeKey(), scope)
                    || !Objects.equals(receipt.getRequirementId(), requirement.getId())
                    || !Objects.equals(receipt.getTargetVersionId(), current.getId())
                    || !Objects.equals(content.id(), receipt.getPreviewId())
                    || !Objects.equals(content.proposalId(), receipt.getProposalId())
                    || !Objects.equals(content.currentRequirement().id(), requirement.getId())
                    || !Objects.equals(content.currentRequirement().projectId(), project.getId())
                    || !Objects.equals(content.sourceVersionId(), baseline.sourceVersionId())
                    || !Objects.equals(content.currentRequirement().currentVersionId(), previous.id())
                    || !Objects.equals(result.previousVersionId(), previous.id())
                    || !Objects.equals(result.targetVersionId(), current.getId())
                    || !Objects.equals(content.originalText(), baseline.originalText())
                    || content.originalText() == null || content.finalText() == null
                    || !Objects.equals(StableIdentityHash.sha256(content.originalText()), baseline.originalTextHash())
                    || !Objects.equals(StableIdentityHash.sha256(content.finalText()), current.getContentHash()))
                throw PortfolioException.conflict("Stored adoption review evidence is incomplete");
            rejectBlocking(content.revision());
        }
        for (var evidence : imports.findMatchingCurrent(
                scope, project.getProjectKey(), requirement.getRequirementKey(), current.getVersionNumber(), current.getContentHash())) {
            if (!StableIdentityHash.sha256(evidence.getPayload()).equals(evidence.getEvidenceHash()))
                throw PortfolioException.conflict("Imported adoption review evidence is inconsistent");
            var payload = codec.payload(new ReformulationEvidenceCodec.Evidence(
                    evidence.getProjectKey(), evidence.getRequirementKey(), evidence.getTargetVersionNumber(),
                    evidence.getSchemaVersion(), evidence.getPayload(), evidence.getEvidenceHash(),
                    evidence.getTargetTextHash()));
            if (payload == null || payload.revision() == null
                    || !Objects.equals(payload.projectKey(), evidence.getProjectKey())
                    || !Objects.equals(payload.requirementKey(), evidence.getRequirementKey())
                    || !sameBusinessKey(payload.projectKey(), project.getProjectKey())
                    || !sameBusinessKey(payload.requirementKey(), requirement.getRequirementKey())
                    || payload.targetVersionNumber() != current.getVersionNumber()
                    || !Objects.equals(payload.targetContentHash(), current.getContentHash()))
                throw PortfolioException.conflict("Imported adoption review evidence is incomplete");
            rejectBlocking(payload.revision());
        }
    }

    private static boolean sameBusinessKey(String portable, String local) {
        return portable != null && local != null && portable.equalsIgnoreCase(local);
    }

    private static void rejectBlocking(ReformulationDtos.Revision revision) {
        if (revision.questions().stream().anyMatch(q -> q.state() == DecisionQuestion.State.CONFLICT)
                || revision.validation().findings().stream().anyMatch(f ->
                    f.kind() == ValidationReport.Kind.STRUCTURAL_LOSS || f.kind() == ValidationReport.Kind.CONFLICT))
            throw PortfolioException.conflict("Adopted reformulation has unresolved blocking review evidence");
    }
}
