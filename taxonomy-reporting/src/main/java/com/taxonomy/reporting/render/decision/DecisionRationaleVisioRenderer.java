package com.taxonomy.reporting.render.decision;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.VisioDrawingOptions;
import com.taxonomy.export.VisioDecisionTree;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.Section;
import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioProperty;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native Visio drawings from the same captured hierarchy and architecture as the other decision reports. */
public class DecisionRationaleVisioRenderer implements ReportRendererExtension {
    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "visio", "Visio", "vsdx", "application/vnd.ms-visio.drawing", true);

    private final VisioDiagramService diagrams;
    private final VisioPackageBuilder packages;

    public DecisionRationaleVisioRenderer(ObjectProvider<VisioDiagramService> diagrams,
                                           ObjectProvider<VisioPackageBuilder> packages) {
        this.diagrams = diagrams.getIfAvailable(VisioDiagramService::new);
        this.packages = packages.getIfAvailable(VisioPackageBuilder::new);
    }

    @Override public String reportTypeId() { return DecisionRationaleReportPlugin.REPORT_TYPE_ID; }
    @Override public Class<?> reportModelType() { return DecisionRationaleReport.class; }
    @Override public ReportFormatDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public ReportRenderResult render(ReportRenderContext context) {
        var report = context.payloadAs(DecisionRationaleReport.class);
        boolean german = report.languageTag().startsWith("de");
        var reasons = report.scope().recordedReasons();
        var options = report.scope().options();
        // Visio always draws the recorded hierarchy for the selected taxonomies.
        var nodes = report.scope().decisionTree().rows().stream()
                .map(row -> new VisioDecisionTree.Node(row.depth(), row.code(), row.title(), row.score(),
                        reasons.get(row.code()), options.showResponsibilities() ? row.responsibility() : null)).toList();
        DiagramModel architecture = options.includes(Section.ARCHITECTURE) && report.architecture() != null
                ? report.architecture().diagram() : null;
        if (architecture == null) {
            architecture = new DiagramModel(report.title(), List.of(), List.of(), new DiagramLayout("LR", false));
        } else if (!options.showResponsibilities()) {
            // A frozen architecture can already contain assignments independent of the decision tree.
            var visibleNodes = architecture.nodes().stream().map(node -> new DiagramNode(node.id(), node.label(),
                    node.type(), node.relevance(), node.anchor(), node.layer(), node.depth(), node.selectedForImpact(),
                    node.parentId(), node.container(), null, node.missingMeasurements())).toList();
            architecture = new DiagramModel(architecture.title(), visibleNodes, architecture.edges(), architecture.layout());
        }
        if (nodes.isEmpty() && architecture.nodes().isEmpty()) {
            throw new IllegalArgumentException("The selection contains no Visio drawing.");
        }
        var documentProperties = new LinkedHashMap<String, VisioProperty>();
        documentProperties.put("taxonomy.requirementText", VisioProperty.text(report.requirement()));
        var metadata = report.metadata();
        if (metadata != null) {
            put(documentProperties, "snapshotId", metadata.analysisSnapshotId());
            put(documentProperties, "analysisSha256", metadata.analysisSnapshotFingerprintSha256());
            put(documentProperties, "projectId", metadata.projectId());
            put(documentProperties, "requirementId", metadata.requirementId());
            put(documentProperties, "requirementVersionId", metadata.requirementVersionId());
            put(documentProperties, "requirementVersion", metadata.requirementVersionNumber());
            put(documentProperties, "snapshotCreatedAt", metadata.analysisCreatedAt());
            put(documentProperties, "workspaceId", metadata.workspaceId());
            put(documentProperties, "branch", metadata.branch());
        }
        var elements = new LinkedHashMap<String, Map<String, VisioProperty>>();
        var relations = new LinkedHashMap<String, Map<String, VisioProperty>>();
        if (options.includes(Section.ARCHITECTURE) && report.architecture() != null) {
            report.architecture().nodeReasons().forEach((id, reason) ->
                    elements.put(id, Map.of("taxonomy.presenceReason", VisioProperty.text(reason))));
            report.architecture().relationReasons().forEach((id, fields) -> {
                var properties = new LinkedHashMap<String, VisioProperty>();
                put(properties, "selectionReason", fields.get("presenceReason"));
                put(properties, "decisionRationale", fields.get("decisionComment"));
                relations.put(id, properties);
            });
        }
        var drawing = diagrams.convert(architecture, new VisioExportMetadata(documentProperties, elements, relations, List.of()),
                new VisioDecisionTree(nodes, german),
                options.visio() == null ? VisioDrawingOptions.screen(german) : options.visio().drawingOptions(german));
        try {
            return new ReportRenderResult(packages.build(drawing));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not export the decision drawings as Visio", failure);
        }
    }

    private static void put(Map<String, VisioProperty> properties, String field, Object value) {
        if (value != null) properties.put("taxonomy." + field, VisioProperty.text(value.toString()));
    }
}
