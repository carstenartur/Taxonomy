package com.taxonomy.architecture.workbench;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** REST contracts for the read-only, server-authoritative architecture workbench. */
public final class ArchitectureWorkbenchDtos {

    private ArchitectureWorkbenchDtos() {
    }

    public record ElementMetadata(
            String nodeCode,
            String nodeTitle,
            String taxonomyRoot,
            int directScore,
            double relevance,
            double confidence,
            String mappingOrigin,
            String hierarchyPath,
            String presenceReason,
            boolean selectedForImpact,
            ReviewStatus reviewStatus,
            ActionStatus actionStatus,
            String actionEvidence,
            String decisionBy,
            Instant decisionAt,
            String decisionComment) {
    }

    public record RelationMetadata(
            String sourceCode,
            String targetCode,
            String relationType,
            String relationOrigin,
            String relationCategory,
            double relevance,
            double confidence,
            String presenceReason,
            ReviewStatus reviewStatus,
            String decisionBy,
            Instant decisionAt,
            String decisionComment) {

        public String signature() {
            return sourceCode + "|" + targetCode + "|" + relationType;
        }
    }

    public record Projection(
            Long projectId,
            String projectKey,
            String projectTitle,
            Long requirementId,
            String requirementKey,
            String requirementTitle,
            String requirementText,
            String snapshotId,
            AnalysisStatus snapshotStatus,
            Instant snapshotCreatedAt,
            String provider,
            String modelName,
            String workspaceId,
            String branchName,
            String commitSha,
            DiagramModel diagram,
            DiagramScene scene,
            Map<String, ElementMetadata> elements,
            Map<String, RelationMetadata> relations,
            List<String> warnings) {

        public Projection {
            elements = elements == null ? Map.of() : Map.copyOf(elements);
            relations = relations == null ? Map.of() : Map.copyOf(relations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
