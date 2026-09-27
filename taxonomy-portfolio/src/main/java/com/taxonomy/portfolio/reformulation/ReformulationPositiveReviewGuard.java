package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ValidationReport;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Invoked only after the requirement aggregate lock; neither adoption nor a draft is approval. */
@Service
public class ReformulationPositiveReviewGuard {
    private final ReformulationAdoptionRepository adoptions;
    private final ReformulationPortableEvidenceRepository imports;
    private final PortfolioJsonCodec json;

    public ReformulationPositiveReviewGuard(ReformulationAdoptionRepository adoptions,
            ReformulationPortableEvidenceRepository imports, PortfolioJsonCodec json) {
        this.adoptions = adoptions;
        this.imports = imports;
        this.json = json;
    }

    public void requireReviewable(ArchitectureProject project, ProjectRequirement requirement, ProjectRequirementVersion current) {
        String scope = requirement.getScopeKey();
        for (var receipt : adoptions.findByRequirementIdAndTargetVersionIdAndScopeKey(requirement.getId(), current.getId(), scope)) {
            var preview = receipt.getPreview();
            if (preview == null || !StableIdentityHash.sha256(preview.getPayload()).equals(preview.getContentHash()))
                throw PortfolioException.conflict("Stored adoption review evidence is inconsistent");
            var content = json.read(preview.getPayload(), ReformulationAdoptionDtos.PreviewContent.class);
            if (content == null || content.revision() == null || !Objects.equals(content.proposalId(), receipt.getProposalId()))
                throw PortfolioException.conflict("Stored adoption review evidence is incomplete");
            rejectBlocking(content.revision());
        }
        for (var evidence : imports.findMatchingCurrent(
                scope, project.getProjectKey(), requirement.getRequirementKey(), current.getVersionNumber(), current.getContentHash())) {
            if (!StableIdentityHash.sha256(evidence.getPayload()).equals(evidence.getEvidenceHash()))
                throw PortfolioException.conflict("Imported adoption review evidence is inconsistent");
            var payload = json.read(evidence.getPayload(), ReformulationEvidenceCodec.Payload.class);
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
