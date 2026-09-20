package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

/** Native Word rendering of frozen documents and a typed adapter for the legacy live report. */
@Component
public final class ArchitectureReportDocxRenderer {
    public byte[] render(ArchitectureReportDocument report) {
        try (var document = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            page(document);
            var labels = new DecisionReportLabels(report.languageTag());
            var writer = new WordDocumentWriter(document, labels);
            document.getProperties().getCoreProperties().setTitle(report.title());
            document.getProperties().getCoreProperties().setSubjectProperty(report.requirement());
            writer.heading(report.title(), 0, null);
            var navigation = new LinkedHashMap<String, String>();
            navigation.put("architecture_requirement", labels.requirement());
            navigation.put("architecture_figures", labels.architectureOverview());
            navigation.put("architecture_elements", labels.elements());
            navigation.put("architecture_relations", labels.relations());
            navigation.put("architecture_evidence", labels.provenance());
            writer.contents(navigation);
            new ArchitectureWordSectionRenderer().write(document, report);
            document.write(output);
            return output.toByteArray();
        } catch (com.taxonomy.architecture.report.WordReportLayoutException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render architecture Word report", exception);
        }
    }

    public byte[] render(ArchitectureReport report) {
        try (var document = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            page(document);
            var labels = new DecisionReportLabels("en");
            var w = new WordDocumentWriter(document, labels);
            w.heading(labels.architectureTitle(), 0, null);
            w.heading(labels.requirement(), 1, null);
            w.paragraph(report.getBusinessText());
            w.paragraph(
                    "Ad-hoc report from the supplied live analysis; no frozen snapshot identity is"
                            + " asserted.");
            w.heading(labels.score(), 1, null);
            w.table(
                    List.of(labels.node(), labels.score()),
                    report.getScores() == null
                            ? List.of()
                            : report.getScores().entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> List.of(e.getKey(), e.getValue() + "%"))
                                    .toList());
            var view = report.getArchitectureView();
            if (view != null
                    && view.getIncludedElements() != null
                    && !view.getIncludedElements().isEmpty()) {
                var graph =
                        PersistedDiagramProjection.project(
                                new DiagramProjectionService(), view, labels.architectureTitle());
                var scene = new LayeredDiagramLayoutService().layout(graph);
                var plan = new ArchitectureFigurePlanner().plan(graph, scene);
                w.heading(labels.architectureOverview(), 1, null);
                w.paragraph(labels.relationKeyNotice());
                var renderer = new ArchitectureFigureRenderer();
                var panels = new ArrayList<ArchitectureFigurePlanner.Panel>();
                panels.add(plan.overview());
                panels.addAll(plan.details());
                for (var panel : panels) {
                    var figure = renderer.render(panel, "en");
                    w.picture(
                            figure.png(),
                            figure.id(),
                            figure.caption(),
                            figure.altDescription(),
                            figure.width(),
                            figure.height(),
                            !panel.overview());
                    w.caption(figure.caption(), true);
                }
                w.heading(labels.elements(), 1, null);
                w.table(
                        List.of(
                                labels.node(),
                                labels.titleLabel(),
                                labels.score(),
                                labels.rationale()),
                        view.getIncludedElements().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getNodeCode()),
                                                        value(e.getTitle()),
                                                        percent(e.getRelevance()),
                                                        value(e.getIncludedBecause())))
                                .toList());
                w.heading(labels.relations(), 1, null);
                w.table(
                        List.of(
                                labels.identifier(),
                                labels.source(),
                                labels.target(),
                                labels.type(),
                                labels.category()),
                        graph.edges().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        e.id(),
                                                        e.sourceId(),
                                                        e.targetId(),
                                                        value(e.relationType()),
                                                        value(e.relationCategory())))
                                .toList());
            }
            var gaps = report.getGapAnalysis();
            if (gaps != null) {
                w.heading(labels.gaps(), 1, null);
                w.table(
                        List.of(
                                labels.source(),
                                labels.type(),
                                labels.target(),
                                labels.rationale()),
                        gaps.getMissingRelations().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getSourceNodeCode()),
                                                        value(e.getExpectedRelationType()),
                                                        value(e.getExpectedTargetRoot()),
                                                        value(e.getDescription())))
                                .toList());
                w.table(
                        List.of(labels.node(), labels.titleLabel(), labels.gaps()),
                        gaps.getIncompletePatterns().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getNodeCode()),
                                                        value(e.getPatternDescription()),
                                                        value(e.getMissingElement())))
                                .toList());
                gaps.getNotes().forEach(w::paragraph);
            }
            var patterns = report.getPatternDetection();
            if (patterns != null) {
                w.heading("Detected patterns", 1, null);
                var all = new ArrayList<DetectedPattern>(patterns.getMatchedPatterns());
                all.addAll(patterns.getIncompletePatterns());
                w.table(
                        List.of(labels.titleLabel(), labels.completeness(), labels.gaps()),
                        all.stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getPatternName()),
                                                        String.format(
                                                                Locale.ROOT,
                                                                "%.0f%%",
                                                                e.getCompleteness()),
                                                        String.join(", ", e.getMissingSteps())))
                                .toList());
            }
            var recommendation = report.getRecommendation();
            if (recommendation != null) {
                w.heading(labels.recommendation(), 1, null);
                recommendation.getReasoning().forEach(w::paragraph);
                var all = new ArrayList<RecommendedElement>(recommendation.getConfirmedElements());
                all.addAll(recommendation.getProposedElements());
                w.table(
                        List.of(
                                labels.node(),
                                labels.titleLabel(),
                                labels.score(),
                                labels.rationale()),
                        all.stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getNodeCode()),
                                                        value(e.getTitle()),
                                                        e.getScore() + "%",
                                                        value(e.getReasoning())))
                                .toList());
                recommendation.getNotes().forEach(w::paragraph);
            }
            if (report.getPendingProposals() != null && !report.getPendingProposals().isEmpty()) {
                w.heading("Pending relation proposals", 1, null);
                w.table(
                        List.of(
                                labels.source(),
                                labels.target(),
                                labels.type(),
                                labels.rationale()),
                        report.getPendingProposals().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getSourceCode()),
                                                        value(e.getTargetCode()),
                                                        value(e.getRelationType()),
                                                        value(e.getRationale())))
                                .toList());
            }
            w.heading(labels.provenance(), 1, null);
            w.paragraph(labels.generatedAt() + ": " + report.getGeneratedAt());
            if (report.getViewContext() != null) {
                w.paragraph(labels.branch() + ": " + report.getViewContext().basedOnBranch());
                w.paragraph(
                        labels.basedOnCommit() + ": " + report.getViewContext().basedOnCommit());
            }
            document.write(output);
            return output.toByteArray();
        } catch (com.taxonomy.architecture.report.WordReportLayoutException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not render legacy architecture Word report", exception);
        }
    }

    private static String value(String text) {
        return text == null ? "Unknown" : text;
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }

    private static void page(XWPFDocument document) {
        var section = document.getDocument().getBody().addNewSectPr();
        var size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        var margin = section.addNewPgMar();
        margin.setLeft(BigInteger.valueOf(1050));
        margin.setRight(BigInteger.valueOf(1050));
        margin.setTop(BigInteger.valueOf(1020));
        margin.setBottom(BigInteger.valueOf(1160));
    }
}
