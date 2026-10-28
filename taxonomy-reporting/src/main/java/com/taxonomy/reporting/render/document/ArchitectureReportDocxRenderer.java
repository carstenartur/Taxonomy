package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.api.document.ArchitectureReportDocument;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
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
            String snapshot = report.evidence().snapshotId();
            String shortSnapshot = snapshot == null ? labels.unknown()
                    : snapshot.substring(0, Math.min(12, snapshot.length()));
            runningIdentity(document, labels, labels.architectureTitle() + " · " + shortSnapshot
                    + " · " + labels.requirementVersion() + " " + report.evidence().requirementVersionNumber());
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
            writer.completeContents();
            document.write(output);
            return output.toByteArray();
        } catch (com.taxonomy.reporting.render.document.WordReportLayoutException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render architecture Word report", exception);
        }
    }

    public byte[] render(ArchitectureReport report) {
        return render(report, "en");
    }

    public byte[] render(ArchitectureReport report, String language) {
        return render(report, language, Map.of());
    }

    public byte[] render(ArchitectureReport report, String language, Map<String, String> nodeReasons) {
        try (var document = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            page(document);
            var labels = new DecisionReportLabels(language);
            var w = new WordDocumentWriter(document, labels);
            runningIdentity(document, labels, labels.architectureTitle());
            w.heading(labels.architectureTitle(), 0, null);
            w.heading(labels.requirement(), 1, null);
            w.paragraph(report.getBusinessText());
            if (report.getCopilot() != null) {
                w.paragraph("Copilot: " + labels.retainedCopilotStatus(report.getCopilot().state()));
                report.getCopilot().notes().forEach(w::paragraph);
            } else w.paragraph(labels.liveReportNotice());
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
                    var figure = renderer.render(panel, language);
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
                                                        value(e.getNodeCode(), labels),
                                                        value(e.getTitle(), labels),
                                                        percent(e.getRelevance()),
                                                        value(e.getIncludedBecause(), labels)))
                                .toList());
            }
            if (nodeReasons != null && !nodeReasons.isEmpty()) {
                w.heading(labels.recordedNodeReasons(), 1, null);
                nodeReasons.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    w.heading(entry.getKey(), 2, null);
                    w.paragraph(entry.getValue());
                });
            }
            if (view != null && !view.getIncludedRelationships().isEmpty()) {
                w.heading(labels.relations(), 1, null);
                w.table(
                        List.of(labels.identifier(), labels.source(), labels.target(), labels.type(),
                                labels.category(), labels.rationale()),
                        view.getIncludedRelationships().stream()
                                .map(e -> List.of(
                                        e.getRelationId() == null ? labels.unknown() : e.getRelationId().toString(),
                                        value(e.getSourceCode(), labels), value(e.getTargetCode(), labels),
                                        value(e.getRelationType(), labels), value(e.getRelationCategory(), labels),
                                        ArchitectureReportTextRenderer.relationshipReasons(e, labels)))
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
                                                        value(e.getSourceNodeCode(), labels),
                                                        value(e.getExpectedRelationType(), labels),
                                                        value(e.getExpectedTargetRoot(), labels),
                                                        value(e.getDescription(), labels)))
                                .toList());
                w.table(
                        List.of(labels.node(), labels.titleLabel(), labels.gaps()),
                        gaps.getIncompletePatterns().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getNodeCode(), labels),
                                                        value(e.getPatternDescription(), labels),
                                                        value(e.getMissingElement(), labels)))
                                .toList());
                gaps.getNotes().forEach(w::paragraph);
            }
            var patterns = report.getPatternDetection();
            if (patterns != null) {
                w.heading(labels.detectedPatterns(), 1, null);
                patterns.getNotes().forEach(w::paragraph);
                w.paragraph(labels.patternCoverage() + ": " + String.format(Locale.ROOT, "%.1f%%",
                        boundedPatternPercentage(patterns.getPatternCoverage())));
                var all = new ArrayList<DetectedPattern>(patterns.getMatchedPatterns());
                all.addAll(patterns.getIncompletePatterns());
                w.table(
                        List.of(labels.titleLabel(), labels.completeness(), labels.gaps()),
                        all.stream()
                                .map(
                                        e ->
                                                List.of(
                                                        value(e.getPatternName(), labels),
                                                        String.format(
                                                                Locale.ROOT,
                                                                "%.0f%%",
                                                                boundedPatternPercentage(e.getCompleteness())),
                                                        String.join(", ", e.getMissingSteps())))
                                .toList());
                for (var pattern : all) {
                    if (pattern.getExpectedSteps().isEmpty() && pattern.getPresentSteps().isEmpty()) continue;
                    w.heading(pattern.getPatternName(), 2, null);
                    if (!pattern.getExpectedSteps().isEmpty()) w.paragraph(labels.expectedSteps() + ": "
                            + String.join(", ", pattern.getExpectedSteps()));
                    if (!pattern.getPresentSteps().isEmpty()) w.paragraph(labels.retainedSteps() + ": "
                            + String.join(", ", pattern.getPresentSteps()));
                }
            }
            var recommendation = report.getRecommendation();
            if (recommendation != null) {
                w.heading(labels.recommendation(), 1, null);
                recommendation.getReasoning().forEach(w::paragraph);
                w.paragraph(labels.confidence() + ": " + String.format(Locale.ROOT, "%.1f%%", recommendation.getConfidence()));
                if (!recommendation.getConfirmedElements().isEmpty()) {
                    w.heading(labels.matchedElements(), 2, null);
                    w.paragraph(labels.matchedElementNotice());
                    recommendationElements(w, labels, recommendation.getConfirmedElements());
                }
                if (!recommendation.getProposedElements().isEmpty()) {
                    w.heading(labels.proposedElements(), 2, null);
                    recommendationElements(w, labels, recommendation.getProposedElements());
                }
                if (!recommendation.getSuggestedRelations().isEmpty()) {
                    w.heading(labels.suggestedRelations(), 2, null);
                    w.table(
                            List.of(labels.source(), labels.target(), labels.type(), labels.rationale()),
                            recommendation.getSuggestedRelations().stream()
                                    .map(e -> List.of(value(e.getSourceCode(), labels), value(e.getTargetCode(), labels),
                                            value(e.getRelationType(), labels), value(e.getReasoning(), labels)))
                                    .toList());
                }
                recommendation.getNotes().forEach(w::paragraph);
            }
            if (report.getPendingProposals() != null && !report.getPendingProposals().isEmpty()) {
                w.heading(labels.pendingRelationProposals(), 1, null);
                w.table(
                        List.of(
                                labels.identifier(),
                                labels.source(),
                                labels.target(),
                                labels.type(),
                                labels.confidence(),
                                labels.status(),
                                labels.rationale()),
                        report.getPendingProposals().stream()
                                .map(
                                        e ->
                                                List.of(
                                                        e.getId() == null ? labels.unknown() : e.getId().toString(),
                                                        value(e.getSourceCode(), labels),
                                                        value(e.getTargetCode(), labels),
                                                        value(e.getRelationType(), labels),
                                                        percent(e.getConfidence()),
                                                        value(e.getStatus(), labels),
                                                        ArchitectureReportTextRenderer.proposalReasons(e, labels)))
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
        } catch (com.taxonomy.reporting.render.document.WordReportLayoutException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not render legacy architecture Word report", exception);
        }
    }

    private static void recommendationElements(WordDocumentWriter writer, DecisionReportLabels labels,
            List<RecommendedElement> elements) {
        writer.table(
                List.of(labels.node(), labels.titleLabel(), labels.taxonomyRoot(), labels.score(), labels.rationale()),
                elements.stream().map(e -> List.of(value(e.getNodeCode(), labels), value(e.getTitle(), labels),
                        value(e.getTaxonomyRoot(), labels), e.getScore() + "%", value(e.getReasoning(), labels))).toList());
    }

    /** Pattern DTO percentages already use the 0–100 scale, unlike element relevance. */
    private static double boundedPatternPercentage(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(100.0, value)) : 0.0;
    }

    private static String value(String text, DecisionReportLabels labels) {
        return text == null ? labels.unknown() : text;
    }

    private static String percent(double value) {
        return com.taxonomy.dto.AnalysisPercentages.display(value * 100);
    }

    private static void runningIdentity(XWPFDocument document, DecisionReportLabels labels, String identity) {
        var header = document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
        var headerParagraph = header.createParagraph();
        var run = headerParagraph.createRun();
        run.setFontSize(9);
        run.setText(identity);
        var footer = document.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
        var paragraph = footer.createParagraph();
        paragraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
        paragraph.createRun().setText(labels.page() + " ");
        paragraph.getCTP().addNewFldSimple().setInstr("PAGE");
        paragraph.createRun().setText(" " + labels.of() + " ");
        paragraph.getCTP().addNewFldSimple().setInstr("NUMPAGES");
        paragraph.getRuns().forEach(r -> r.setFontSize(9));
    }

    private static void page(XWPFDocument document) {
        // Only fresh standalone documents reach this setup; custom DOTX styles stay authoritative.
        var styles = document.createStyles();
        var fonts = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts.Factory.newInstance();
        fonts.setAscii("Aptos");
        fonts.setHAnsi("Aptos");
        fonts.setCs("Aptos");
        fonts.setEastAsia("Aptos");
        styles.setDefaultFonts(fonts);
        styles.getDefaultRunStyle().getRPr().addNewSz().setVal(BigInteger.valueOf(20));
        var section = document.getDocument().getBody().addNewSectPr();
        var size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        var margin = section.addNewPgMar();
        margin.setLeft(BigInteger.valueOf(1050));
        margin.setRight(BigInteger.valueOf(1050));
        margin.setTop(BigInteger.valueOf(1020));
        margin.setBottom(BigInteger.valueOf(1160));
        margin.setHeader(BigInteger.valueOf(420));
        margin.setFooter(BigInteger.valueOf(560));
    }
}
