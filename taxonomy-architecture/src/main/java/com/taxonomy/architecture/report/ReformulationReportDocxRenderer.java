package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.reformulation.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

/** POI adapter for the export-owned immutable reformulation and graph contracts. */
@Component
public final class ReformulationReportDocxRenderer implements ReformulationDocxPort {
    @Override public byte[] render(ReformulationReportRenderer.Input report, FrozenReformulationArchitecture architecture) {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            standalonePage(document);
            var w = new WordDocumentWriter(document, new DecisionReportLabels(report.language()));
            var graphNodes = new HashSet<String>();
            architecture.graph().nodes().forEach(node -> graphNodes.add(node.id()));
            var graphEdges = new HashSet<String>();
            architecture.graph().edges().forEach(edge -> graphEdges.add(edge.id()));
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
            report.metadata().forEach((key, value) -> w.paragraph(label(de, key) + ": " + value));
            w.heading(t(de, "Fragen und Antworten", "Questions and answers"), 1, "rf_questions");
            if (report.questions().isEmpty()) w.paragraph(t(de, "Keine Fragen gespeichert; dies bedeutet keine Freigabe.",
                    "No questions recorded; this does not imply approval."));
            for (var question : report.questions()) {
                w.heading(question.id() + " — " + question.wording(), 2, bookmark("question", question.id()));
                w.paragraph("Status: " + question.state() + " · " + t(de, "Antworttyp: ", "Answer type: ")
                        + question.answerSchema().kind() + " · " + t(de, "Optionen: ", "Options: ") + question.answerSchema().options());
                w.paragraph(t(de, "Auswirkungen: ", "Consequences: ") + Objects.toString(question.consequences(), "")
                        + " · " + t(de, "Aussagen: ", "Statements: ") + question.affectedStatementIds());
                question.discoveries().forEach(discovery -> {
                    w.paragraph(t(de, "Entdeckung: ", "Discovery: ")
                            + discovery.location() + " · " + discovery.context() + " · " + discovery.rationale());
                    references(w, discovery.nodeIds(), graphNodes, de);
                    references(w, discovery.edgeIds(), graphEdges, de);
                });
                question.origins().forEach(origin -> {
                    w.paragraph(t(de, "Ursprungsfrage: ", "Origin question: ")
                            + origin.id() + " · " + origin.wording());
                    w.paragraph(t(de, "Ursprungsstatus: ", "Origin state: ") + origin.state()
                            + " · " + t(de, "Thema: ", "Subject: ") + origin.key().subject()
                            + " · " + t(de, "Dimension: ", "Dimension: ") + origin.key().dimension()
                            + " · " + t(de, "Geltungsbereich: ", "Scope: ") + origin.key().scope());
                    var schema = origin.answerSchema();
                    w.paragraph(t(de, "Antworttyp: ", "Answer type: ") + schema.kind()
                            + " · " + t(de, "Optionen: ", "Options: ") + schema.options()
                            + " · " + t(de, "Einheit: ", "Unit: ") + Objects.toString(schema.unit(), "—")
                            + " · " + t(de, "Minimum: ", "Minimum: ") + Objects.toString(schema.minimum(), "—")
                            + " · " + t(de, "Maximum: ", "Maximum: ") + Objects.toString(schema.maximum(), "—"));
                    w.paragraph(t(de, "Optionsbedeutungen: ", "Option meanings: ") + schema.optionMeanings()
                            + " · " + t(de, "Unvereinbare Optionen: ", "Incompatible options: ")
                            + schema.incompatibleOptions()
                            + " · " + t(de, "Anwendbarkeit: ", "Applicability: ")
                            + schema.applicability().stream().map(condition -> condition.questionId()
                                    + " → " + condition.anyOf()).toList());
                    w.paragraph(t(de, "Auswirkungen: ", "Consequences: ")
                            + Objects.toString(origin.consequences(), "")
                            + " · " + t(de, "Aussagen: ", "Statements: ") + origin.affectedStatementIds()
                            + " · " + t(de, "Vorausgesetzte Fragen: ", "Prerequisite questions: ")
                            + origin.prerequisites()
                            + " · " + t(de, "Abhängige Fragen: ", "Dependent questions: ")
                            + origin.dependentQuestionIds());
                    origin.discoveries().forEach(discovery -> {
                        w.paragraph(t(de, "Entdeckt bei: ", "Discovered at: ") + discovery.location()
                                + " · " + t(de, "Kontext: ", "Context: ") + discovery.context()
                                + " · " + t(de, "Begründung: ", "Rationale: ") + discovery.rationale()
                                + " · " + t(de, "Quellstellen: ", "Source spans: ") + discovery.sourceSpans());
                        references(w, discovery.nodeIds(), graphNodes, de);
                        references(w, discovery.edgeIds(), graphEdges, de);
                    });
                });
                question.sourceResolutions().forEach(resolution -> w.paragraph(t(de, "Aus Original beantwortet: ", "Resolved from source: ")
                        + resolution.values() + " · " + resolution.rationale() + " · " + resolution.sourceSpans()));
            }
            w.heading(t(de, "Gespeicherte Antworten und Verlauf", "Saved answers and history"), 2, null);
            var active = com.taxonomy.reformulation.DecisionAnswer.active(report.answers());
            for (var answer : report.answers()) w.paragraph(answer.questionId() + " · " + answer.state()
                    + (active.contains(answer) ? t(de, " · wirksam", " · active")
                            : t(de, " · ersetzt", " · superseded")) + " · " + answer.values()
                    + " · " + Objects.toString(answer.otherText(), "") + " · " + answer.author() + " · "
                    + answer.occurredAt() + " · " + answer.rationale() + " · " + answer.disposition()
                    + " · " + t(de, "ersetzt ", "supersedes ") + answer.supersedes());
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
                        + Objects.toString(statement.conditionalValidity(), "") + " · "
                        + t(de, "Architektur ", "architecture ") + statement.architectureLinks()
                        + " · " + t(de, "Fragen ", "questions ") + statement.questionDependencies()
                        + " · " + t(de, "Quellstellen ", "source ") + statement.sourceSpans());
                references(w, statement.architectureLinks(), graphNodes, graphEdges, de);
            }
            w.heading(t(de, "Prüfung und übernommene Evidenz", "Validation and inherited evidence"), 1, "rf_validation");
            if (report.validation().findings().isEmpty()) w.paragraph(t(de,
                    "Keine Prüfbefunde gespeichert; dies bedeutet keine fachliche Freigabe.",
                    "No validation findings recorded; this does not imply expert approval."));
            report.validation().findings().forEach(f -> w.paragraph(f.kind() + " · " + f.code() + " · "
                    + f.message() + " · " + f.statementIds() + " · " + f.sourceSpans()));
            // The raw archive remains in JSON export. Word presents the reviewable evidence above.
            w.heading(t(de, "Gespeicherte Architektur — Kontext", "Saved architecture — context"), 1, null);
            architecture.identity().forEach((key, value) -> w.paragraph(label(de, key) + ": " + value));
            w.paragraph(architecture.gapAnalysisAvailable()
                    ? t(de, "Lückenanalyse gespeichert", "Gap analysis recorded")
                    : t(de, "Lückenanalyse nicht verfügbar — nicht als lückenfrei zu interpretieren",
                            "Gap analysis unavailable — not evidence of completeness"));
            architecture.warnings().forEach(w::paragraph);
            architecture.elementDetails().forEach(w::paragraph);
            architecture.relationDetails().forEach(w::paragraph);
            var graph = architecture.graph();
            w.heading(t(de, "Nachweisbare Architekturbezüge", "Navigable architecture references"), 2, null);
            for (var node : graph.nodes()) {
                var p = w.paragraph(node.id() + " — " + node.label());
                w.bookmark(p, referenceBookmark(node.id()));
            }
            for (var edge : graph.edges()) {
                var p = w.paragraph(edge.id() + " · " + edge.sourceId() + " → " + edge.targetId()
                        + " · " + edge.relationType());
                w.bookmark(p, referenceBookmark(edge.id()));
            }
            if (graph.nodes().isEmpty()) {
                w.heading(t(de, "Gespeicherte Architektur", "Saved architecture"), 1, "architecture_figures");
                w.paragraph(t(de, "Kein Architekturgraph im ausgewählten Snapshot gespeichert.",
                        "No architecture graph recorded in the selected snapshot."));
                if (architecture.gapAnalysisAvailable()) {
                    w.heading(t(de, "Gespeicherte Lücken", "Saved gaps"), 2, null);
                    if (architecture.gaps().isEmpty()) w.paragraph(t(de, "Keine Lücken gespeichert.", "No gaps recorded."));
                    else architecture.gaps().forEach(w::paragraph);
                }
                w.heading(t(de, "Architekturbeleg", "Architecture provenance"), 1, "architecture_evidence");
                w.paragraph("SHA-256: " + ArchitectureReportDocument.graphSha256(graph));
                document.write(output);
                return output.toByteArray();
            }
            var source = architecture.source();
            var evidence = new ArchitectureReportDocument.SnapshotEvidence(
                    source == null ? null : source.projectId(), source == null ? null : source.requirementId(),
                    source == null ? null : source.versionId(), source == null ? null : source.versionNumber(),
                    architecture.identity().get("Snapshot"), architecture.identity().get("Repository"),
                    architecture.identity().get("Workspace"), architecture.identity().get("Analysis based-on branch"),
                    architecture.identity().get("Analysis based-on commit"), source == null ? null : source.provider(),
                    source == null ? null : source.model(), source == null ? null : source.taxonomyFingerprint(),
                    ArchitectureReportDocument.graphSha256(graph));
            var architectureDocument = ArchitectureReportDocument.from(title, report.language(), report.originalText(),
                    architecture.identity().entrySet().stream()
                            .map(entry -> label(de, entry.getKey()) + ": " + entry.getValue())
                            .collect(java.util.stream.Collectors.joining("; ")),
                    t(de, "Historischer Stand; keine fachliche Freigabe.",
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
    private static void standalonePage(XWPFDocument document) {
        var styles = document.createStyles();
        var fonts = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts.Factory.newInstance();
        fonts.setAscii("Aptos"); fonts.setHAnsi("Aptos"); fonts.setCs("Aptos"); fonts.setEastAsia("Aptos");
        styles.setDefaultFonts(fonts);
        styles.getDefaultRunStyle().getRPr().addNewSz().setVal(BigInteger.valueOf(20));
        var section = document.getDocument().getBody().addNewSectPr();
        var size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(11906)); size.setH(BigInteger.valueOf(16838));
        var margin = section.addNewPgMar();
        margin.setLeft(BigInteger.valueOf(1050)); margin.setRight(BigInteger.valueOf(1050));
        margin.setTop(BigInteger.valueOf(1020)); margin.setBottom(BigInteger.valueOf(1160));
        margin.setHeader(BigInteger.valueOf(420)); margin.setFooter(BigInteger.valueOf(560));
    }
    private static String label(boolean de, String key) {
        if (!de) return key;
        return switch (key) {
            case "Proposal" -> "Angebot";
            case "Project / Requirement" -> "Projekt / Anforderung";
            case "Source version" -> "Quellversion";
            case "Source SHA-256" -> "Quell-SHA-256";
            case "Analysis snapshot" -> "Analyse-Snapshot";
            case "Proposal revision" -> "Angebotsrevision";
            case "Saved by" -> "Gespeichert von";
            case "Saved at" -> "Gespeichert am";
            case "Revision rationale" -> "Begründung der Revision";
            case "Adoption command" -> "Übernahmebefehl";
            case "Adopted by" -> "Übernommen von";
            case "Adopted at" -> "Übernommen am";
            case "Adoption rationale" -> "Begründung der Übernahme";
            case "Target version" -> "Zielversion";
            case "Warnings acknowledged" -> "Warnungen bestätigt";
            case "Unresolved question IDs" -> "IDs offener Fragen";
            case "Preview SHA-256" -> "Vorschau-SHA-256";
            case "Inherited decision context" -> "Übernommener Entscheidungskontext";
            case "Snapshot" -> "Snapshot";
            case "Repository" -> "Repository";
            case "Workspace" -> "Arbeitsbereich";
            case "Offer workspace branch" -> "Angebotszweig";
            case "Snapshot branch" -> "Snapshot-Zweig";
            case "Analysis based-on branch" -> "Analyse-Ausgangszweig";
            case "Analysis based-on commit" -> "Analyse-Ausgangs-Commit";
            case "Includes provisional relations" -> "Vorläufige Beziehungen enthalten";
            case "Projection stale" -> "Projektion veraltet";
            case "Index stale" -> "Index veraltet";
            case "Provider / model" -> "Anbieter / Modell";
            case "Status" -> "Status";
            default -> key;
        };
    }
    private static String t(boolean de, String german, String english) { return de ? german : english; }
    private static String bookmark(String kind, String id) {
        return "rf_" + kind + "_" + Integer.toUnsignedString(id.hashCode(), 36);
    }
    private static void references(WordDocumentWriter writer, List<String> ids, Set<String> nodes, boolean de) {
        references(writer, ids, nodes, Set.of(), de);
    }
    private static void references(WordDocumentWriter writer, List<String> ids, Set<String> nodes,
            Set<String> edges, boolean de) {
        for (String id : ids) {
            if (nodes.contains(id) || edges.contains(id)) {
                writer.link(writer.paragraph(t(de, "Architekturbezug: ", "Architecture reference: ")),
                        referenceBookmark(id), id);
            } else {
                writer.paragraph(t(de, "Bezug nicht im gespeicherten Graph verfügbar: ",
                        "Reference unavailable in saved graph: ") + id);
            }
        }
    }
    private static String referenceBookmark(String id) {
        StringBuilder out = new StringBuilder("rf_ref_");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-') out.append('_');
            else if (c == '_') out.append("_u");
            else if (Character.isLetterOrDigit(c) && c < 128) out.append(c);
            else out.append("_x").append(Integer.toHexString(c));
        }
        return out.length() <= 40 ? out.toString()
                : "rf_ref_" + Integer.toUnsignedString(id.hashCode(), 36);
    }
}
