package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import java.time.Instant;
import java.util.List;

/** Exact immutable review material; this contract never denotes an architecture approval. */
public final class ReformulationAdoptionDtos {
    private ReformulationAdoptionDtos() {}
    public record PreviewContent(String id, String proposalId, long sourceVersionId, String originalText,
            String analysisSnapshotId, RequirementView currentRequirement, long requirementRowVersion,
            ReformulationDtos.Revision revision, String finalText, List<String> warnings,
            List<String> blockingReasons, List<String> unresolvedQuestionIds, String actor, Instant createdAt) {
        public PreviewContent { warnings=List.copyOf(warnings); blockingReasons=List.copyOf(blockingReasons); unresolvedQuestionIds=List.copyOf(unresolvedQuestionIds); }
    }
    public record Preview(PreviewContent content, String hash) {}
    public record ConfirmRequest(String commandId, String previewId, String previewHash,
            boolean confirmed, boolean acknowledgeWarnings, String rationale) {}
    public record Result(String commandId, String previewId, String proposalId, long proposalRevision,
            long previousVersionId, long targetVersionId, int targetVersionNumber, boolean textChanged,
            boolean analysisNeedsRefresh, List<String> unresolvedQuestionIds, String actor, Instant adoptedAt, String rationale, boolean warningsAcknowledged) {
        public Result { unresolvedQuestionIds=List.copyOf(unresolvedQuestionIds); }
    }
}
