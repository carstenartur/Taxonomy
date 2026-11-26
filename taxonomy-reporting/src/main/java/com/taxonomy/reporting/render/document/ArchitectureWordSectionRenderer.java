package com.taxonomy.reporting.render.document;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.DiagramPresentation;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.reporting.render.decision.DecisionReportPresentation;

import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.util.*;

/** The same architecture evidence is embedded in both snapshot Word reports. */
public final class ArchitectureWordSectionRenderer {
    public void write(XWPFDocument document, ArchitectureReportDocument report) throws Exception {
        var labels = new DecisionReportLabels(report.languageTag());
        var writer = new WordDocumentWriter(document, labels);
        writer.properties(report);
        writer.heading(labels.requirement(), 1, "architecture_requirement");
        writer.requirementParagraphs(report.requirement());
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
                        .stream().map(value -> com.taxonomy.export.GeneratedDiagnosticText.display(value, labels.german()))
                        .map(List::of).toList());
        writer.heading(labels.architectureOverview(), 1, "architecture_figures");
        if (!report.diagram().title().equals(report.title()))
            writer.paragraph(report.diagram().title());
        writer.paragraph(labels.overviewPurpose());
        var plan = new ArchitectureFigurePlanner().plan(report.diagram(), report.scene());
        var renderer = new ArchitectureFigureRenderer();
        var panels = new ArrayList<ArchitectureFigurePlanner.Panel>();
        panels.add(plan.overview());
        panels.addAll(plan.details());
        for (var panel : panels) {
            var figure = renderer.render(panel, report.languageTag());
            writer.architecturePicture(figure, panel.overview());
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
                                                                labels.relationCategory(edge.relationCategory())
                                                                        + " · " + DiagramPresentation
                                                                                .relationshipStatus(edge.metadata(), labels.german())))
                                        .toList());
                writer.columnWidths(key, 14, 30, 30, 26);
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
        var elements = writer.table(
                List.of(
                        labels.identifier(),
                        labels.titleLabel(),
                        labels.type(),
                        labels.score(),
                        labels.depth(),
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
                                                e.relevance() == null ? labels.notEvaluated() : percentage(e.relevance()),
                                                e.depth() == null ? labels.unknown() : e.depth().toString(),
                                                value(e.parentId(), labels)))
                        .toList());
        writer.columnWidths(elements, 14, 26, 21, 13, 9, 17);
        writeResponsibilities(writer, labels, report.diagram());
        if (!report.nodeReasons().isEmpty()) {
            writer.heading(labels.recordedNodeReasons(), 2, null);
            report.nodeReasons().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                writer.heading(entry.getKey(), 3, null);
                writer.paragraph(DecisionReportPresentation.nodeReason(entry.getValue(), labels));
            });
        }
        writer.heading(labels.relations(), 1, "architecture_relations");
        writer.caption(labels.relations(), false);
        var metadataById = new HashMap<String, com.taxonomy.diagram.DiagramRelationMetadata>();
        report.diagram().edges().forEach(edge -> metadataById.put(edge.id(), edge.metadata()));
        var relations = writer.table(
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
                                                labels.relationCategory(e.category()) + " · "
                                                        + DiagramPresentation.relationshipStatus(metadataById.get(e.id()), labels.german()),
                                                percentage(e.relevance())))
                        .toList());
        writer.columnWidths(relations, 13, 15, 15, 14, 31, 12);
        if (report.diagram().edges().stream().anyMatch(edge -> edge.metadata() != null
                || report.relationReasons().containsKey(edge.id()))) {
            writer.heading(labels.relationshipProvenance(), 2, null);
            for (var edge : report.diagram().edges()) {
                var supplemental = report.relationReasons().get(edge.id());
                if (edge.metadata() == null && supplemental == null) continue;
                writer.heading(edge.id() + " · " + edge.sourceId() + " → " + edge.targetId(), 2, null);
                // One point between evidence fields; body type remains the writer's readable 10pt.
                var details = labels.german() && edge.metadata() != null && !edge.metadata().requirementEvidence().isEmpty()
                        ? DiagramPresentation.relationshipReportDetails(edge.metadata(), supplemental, true)
                        : relationshipDetails(edge.metadata(), supplemental, labels.german());
                details.forEach(detail -> writer.paragraph(detail).setSpacingAfter(20));
            }
        }
        writer.heading(labels.architectureProvenance(), 1, "architecture_evidence");
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
                        row(labels.analysisModel(), value(labels.modelName(e.model()), labels)),
                        row(
                                labels.recordedTaxonomyDigest(),
                                value(e.taxonomyFingerprint(), labels))));
    }

    static void writeResponsibilities(WordDocumentWriter writer, DecisionReportLabels labels, DiagramModel diagram) {
        var assigned = diagram.nodes().stream().filter(node -> node.responsibility() != null)
                .sorted(Comparator.comparing(DiagramNode::id)).toList();
        if (assigned.isEmpty()) return;
        writer.heading(labels.organizationalResponsibilities(), 2, null);
        var table = writer.table(List.of(labels.identifier(), labels.responsibility(),
                        labels.responsibilityUnitId(), labels.responsibilitySource()),
                assigned.stream().map(node -> {
                    var assignment = node.responsibility();
                    return List.of(node.id(), assignment.name(), assignment.unitId(), assignment.sourceId());
                }).toList());
        writer.columnWidths(table, 19, 37, 22, 22);
        for (int index = 0; index < assigned.size(); index++) {
            var paragraph = table.getRow(index + 1).getCell(1).getParagraphs().getFirst();
            var mark = paragraph.insertNewRun(0);
            mark.setFontSize(9);
            mark.setColor(assigned.get(index).responsibility().color().substring(1));
            mark.setText("● ");
        }
    }

    private List<String> row(String name, Object value) {
        return List.of(name, String.valueOf(value));
    }

    /** Equal recorded reasons share their value while retaining every source-field label. */
    private static List<String> relationshipDetails(com.taxonomy.diagram.DiagramRelationMetadata metadata,
            Map<String, String> supplemental, boolean german) {
        var labels = german ? List.of("Aufgenommen weil", "Herleitungsbegründung", "Begründung der Aufnahme")
                : List.of("Included because", "Derivation reason", "Presence reason");
        var values = metadata == null ? Arrays.<String>asList(null, null, null)
                : Arrays.asList(metadata.includedBecause(), metadata.derivationReason(), metadata.presenceReason());
        var grouped = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < labels.size(); i++) {
            String value = values.get(i);
            if (value != null) grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(labels.get(i));
        }
        // Group only exactly equal values within this relationship. Keep distinct wording,
        // every source-field label and the frozen graph metadata unchanged.
        if (supplemental != null) {
            for (String key : List.of("presenceReason", "decisionComment")) {
                String value = supplemental.get(key);
                if (value == null || value.isBlank()) continue;
                String label = key.equals("presenceReason")
                        ? (german ? "Gespeicherte Auswahlbegründung" : "Recorded selection reason")
                        : (german ? "Gespeicherter Prüfkommentar" : "Recorded review comment");
                grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(label);
            }
        }
        var result = new ArrayList<String>();
        boolean reasonsWritten = false;
        var details = metadata == null ? List.<String>of()
                : com.taxonomy.export.DiagramPresentation.relationshipDetails(metadata, german);
        for (String detail : details) {
            if (labels.stream().anyMatch(label -> detail.startsWith(label + ": "))) {
                if (!reasonsWritten) {
                    grouped.forEach((value, fields) -> result.add(String.join(", ", fields) + ": " + value));
                    reasonsWritten = true;
                }
            } else {
                result.add(detail);
            }
        }
        if (!reasonsWritten)
            grouped.forEach((value, fields) -> result.add(String.join(", ", fields) + ": " + value));
        return result;
    }

    private String value(String text, DecisionReportLabels labels) {
        return text == null ? labels.unknown() : text;
    }

    private String percentage(double score) {
        return com.taxonomy.dto.AnalysisPercentages.display(score * 100);
    }
}
