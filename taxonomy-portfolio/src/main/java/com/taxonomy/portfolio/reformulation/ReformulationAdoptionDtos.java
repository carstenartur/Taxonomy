package com.taxonomy.portfolio.reformulation;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(name = "ReformulationAdoptionConfirmation", description = "Explicit confirmation of an immutable reviewed preview")
    public record ConfirmRequest(
            @Schema(description = "Stable client-generated UUID for idempotent replay of this exact confirmation",
                    format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String commandId,
            @Schema(description = "UUID of the previously reviewed immutable preview",
                    format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String previewId,
            @Schema(description = "Exact lowercase SHA-256 hash returned with the preview",
                    pattern = "[0-9a-f]{64}", requiredMode = Schema.RequiredMode.REQUIRED) String previewHash,
            @Schema(description = "Must be true after explicit user confirmation",
                    allowableValues = "true", requiredMode = Schema.RequiredMode.REQUIRED) boolean confirmed,
            @Schema(description = "Must be true when the preview contains warnings") boolean acknowledgeWarnings,
            @Schema(description = "Required human rationale for the adoption",
                    minLength = 1, maxLength = 1000, requiredMode = Schema.RequiredMode.REQUIRED) String rationale) {}
    public record Result(String commandId, String previewId, String proposalId, long proposalRevision,
            long previousVersionId, long targetVersionId, int targetVersionNumber, boolean textChanged,
            boolean analysisNeedsRefresh, List<String> unresolvedQuestionIds, String actor, Instant adoptedAt, String rationale, boolean warningsAcknowledged) {
        public Result { unresolvedQuestionIds=List.copyOf(unresolvedQuestionIds); }
    }
}
