package com.taxonomy.reporting.render.decision;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native Visio drawings from the same captured hierarchy and architecture as the other decision reports. */
public class DecisionRationaleVisioRenderer implements ReportRendererExtension {
    private static final ReportFormatDescriptor DESCRIPTOR = new ReportFormatDescriptor(
            "visio", "Visio", "vsdx", "application/vnd.ms-visio.drawing", false);

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
        Map<String, String> reasons = new HashMap<>();
        report.chapters().forEach(chapter -> chapter.children().forEach(child -> {
            if (child.reason() != null) reasons.put(child.code(), child.reason());
        }));
        var options = report.scope().options();
        var nodes = options.includes(Section.TREE) ? report.scope().decisionTree().rows().stream()
                .map(row -> new VisioDecisionTree.Node(row.depth(), row.code(), row.title(), row.score(),
                        reasons.get(row.code()), row.responsibility())).toList() : List.<VisioDecisionTree.Node>of();
        DiagramModel architecture = options.includes(Section.ARCHITECTURE) && report.architecture() != null
                ? report.architecture().diagram() : null;
        if (architecture == null) {
            architecture = new DiagramModel(report.title(), List.of(), List.of(), new DiagramLayout("LR", false));
        }
        if (nodes.isEmpty() && architecture.nodes().isEmpty()) {
            throw new IllegalArgumentException("The selection contains no Visio drawing.");
        }
        var documentProperties = new java.util.LinkedHashMap<String, VisioProperty>();
        documentProperties.put("taxonomy.requirementText", VisioProperty.text(report.requirement()));
        var metadata = report.metadata();
        if (metadata != null) {
            if (metadata.analysisSnapshotId() != null) documentProperties.put("taxonomy.snapshotId", VisioProperty.text(metadata.analysisSnapshotId()));
            if (metadata.analysisSnapshotFingerprintSha256() != null) {
                documentProperties.put("taxonomy.analysisSha256", VisioProperty.text(metadata.analysisSnapshotFingerprintSha256()));
            }
        }
        var drawing = diagrams.convert(architecture, new VisioExportMetadata(documentProperties, Map.of(), Map.of(), List.of()),
                new VisioDecisionTree(nodes, german));
        try {
            return new ReportRenderResult(packages.build(drawing));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not export the decision drawings as Visio", failure);
        }
    }
}
