package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.reformulation.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** POI adapter for the export-owned immutable reformulation and graph contracts. */
@Component
public final class ReformulationReportDocxRenderer implements ReformulationDocxPort {
    @Override public byte[] render(ReformulationReportRenderer.Input report, FrozenReformulationArchitecture architecture) {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var w = new WordDocumentWriter(document, new DecisionReportLabels(report.language()));
            boolean de = report.language().equals("de");
            String title = report.adoptionReceipt() ? t(de, "Neuformulierung — Übernahmebeleg", "Reformulation — adoption receipt")
                    : t(de, "Neuformulierungsangebot — gespeicherte Revision", "Reformulation offer — saved revision");
            document.getProperties().getCoreProperties().setTitle(title);
            w.heading(title, 0, null);
            w.paragraph(report.adoptionReceipt()
                    ? t(de, "Dokumentierte Übernahme zum angegebenen Zeitpunkt. Keine Aussage über die heute aktive Version oder fachliche Freigabe.",
                            "Documented adoption at the stated time. Not a claim about today's active version or expert approval.")
                    : t(de, "Gespeicherter Vorschlag — nicht übernommen. Spätere Entscheidungen gehören nicht zu diesem Stand.",
                            "Saved proposal — not adopted. Later decisions are not part of this record."));
            var contents = new LinkedHashMap<String, String>();
            contents.put("rf_source", t(de, "Original und Vorschlag", "Original and proposal"));
            contents.put("rf_questions", t(de, "Fragen und Antworten", "Questions and answers"));
            contents.put("rf_statements", t(de, "Aussagen und Herkunft", "Statements and provenance"));
            contents.put("rf_validation", t(de, "Prüfung und übernommene Evidenz", "Validation and inherited evidence"));
            contents.put("architecture_figures", t(de, "Gespeicherte Architektur", "Saved architecture"));
            contents.put("architecture_evidence", t(de, "Architekturbeleg", "Architecture provenance"));
            w.contents(contents);
            w.heading(t(de, "Original und Vorschlag", "Original and proposal"), 1, "rf_source");
            w.heading(t(de, "Originalanforderung", "Original requirement"), 2, null);
            w.paragraph(report.originalText());
            w.heading(t(de, "Vollständiger Vorschlagstext", "Complete proposed text"), 2, null);
            w.paragraph(report.reviewedText());
            report.metadata().forEach((key, value) -> w.paragraph(key + ": " + value));
            w.heading(t(de, "Fragen und Antworten", "Questions and answers"), 1, "rf_questions");
            if (report.questions().isEmpty()) w.paragraph(t(de, "Keine Fragen gespeichert; dies bedeutet keine Freigabe.",
                    "No questions recorded; this does not imply approval."));
            for (var question : report.questions()) {
                w.heading(question.id() + " — " + question.wording(), 2, bookmark("question", question.id()));
                w.paragraph("Status: " + question.state() + " · " + t(de, "Antworttyp: ", "Answer type: ")
                        + question.answerSchema().kind() + " · " + t(de, "Optionen: ", "Options: ") + question.answerSchema().options());
                w.paragraph(t(de, "Auswirkungen: ", "Consequences: ") + Objects.toString(question.consequences(), "")
                        + " · " + t(de, "Aussagen: ", "Statements: ") + question.affectedStatementIds());
                question.discoveries().forEach(discovery -> w.paragraph(t(de, "Entdeckung: ", "Discovery: ")
                        + discovery.location() + " · " + discovery.context() + " · " + discovery.rationale()
                        + " · nodes " + discovery.nodeIds() + " · edges " + discovery.edgeIds()));
                question.origins().forEach(origin -> w.paragraph(t(de, "Ursprungsfrage: ", "Origin question: ")
                        + origin.id() + " · " + origin.wording() + " · " + origin.discoveries()));
                question.sourceResolutions().forEach(resolution -> w.paragraph(t(de, "Aus Original beantwortet: ", "Resolved from source: ")
                        + resolution.values() + " · " + resolution.rationale() + " · " + resolution.sourceSpans()));
            }
            w.heading(t(de, "Gespeicherte Antworten und Verlauf", "Saved answers and history"), 2, null);
            var active = com.taxonomy.reformulation.DecisionAnswer.active(report.answers());
            for (var answer : report.answers()) w.paragraph(answer.questionId() + " · " + answer.state()
                    + (active.contains(answer) ? " · active" : " · superseded") + " · " + answer.values()
                    + " · " + Objects.toString(answer.otherText(), "") + " · " + answer.author() + " · "
                    + answer.occurredAt() + " · " + answer.rationale() + " · " + answer.disposition()
                    + " · supersedes " + answer.supersedes());
            w.heading(t(de, "Aussagen und Herkunft", "Statements and provenance"), 1, "rf_statements");
            for (var section : report.sections()) {
                w.heading(section.id() + " — " + section.title(), 2, bookmark("section", section.id()));
                w.paragraph(section.summary() + " · " + section.taxonomyCode() + " · " + section.children());
                w.paragraph(t(de, "Aussagen: ", "Statements: ") + section.statementIds()
                        + " · " + t(de, "Fragen: ", "Questions: ") + section.questionIds());
            }
            for (var statement : report.statements()) {
                w.heading(statement.id() + " — " + statement.provenance(), 2, bookmark("statement", statement.id()));
                w.paragraph(statement.wording());
                w.paragraph(statement.reviewState() + " · " + statement.editingOrigin() + " · "
                        + Objects.toString(statement.conditionalValidity(), "") + " · architecture "
                        + statement.architectureLinks() + " · questions " + statement.questionDependencies()
                        + " · source " + statement.sourceSpans());
            }
            w.heading(t(de, "Prüfung und übernommene Evidenz", "Validation and inherited evidence"), 1, "rf_validation");
            report.validation().findings().forEach(f -> w.paragraph(f.kind() + " · " + f.code() + " · "
                    + f.message() + " · " + f.statementIds() + " · " + f.sourceSpans()));
            // The raw archive remains in JSON export. Word presents the reviewable evidence above.
            w.heading(t(de, "Gespeicherte Architektur — Kontext", "Saved architecture — context"), 1, null);
            architecture.identity().forEach((key, value) -> w.paragraph(key + ": " + value));
            w.paragraph(architecture.gapAnalysisAvailable()
                    ? t(de, "Lückenanalyse gespeichert", "Gap analysis recorded")
                    : t(de, "Lückenanalyse nicht verfügbar — nicht als lückenfrei zu interpretieren",
                            "Gap analysis unavailable — not evidence of completeness"));
            architecture.warnings().forEach(w::paragraph);
            architecture.elementDetails().forEach(w::paragraph);
            architecture.relationDetails().forEach(w::paragraph);
            var graph = architecture.graph();
            if (graph.nodes().isEmpty()) {
                w.heading(t(de, "Gespeicherte Architektur", "Saved architecture"), 1, "architecture_figures");
                w.paragraph(t(de, "Kein Architekturgraph im ausgewählten Snapshot gespeichert.",
                        "No architecture graph recorded in the selected snapshot."));
                w.heading(t(de, "Architekturbeleg", "Architecture provenance"), 1, "architecture_evidence");
                w.paragraph("SHA-256: " + ArchitectureReportDocument.graphSha256(graph));
                document.write(output);
                return output.toByteArray();
            }
            var evidence = new ArchitectureReportDocument.SnapshotEvidence(null, null, null, null,
                    architecture.identity().get("Snapshot"), architecture.identity().get("Repository"),
                    architecture.identity().get("Workspace"), architecture.identity().get("Analysis based-on branch"),
                    architecture.identity().get("Analysis based-on commit"), architecture.identity().get("Provider / model"),
                    null, null, ArchitectureReportDocument.graphSha256(graph));
            var architectureDocument = ArchitectureReportDocument.from(title, report.language(), report.originalText(),
                    architecture.identity().toString(), t(de, "Historischer Stand; keine fachliche Freigabe.",
                            "Historical record; no expert approval."),
                    architecture.gapAnalysisAvailable() ? architecture.gaps()
                            : List.of(t(de, "Nicht verfügbar", "Unavailable")), graph,
                    new LayeredDiagramLayoutService().layout(graph), null, evidence);
            new ArchitectureWordSectionRenderer().write(document, architectureDocument);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render frozen reformulation Word report", exception);
        }
    }
    private static String t(boolean de, String german, String english) { return de ? german : english; }
    private static String bookmark(String kind, String id) {
        return "rf_" + kind + "_" + Integer.toUnsignedString(id.hashCode(), 36);
    }
}
