package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads immutable report material. Never invokes adoption, analysis, generation or Git writes. */
@Service
@Transactional(readOnly = true)
public class ReformulationReportService {
    private final ReformulationService proposals;
    private final ReformulationAdoptionService adoptions;
    private final ReformulationAdoptionRepository receipts;
    private final PortfolioJsonCodec json;

    public ReformulationReportService(ReformulationService proposals, ReformulationAdoptionService adoptions,
            ReformulationAdoptionRepository receipts, PortfolioJsonCodec json) {
        this.proposals = proposals;
        this.adoptions = adoptions;
        this.receipts = receipts;
        this.json = json;
    }

    public record Source(long projectId, long requirementId, long versionId, String analysisSnapshotId,
            String language, String originalText, String originalTextHash) {}
    public record ProposalIdentity(String id, long revision) {}
    public record Report(String schemaVersion, String kind, String proposalId, Source source, ProposalIdentity proposal,
            ReformulationDtos.Revision revision, String reviewedText,
            ReformulationAdoptionDtos.Preview preview, ReformulationAdoptionDtos.Result adoption) {}

    public Report revision(Long projectId, Long requirementId, String proposalId, long number,
            String actor, WorkspaceContext context) {
        var offer = proposals.get(projectId, requirementId, proposalId, actor, context);
        if (number <= 0) throw PortfolioException.validation("A positive saved revision is required");
        var revision = proposals.revision(projectId, requirementId, proposalId, number, actor, context);
        // Neither current text nor later adoption status belongs in a historical revision export.
        return new Report("reformulation-report-v1", "PROPOSAL_REVISION", proposalId, source(offer.baseline()),
                new ProposalIdentity(proposalId, revision.number()), revision,
                revision.text(), null, null);
    }

    public Report adoption(Long projectId, Long requirementId, String proposalId, String commandId,
            String actor, WorkspaceContext context) {
        var offer = proposals.get(projectId, requirementId, proposalId, actor, context);
        try {
            if (!UUID.fromString(commandId).toString().equals(commandId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw PortfolioException.validation("A canonical adoption command UUID is required");
        }
        // Same scoped identity as the writer; no global command-ID or unbounded history lookup.
        String id = StableIdentityHash.sha256(json.write(List.of(PortfolioScope.key(actor, context), commandId)));
        var stored = receipts.findById(id).orElseThrow(() -> PortfolioException.notFound("Adoption receipt not found"));
        var receipt = json.read(stored.getPayload(), ReformulationAdoptionDtos.Result.class);
        if (!Objects.equals(proposalId, receipt.proposalId()) || !Objects.equals(commandId, receipt.commandId()))
            throw PortfolioException.notFound("Adoption receipt not found");
        // readPreview rechecks authorization and the saved payload's integrity hash.
        var preview = adoptions.readPreview(projectId, requirementId, proposalId, receipt.previewId(), actor, context);
        var content = preview.content();
        var baseline = offer.baseline();
        if (receipt.proposalRevision() != content.revision().number()
                || content.sourceVersionId() != baseline.sourceVersionId()
                || !content.originalText().equals(baseline.originalText())
                || !content.analysisSnapshotId().equals(baseline.snapshotId()))
            throw PortfolioException.conflict("Adoption evidence does not match its immutable source");
        return new Report("reformulation-report-v1", "ADOPTION_RECEIPT", proposalId, source(baseline),
                new ProposalIdentity(proposalId, content.revision().number()), content.revision(),
                content.finalText(), preview, receipt);
    }

    private static Source source(ReformulationBaseline baseline) {
        return new Source(baseline.scope().projectId(), baseline.scope().requirementId(), baseline.sourceVersionId(),
                baseline.snapshotId(), baseline.language(), baseline.originalText(), baseline.originalTextHash());
    }
}
