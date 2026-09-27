package com.taxonomy.composition.reformulation;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.reformulation.FrozenReformulationArchitecture;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.ReformulationBaseline;
import org.springframework.stereotype.Component;

import java.util.*;

/** Decode and validate ONLY the baseline's captured bytes, never a live catalogue or snapshot. */
@Component
public final class FrozenReformulationArchitectureAssembler {
    private final PortfolioJsonCodec json;
    FrozenReformulationArchitectureAssembler(PortfolioJsonCodec json) { this.json = json; }

    public FrozenReformulationArchitecture assemble(ReformulationBaseline baseline) {
        var frozen = baseline.frozenContext();
        SnapshotDetail detail = required(frozen, "snapshotDetail", SnapshotDetail.class);
        AnalysisResult payload = json.read(baseline.snapshotPayload(), AnalysisResult.class);
        TaxonomyNodeDto[] catalogue = required(frozen, "catalogue", TaxonomyNodeDto[].class);
        ElementMappingView[] elements = required(frozen, "elementMappings", ElementMappingView[].class);
        RelationMappingView[] relations = required(frozen, "relationMappings", RelationMappingView[].class);
        RequirementVersionView version = required(frozen, "sourceVersion", RequirementVersionView.class);
        ProjectView project = required(frozen, "project", ProjectView.class);
        var summary = detail.summary();
        var scope = baseline.scope();
        check(summary != null && Objects.equals(summary.id(), baseline.snapshotId())
                && Objects.equals(summary.projectId(), scope.projectId())
                && Objects.equals(summary.requirementId(), scope.requirementId())
                && Objects.equals(summary.requirementVersionId(), baseline.sourceVersionId()), "Snapshot source identity disagrees with the baseline");
        check(Objects.equals(version.id(), baseline.sourceVersionId())
                && Objects.equals(version.text(), baseline.originalText())
                && Objects.equals(version.contentHash(), baseline.originalTextHash())
                && Objects.equals(project.id(), scope.projectId())
                && Objects.equals(project.workspaceId(), scope.workspaceId()), "Frozen source/project disagrees with the baseline");
        check(payload != null && detail.analysis() != null && Objects.equals(json.write(payload), json.write(detail.analysis())),
                "Snapshot payload disagrees with frozen snapshot detail");
        check(Objects.equals(json.write(elements), json.write(detail.elementMappings()))
                && Objects.equals(json.write(relations), json.write(detail.relationMappings()))
                && Objects.equals(json.write(catalogue), json.write(detail.analysis().getTree() == null ? List.of() : detail.analysis().getTree())),
                "Frozen catalogue or mappings disagree with snapshot detail");

        var names = new HashMap<String, TaxonomyNodeDto>();
        for (var root : catalogue) index(root, names);
        var nodes = new ArrayList<DiagramNode>();
        var nodeIds = new HashSet<String>();
        var elementDetails = new ArrayList<String>();
        for (var element : elements) {
            check(element.id() != null && element.id() > 0 && baseline.snapshotId().equals(element.snapshotId())
                    && present(element.nodeCode()) && nodeIds.add(element.nodeCode()) && names.containsKey(element.nodeCode()),
                    "Invalid frozen element mapping");
            var node = names.get(element.nodeCode());
            var label = baseline.language().equals("de") ? node.getNameDe() : node.getNameEn();
            if (!present(label)) label = present(element.nodeTitle()) ? element.nodeTitle() : element.nodeCode();
            String parent = node.getParentCode();
            nodes.add(new DiagramNode(element.nodeCode(), label,
                    Objects.toString(element.taxonomyRoot(), "Unknown"), element.relevance(),
                    element.directScore() > 0, 0, node.getLevel(), element.selectedForImpact(),
                    parent, false));
            elementDetails.add(element.nodeCode() + " — " + label + " · " + Objects.toString(element.presenceReason(), "")
                    + " · " + Objects.toString(baseline.language().equals("de") ? node.getDescriptionDe() : node.getDescriptionEn(), "")
                    + " · " + element.reviewStatus());
        }
        // The saved analysis view can contain directed relations even when no mapping row was indexed.
        // Its identity remains distinct; every occurrence is retained, including parallel edges.
        var edges = new ArrayList<DiagramEdge>();
        var edgeIds = new HashSet<String>();
        var relationDetails = new ArrayList<String>();
        for (var relation : relations) {
            check(relation.id() != null && relation.id() > 0 && baseline.snapshotId().equals(relation.snapshotId()),
                    "Invalid frozen relation mapping identity");
            addEdge(edges, edgeIds, nodeIds, "mapping-" + relation.id(), relation.sourceCode(), relation.targetCode(),
                    relation.relationType(), relation.relevance(), relation.relationCategory());
            relationDetails.add("mapping-" + relation.id() + " · " + relation.sourceCode() + " → " + relation.targetCode()
                    + " · " + relation.relationType() + " · " + Objects.toString(relation.presenceReason(), "")
                    + " · " + relation.reviewStatus());
        }
        var view = payload.getArchitectureView();
        if (view != null && view.getIncludedRelationships() != null) {
            int ordinal = 0;
            for (var relation : view.getIncludedRelationships()) {
                String id = "view-" + (++ordinal);
                addEdge(edges, edgeIds, nodeIds, id, relation.getSourceCode(), relation.getTargetCode(),
                        relation.getRelationType(), relation.getPropagatedRelevance(), relation.getRelationCategory());
                relationDetails.add(id + " · " + relation.getSourceCode() + " → " + relation.getTargetCode()
                        + " · " + relation.getRelationType() + " · " + Objects.toString(relation.getPresenceReason(), ""));
            }
        }
        var gaps = new ArrayList<String>();
        if (detail.gapAnalysis() != null) {
            var gap = detail.gapAnalysis();
            gap.getMissingRelations().forEach(g -> gaps.add("Relation: " + g.getSourceNodeCode() + " → "
                    + g.getExpectedTargetRoot() + " · " + g.getExpectedRelationType() + " · " + g.getDescription()));
            gap.getIncompletePatterns().forEach(g -> gaps.add("Pattern: " + g.getNodeCode() + " · "
                    + g.getPatternDescription() + " · " + g.getMissingElement()));
            gap.getCoverageGaps().forEach(g -> gaps.add("Coverage: " + g.getNodeCode() + " · " + g.getGapDescription()));
            gaps.addAll(gap.getNotes());
        }
        var warnings = new ArrayList<String>();
        warnings.addAll(payload.getWarnings());
        warnings.addAll(payload.getScoreSemanticsWarnings());
        if (payload.getErrorMessage() != null) warnings.add(payload.getErrorMessage());
        if (summary.errorMessage() != null) warnings.add(summary.errorMessage());
        var identity = new TreeMap<String, String>();
        identity.put("Snapshot", baseline.snapshotId());
        identity.put("Repository", scope.repositoryId());
        identity.put("Workspace", Objects.toString(scope.workspaceId(), "—"));
        identity.put("Offer workspace branch", scope.branch());
        identity.put("Snapshot branch", Objects.toString(summary.branchName(), "—"));
        identity.put("Analysis based-on branch", payload.getViewContext() == null ? "—" : Objects.toString(payload.getViewContext().basedOnBranch(), "—"));
        identity.put("Analysis based-on commit", payload.getViewContext() == null ? "—" : Objects.toString(payload.getViewContext().basedOnCommit(), "—"));
        identity.put("Provider / model", Objects.toString(summary.provider(), "—") + " / " + Objects.toString(summary.modelName(), "—"));
        identity.put("Status", Objects.toString(summary.status(), "—"));
        return new FrozenReformulationArchitecture(new DiagramModel("Frozen architecture · " + baseline.snapshotId(),
                nodes, edges, new DiagramLayout("LR", true)), identity, gaps, warnings,
                detail.gapAnalysis() != null, elementDetails, relationDetails);
    }

    private <T> T required(Map<String, String> frozen, String key, Class<T> type) {
        check(present(frozen.get(key)), "Missing frozen " + key);
        T value = json.read(frozen.get(key), type);
        check(value != null, "Missing frozen " + key);
        return value;
    }
    private static void index(TaxonomyNodeDto node, Map<String, TaxonomyNodeDto> names) {
        check(node != null && present(node.getCode()) && names.putIfAbsent(node.getCode(), node) == null,
                "Duplicate or invalid frozen catalogue node");
        if (node.getChildren() != null) node.getChildren().forEach(child -> index(child, names));
    }
    private static void addEdge(List<DiagramEdge> edges, Set<String> ids, Set<String> nodes, String id,
            String source, String target, String type, double relevance, String category) {
        check(ids.add(id) && present(source) && present(target) && nodes.contains(source) && nodes.contains(target)
                && present(type), "Invalid directed frozen relation " + id);
        edges.add(new DiagramEdge(id, source, target, type, relevance, category));
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
