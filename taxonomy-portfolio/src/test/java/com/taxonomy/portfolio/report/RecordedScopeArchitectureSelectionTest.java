package com.taxonomy.portfolio.report;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;

import com.taxonomy.architecture.decision.*;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.*;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecordedScopeArchitectureSelectionTest {
    private static final WorkspaceContext CONTEXT = new WorkspaceContext("auditor", "workspace-a", "main", "repository-a");

    @Test
    void omittedAndExplicitRecordedRootsProduceIdenticalArchitectureEvidence() {
        var service = service("CP");
        var implicit = service.loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, DecisionReportOptions.full());
        var explicit = service.loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH,
                new DecisionReportOptions(DecisionReportOptions.Profile.FULL, Set.of("CP"), null, null, null));
        assertEquals(explicit.architecture(), implicit.architecture());
        assertTrue(implicit.architecture().scope().contains("Boundary context: BP"));
        assertEquals(List.of("CP", "BP"), implicit.architecture().diagram().nodes().stream().map(DiagramNode::id).toList());
        assertEquals(1, implicit.architecture().relations().size());
    }

    @Test
    void legacyCallWithoutOptionsStillHonorsARecordedRestrictedScope() {
        var service = service("CP");
        var implicit = service.loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, null);
        var configured = service.loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, DecisionReportOptions.full());
        assertEquals(configured.architecture(), implicit.architecture());
        assertTrue(implicit.architecture().scope().contains("Boundary context: BP"));
    }

    @Test
    void absentRecordedScopeRetainsLegacyWholeGraphEvidence() {
        var source = service(null).loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, DecisionReportOptions.full());
        assertEquals(3, source.architecture().diagram().nodes().size());
        assertFalse(source.architecture().scope().contains("Boundary context:"));
    }

    @Test
    void recordedSelectionWithNoArchitectureNodesDoesNotExportUnrelatedGraph() {
        var source = service("CR").loadEvidence(41L, "snapshot-1", "auditor", CONTEXT, Locale.ENGLISH, DecisionReportOptions.full());
        assertNull(source.architecture());
        assertEquals(Set.of("CR"), source.decision().scope().reportRoots());
    }

    private static SnapshotWordReportService service(String root) {
        var decisions = new DecisionRationaleSnapshotReportService(null, null, null) {
            @Override public DecisionRationaleReport generate(Long p, String s, String u, WorkspaceContext c, Locale l) {
                return decision(root, DecisionReportOptions.full());
            }
            @Override public DecisionRationaleReport generate(Long p, String s, String u, WorkspaceContext c, Locale l, DecisionReportOptions o) {
                return decision(root, o);
            }
        };
        var workbench = new ArchitectureWorkbenchService(null, null, null, null, null, null) {
            @Override public Projection load(Long p, String s, String u, WorkspaceContext c) { return projection(); }
            @Override public Optional<Projection> loadIfPresent(Long p, String s, String u, WorkspaceContext c) { return Optional.of(projection()); }
        };
        return new SnapshotWordReportService(decisions, workbench);
    }

    private static DecisionRationaleReport decision(String root, DecisionReportOptions options) {
        var metadata = new DecisionRationaleReport.ReportMetadata(Instant.EPOCH, "auditor", "1", "build",
                "catalogue", "v1", "source", "data-sha", "analysis-sha", "snapshot", 3, 3,
                "repository-a", "workspace-a", "main", "commit-a", Instant.EPOCH, false, false,
                "MOCK", "SUCCESS", "model-a", "snapshot-1", 41L, 42L, 43L, 7, Instant.EPOCH,
                "author", "data-sha", "prompt", true, "UTC", 2, 2, 1, 100);
        var selected = root == null ? Set.of("CP", "BP", "UA") : Set.of(root);
        var scope = new DecisionReportScope(root == null ? null : new AnalysisScope(Set.of(root), AnalysisScope.full().mode()),
                null, List.of(), selected, selected, new DecisionTreeOverview(List.of(), List.of()), options, true, true);
        return new DecisionRationaleReport("Saved title", "en", "Saved requirement", DecisionRationaleReport.ReportStatus.FINAL,
                metadata, null, List.of(), List.of(), List.of("Saved warning"), List.of(), List.of(), null, Map.of(), null, scope);
    }

    private static Projection projection() {
        var graph = new DiagramModel("Saved graph", List.of(
                new DiagramNode("CP", "Selected capability", "Capability", 1, true, 0),
                new DiagramNode("BP", "Crossing process", "Process", .5, false, 1),
                new DiagramNode("UA", "Unrelated application", "Application", .4, false, 2)),
                List.of(new DiagramEdge("R1", "CP", "BP", "serves", .5)), new DiagramLayout("LR", true));
        return new Projection(41L, "project", "Saved graph", 42L, "requirement", "Requirement", "Saved requirement",
                "snapshot-1", AnalysisStatus.SUCCESS, Instant.EPOCH, "MOCK", "model-a", "workspace-a", "main", "commit-a",
                graph, new LayeredDiagramLayoutService().layout(graph), Map.of(), Map.of(), List.of("Saved warning"),
                new SnapshotProvenance(43L, "data-sha", "repository-a"));
    }
}
