package com.taxonomy.portfolio.workbench;

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
            double directScore,
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
            List<String> warnings,
            SnapshotProvenance exportProvenance,
            String policyTitleKey,
            String persistedViewTitle,
            Map<String, String> nodeReasons) {

        /** Source-compatible constructor for projections without separate recorded node explanations. */
        public Projection(Long projectId, String projectKey, String projectTitle,
                Long requirementId, String requirementKey, String requirementTitle, String requirementText,
                String snapshotId, AnalysisStatus snapshotStatus, Instant snapshotCreatedAt,
                String provider, String modelName, String workspaceId, String branchName, String commitSha,
                DiagramModel diagram, DiagramScene scene, Map<String, ElementMetadata> elements,
                Map<String, RelationMetadata> relations, List<String> warnings, SnapshotProvenance exportProvenance,
                String policyTitleKey, String persistedViewTitle) {
            this(projectId, projectKey, projectTitle, requirementId, requirementKey, requirementTitle, requirementText,
                    snapshotId, snapshotStatus, snapshotCreatedAt, provider, modelName, workspaceId, branchName, commitSha,
                    diagram, scene, elements, relations, warnings, exportProvenance, policyTitleKey, persistedViewTitle, Map.of());
        }

        /** Source-compatible constructor for projections without persisted title provenance. */
        public Projection(
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
            List<String> warnings,
            SnapshotProvenance exportProvenance,
            String policyTitleKey) {
            this(projectId, projectKey, projectTitle, requirementId, requirementKey, requirementTitle, requirementText, snapshotId, snapshotStatus, snapshotCreatedAt, provider, modelName, workspaceId, branchName, commitSha, diagram, scene, elements, relations, warnings, exportProvenance, policyTitleKey, null);
        }

        public Projection {
            elements = elements == null ? Map.of() : Map.copyOf(elements);
            relations = relations == null ? Map.of() : Map.copyOf(relations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            var captured = new java.util.LinkedHashMap<String, String>();
            if (nodeReasons != null) captured.putAll(nodeReasons);
            // Projections constructed by older callers still retain their recorded mapping evidence.
            elements.forEach((code, element) -> {
                if (element.presenceReason() != null && !element.presenceReason().isBlank())
                    captured.merge(code, element.presenceReason(), (original, reason) ->
                            original.equals(reason) || original.contains(reason) ? original : original + "\n\n" + reason);
            });
            nodeReasons = Map.copyOf(captured);
        }

        /** Mapping/review explanations remain separate from the immutable graph and its digest. */
        public Map<String, Map<String, String>> relationReasons() {
            if (diagram == null || diagram.edges() == null || relations.isEmpty()) return Map.of();
            var captured = new java.util.LinkedHashMap<String, Map<String, String>>();
            for (var edge : diagram.edges()) {
                var relation = relations.get(edge.sourceId() + "|" + edge.targetId() + "|" + edge.relationType());
                if (relation == null) continue;
                var fields = new java.util.LinkedHashMap<String, String>();
                if (relation.presenceReason() != null && !relation.presenceReason().isBlank())
                    fields.put("presenceReason", relation.presenceReason());
                if (relation.decisionComment() != null && !relation.decisionComment().isBlank())
                    fields.put("decisionComment", relation.decisionComment());
                if (!fields.isEmpty()) captured.put(edge.id(), Map.copyOf(fields));
            }
            return Map.copyOf(captured);
        }

        public Projection(Long projectId, String projectKey, String projectTitle,
                          Long requirementId, String requirementKey, String requirementTitle, String requirementText,
                          String snapshotId, AnalysisStatus snapshotStatus, Instant snapshotCreatedAt,
                          String provider, String modelName, String workspaceId, String branchName, String commitSha,
                          DiagramModel diagram, DiagramScene scene, Map<String, ElementMetadata> elements,
                          Map<String, RelationMetadata> relations, List<String> warnings, SnapshotProvenance exportProvenance) {
            this(projectId, projectKey, projectTitle, requirementId, requirementKey, requirementTitle, requirementText,
                    snapshotId, snapshotStatus, snapshotCreatedAt, provider, modelName, workspaceId, branchName, commitSha,
                    diagram, scene, elements, relations, warnings, exportProvenance, null);
        }

        public Projection(Long projectId, String projectKey, String projectTitle,
                          Long requirementId, String requirementKey, String requirementTitle, String requirementText,
                          String snapshotId, AnalysisStatus snapshotStatus, Instant snapshotCreatedAt,
                          String provider, String modelName, String workspaceId, String branchName, String commitSha,
                          DiagramModel diagram, DiagramScene scene, Map<String, ElementMetadata> elements,
                          Map<String, RelationMetadata> relations, List<String> warnings) {
            this(projectId, projectKey, projectTitle, requirementId, requirementKey, requirementTitle, requirementText,
                    snapshotId, snapshotStatus, snapshotCreatedAt, provider, modelName, workspaceId, branchName, commitSha,
                    diagram, scene, elements, relations, warnings, null);
        }
    }

    public record SnapshotProvenance(Long requirementVersionId, String taxonomyFingerprint,
                                     String repositoryId) { }
}
