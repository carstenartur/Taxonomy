package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.api.document.ArchitectureReportDocument;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.util.*;

/** The same architecture evidence is embedded in both snapshot Word reports. */
public final class ArchitectureWordSectionRenderer {
    public void write(XWPFDocument document, ArchitectureReportDocument report) throws Exception {
        var labels = new DecisionReportLabels(report.languageTag());
        var writer = new WordDocumentWriter(document, labels);
        writer.properties(report);
        writer.heading(labels.requirement(), 1, "architecture_requirement");
        writer.paragraph(report.requirement());
        writer.heading(labels.scope(), 2, null);
        writer.paragraph(report.scope());
        ReportInventory.diagram(report.diagram(), labels.german()).forEach(writer::paragraph);
        writer.heading(labels.recommendation(), 2, null);
        writer.paragraph(report.recommendation());
        writer.heading(labels.gaps(), 2, null);
        writer.table(
                List.of(labels.gaps()),
                (report.unresolvedGaps().isEmpty()
                                ? List.of(labels.noneRecorded())
                                : report.unresolvedGaps())
                        .stream().map(List::of).toList());
        writer.heading(labels.architectureOverview(), 1, "architecture_figures");
        if (!report.diagram().title().equals(report.title()))
            writer.paragraph(report.diagram().title());
        var plan = new ArchitectureFigurePlanner().plan(report.diagram(), report.scene());
        var renderer = new ArchitectureFigureRenderer();
        var panels = new ArrayList<ArchitectureFigurePlanner.Panel>();
        panels.add(plan.overview());
        panels.addAll(plan.details());
        for (var panel : panels) {
            var figure = renderer.render(panel, report.languageTag());
            writer.picture(
                    figure.png(),
                    figure.id(),
                    figure.caption(),
                    figure.altDescription(),
                    figure.width(),
                    figure.height(),
                    !panel.overview());
            writer.caption(figure.caption(), true);
            if (!panel.overview() && !panel.diagram().edges().isEmpty()) {
                var key =
                        writer.table(
                                List.of(
                                        labels.identifier(),
                                        labels.source() + " → " + labels.target(),
                                        labels.type(),
                                        labels.category()),
                                panel.diagram().edges().stream()
                                        .map(
                                                edge ->
                                                        List.of(
                                                                edge.id(),
                                                                edge.sourceId()
                                                                        + " → "
                                                                        + edge.targetId(),
                                                                value(edge.relationType(), labels),
                                                                value(
                                                                        edge.relationCategory(),
                                                                        labels)
                                                                        + " · " + com.taxonomy.export.DiagramPresentation
                                                                                .relationshipStatus(edge.metadata())))
                                        .toList());
                writer.columnWidths(key, 12, 32, 32, 24);
            }
        }
        writer.heading(labels.legend(), 2, null);
        writer.paragraph(labels.relationKeyNotice());
        writer.caption(labels.legend(), false);
        writer.table(
                List.of(labels.identifier(), labels.titleLabel()),
                report.legend().stream().map(e -> List.of(e.symbol(), e.meaning())).toList());
        writer.heading(labels.elements(), 1, "architecture_elements");
        writer.caption(labels.elements(), false);
        writer.table(
                List.of(
                        labels.identifier(),
                        labels.titleLabel(),
                        labels.type(),
                        labels.score(),
                        labels.parentNode()),
                report.elements().stream()
                        .map(
                                e ->
                                        List.of(
                                                e.id(),
                                                value(e.title(), labels),
                                                value(e.type(), labels)
                                                        + (e.container()
                                                                ? " · " + labels.container()
                                                                : "")
                                                        + (e.anchor()
                                                                ? " · " + labels.anchor()
                                                                : ""),
                                                percentage(e.relevance()),
                                                value(e.parentId(), labels)))
                        .toList());
        writer.heading(labels.relations(), 1, "architecture_relations");
        writer.caption(labels.relations(), false);
        var metadataById = new HashMap<String, com.taxonomy.diagram.DiagramRelationMetadata>();
        report.diagram().edges().forEach(edge -> metadataById.put(edge.id(), edge.metadata()));
        writer.table(
                List.of(
                        labels.identifier(),
                        labels.source(),
                        labels.target(),
                        labels.type(),
                        labels.category(),
                        labels.score()),
                report.relations().stream()
                        .map(
                                e ->
                                        List.of(
                                                e.id(),
                                                e.sourceId(),
                                                e.targetId(),
                                                value(e.type(), labels),
                                                value(e.category(), labels) + " · "
                                                        + com.taxonomy.export.DiagramPresentation.relationshipStatus(metadataById.get(e.id())),
                                                percentage(e.relevance())))
                        .toList());
        if (report.diagram().edges().stream().anyMatch(edge -> edge.metadata() != null)) {
            writer.heading(labels.german() ? "Beziehungsherkunft" : "Relationship provenance", 2, null);
            for (var edge : report.diagram().edges()) {
                if (edge.metadata() == null) continue;
                writer.heading(edge.id() + " · " + edge.sourceId() + " → " + edge.targetId(), 2, null);
                // One point between evidence fields; body type remains the writer's readable 10pt.
                relationshipDetails(edge.metadata()).forEach(detail -> writer.paragraph(detail).setSpacingAfter(20));
            }
        }
        writer.heading(labels.provenance(), 1, "architecture_evidence");
        var e = report.evidence();
        writer.table(
                List.of(labels.titleLabel(), labels.identifier()),
                List.of(
                        row(labels.project(), e.projectId()),
                        row(labels.requirementId(), e.requirementId()),
                        row(
                                labels.requirementVersion(),
                                e.requirementVersionId() + " / v" + e.requirementVersionNumber()),
                        row(labels.analysisSnapshot(), e.snapshotId()),
                        row(labels.graphDigest(), e.graphSha256()),
                        row(labels.repository(), value(e.repositoryId(), labels)),
                        row(labels.workspace(), value(e.workspaceId(), labels)),
                        row(labels.branch(), value(e.branch(), labels)),
                        row(labels.basedOnCommit(), value(e.commit(), labels)),
                        row(labels.analysisProvider(), value(e.provider(), labels)),
                        row(labels.analysisModel(), value(e.model(), labels)),
                        row(
                                labels.recordedTaxonomyDigest(),
                                value(e.taxonomyFingerprint(), labels))));
    }

    private List<String> row(String name, Object value) {
        return List.of(name, String.valueOf(value));
    }

    /** Equal recorded reasons share their value while retaining every source-field label. */
    private static List<String> relationshipDetails(com.taxonomy.diagram.DiagramRelationMetadata metadata) {
        var labels = List.of("Included because", "Derivation reason", "Presence reason");
        var values = Arrays.asList(metadata.includedBecause(), metadata.derivationReason(), metadata.presenceReason());
        var grouped = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < labels.size(); i++) {
            String value = values.get(i);
            if (value != null) grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(labels.get(i));
        }
        var result = new ArrayList<String>();
        boolean reasonsWritten = false;
        for (String detail : com.taxonomy.export.DiagramPresentation.relationshipDetails(metadata)) {
            if (labels.stream().anyMatch(label -> detail.startsWith(label + ": "))) {
                if (!reasonsWritten) {
                    grouped.forEach((value, fields) -> result.add(String.join(", ", fields) + ": " + value));
                    reasonsWritten = true;
                }
            } else {
                result.add(detail);
            }
        }
        return result;
    }

    private String value(String text, DecisionReportLabels labels) {
        return text == null ? labels.unknown() : text;
    }

    private String percentage(double score) {
        return com.taxonomy.dto.AnalysisPercentages.display(score * 100);
    }
}
