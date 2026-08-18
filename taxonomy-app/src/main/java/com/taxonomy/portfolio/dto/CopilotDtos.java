package com.taxonomy.portfolio.dto;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

import java.util.List;

/** API contracts for persisted Copilot and explicitly enabled Autopilot operations. */
public final class CopilotDtos {

    private CopilotDtos() {
    }

    public record CopilotRunRequest(
            String provider,
            Integer maxArchitectureNodes,
            AnalysisAutomationProfile profile,
            Integer verificationPasses,
            Boolean force,
            Boolean proposeSolutions,
            Boolean proposeProducts) {

        public boolean forceRun() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record ProjectAutopilotRunRequest(
            List<Long> requirementIds,
            Integer maxRequirements) {
    }

    public record ProjectAutopilotStatus(
            Long projectId,
            boolean autopilotReady,
            boolean runAfterRequirementSave,
            int requirementCount,
            int maximumBatchRequirements,
            String reason) {
    }

    public record ProjectAutopilotRunView(
            Long projectId,
            int selectedRequirements,
            int operationsStarted,
            List<String> operationIds,
            String message) {
    }

    public record AiAutomationStatus(
            AiCostPolicy costPolicy,
            boolean manualCopilotReady,
            boolean autopilotEnabled,
            boolean autopilotReady,
            boolean runAfterRequirementSave,
            String activeProvider,
            String autopilotProvider,
            AnalysisAutomationProfile copilotProfile,
            AnalysisAutomationProfile autopilotProfile,
            int copilotVerificationPasses,
            int autopilotVerificationPasses,
            int maxArchitectureNodes,
            String reason,
            List<String> automaticSteps,
            List<String> humanReviewRequired) {
    }

    public record CopilotOperationView(
            String operationId,
            Long projectId,
            Long requirementId,
            AnalysisAutomationProfile profile,
            AiCostPolicy costPolicy,
            boolean autopilot,
            String provider,
            int maxArchitectureNodes,
            int verificationPasses,
            int completedPasses,
            AnalysisStatus status,
            boolean proposeSolutions,
            boolean proposeProducts,
            String selectedSnapshotId,
            String message,
            List<AnalysisJobView> jobs,
            List<String> automaticSteps,
            List<String> humanReviewRequired) {
    }
}
