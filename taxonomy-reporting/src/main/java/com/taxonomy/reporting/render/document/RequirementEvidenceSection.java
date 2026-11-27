package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.EvidenceStatus;
import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import org.springframework.web.util.HtmlUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** Common presentation of the recorded, source-bound node evidence in HTML and Word. */
public final class RequirementEvidenceSection {

    private RequirementEvidenceSection() {}

    public static void appendHtml(StringBuilder html,
            Map<String, List<RequirementNodeAssessment>> assessments, Collection<String> reportNodes,
            DecisionReportLabels labels) {
        boolean german = labels.german();
        html.append("<section class=\"report-section requirement-evidence\"><h2>")
                .append(title(german)).append("</h2><p>").append(summary(assessments, reportNodes, german)).append("</p>");
        if (!assessments.isEmpty()) html.append("<p>").append(reviewNotice(german)).append("</p>");
        assessments.forEach((code, claims) -> {
            html.append("<h3>").append(escape(code)).append("</h3>");
            for (var claim : claims) {
                html.append("<article data-evidence-status=\"").append(claim.evidenceStatus().name()).append("\">")
                        .append("<p><strong style=\"color:#").append(color(claim.evidenceStatus())).append("\">")
                        .append(label(claim.evidenceStatus(), german)).append("</strong> · ")
                        .append(label(claim.contributionType(), german)).append("</p>");
                if (!claim.requirementQuote().isEmpty()) {
                    html.append("<p><strong>").append(originalQuote(german)).append("</strong></p>")
                            .append("<blockquote style=\"white-space:pre-wrap;overflow-wrap:anywhere\">")
                            .append(escape(claim.requirementQuote())).append("</blockquote>");
                }
                html.append("<dl>");
                fields(claim, german, (name, value) -> html.append("<dt>").append(name)
                        .append("</dt><dd style=\"white-space:pre-wrap;overflow-wrap:anywhere\">")
                        .append(escape(value)).append("</dd>"));
                html.append("</dl></article>");
            }
        });
        html.append("</section>");
    }

    public static void writeWord(WordDocumentWriter writer,
            Map<String, List<RequirementNodeAssessment>> assessments, Collection<String> reportNodes,
            DecisionReportLabels labels) {
        boolean german = labels.german();
        writer.heading(title(german), 1, null);
        writer.paragraph(summary(assessments, reportNodes, german));
        if (!assessments.isEmpty()) writer.paragraph(reviewNotice(german));
        assessments.forEach((code, claims) -> {
            writer.heading(code, 2, null);
            for (var claim : claims) {
                var status = writer.paragraph(label(claim.evidenceStatus(), german));
                status.setKeepNext(true);
                status.getRuns().getFirst().setBold(true);
                status.getRuns().getFirst().setColor(color(claim.evidenceStatus()));
                var contribution = status.createRun();
                contribution.setFontSize(10);
                contribution.setText(" · " + label(claim.contributionType(), german));
                if (!claim.requirementQuote().isEmpty()) {
                    writer.paragraph(originalQuote(german)).setKeepNext(true);
                    writer.paragraph(claim.requirementQuote());
                }
                fields(claim, german, (name, value) -> writer.paragraph(name + ": " + value));
            }
        });
    }

    private static String title(boolean german) {
        return german ? "Anforderungsbeiträge und Beleglage" : "Requirement contributions and evidence";
    }

    private static String summary(Map<String, List<RequirementNodeAssessment>> assessments,
            Collection<String> reportNodes, boolean german) {
        if (assessments.isEmpty())
            return german ? "Nicht beurteilt. Es wurden keine Anforderungsnachweise erfasst."
                    : "Not assessed. No requirement evidence was recorded.";
        long unassessed = reportNodes.stream().distinct().filter(code -> !assessments.containsKey(code)).count();
        return german ? "Knoten mit erfassten Nachweisen: " + assessments.size() + " · Nicht beurteilt: " + unassessed
                : "Nodes with recorded evidence: " + assessments.size() + " · Not assessed: " + unassessed;
    }

    private static String reviewNotice(boolean german) {
        return german ? "KI-Interpretationen zur fachlichen Prüfung. Die Übernahme in die Architektur erfordert eine separate Prüfung."
                : "AI interpretations for review. Adoption into the architecture requires a separate review.";
    }

    private static String originalQuote(boolean german) {
        return german ? "Originalpassage" : "Original passage";
    }

    private static void fields(RequirementNodeAssessment claim, boolean de, BiConsumer<String, String> show) {
        show.accept(de ? "Gegenstandsarten" : "Subject kinds", labels(claim.subjectKinds(), de));
        show.accept(de ? "Anforderungsarten" : "Requirement kinds", labels(claim.requirementKinds(), de));
        show.accept(de ? "Belegart" : "Evidence basis", label(claim.evidenceBasis(), de));
        show.accept(de ? "Verbindlichkeit" : "Necessity", label(claim.necessity(), de));
        show.accept(de ? "Geltungsbereich" : "Scope", label(claim.scope(), de));
        if (!claim.condition().isBlank()) show.accept(de ? "Bedingung" : "Condition", claim.condition());
        show.accept("Interpretation", claim.rationale());
        if (!claim.question().isBlank()) show.accept(de ? "Offene Frage" : "Open question", claim.question());
    }

    private static String color(EvidenceStatus status) {
        return switch (status) {
            case SUPPORTED -> "166534";
            case UNRESOLVED -> "92400E";
            case CONFLICT -> "991B1B";
            case NOT_APPLICABLE, OUT_OF_SCOPE -> "475569";
        };
    }

    private static String labels(List<? extends Enum<?>> values, boolean german) {
        return values.stream().map(value -> label(value, german)).collect(Collectors.joining(", "));
    }

    private static String label(Enum<?> value, boolean german) {
        String[] words = switch (value.name()) {
            case "PRODUCT" -> new String[]{"Product", "Produkt"};
            case "SERVICE" -> new String[]{"Service", "Dienstleistung"};
            case "PROCESS" -> new String[]{"Process", "Prozess"};
            case "INFORMATION" -> new String[]{"Information", "Information"};
            case "ROLE" -> new String[]{"Role", "Rolle"};
            case "CAPABILITY" -> new String[]{"Capability", "Fähigkeit"};
            case "FUNCTIONAL" -> new String[]{"Functional", "Funktional"};
            case "QUALITY" -> new String[]{"Quality", "Qualität"};
            case "CONSTRAINT" -> new String[]{"Constraint", "Randbedingung"};
            case "ORGANIZATIONAL" -> new String[]{"Organizational", "Organisatorisch"};
            case "CONTEXT" -> new String[]{"Context", "Kontext"};
            case "GOAL" -> new String[]{"Goal", "Ziel"};
            case "DIRECT_SUBJECT" -> new String[]{"Direct subject", "Direkter Gegenstand"};
            case "PROVIDES" -> new String[]{"Provides", "Stellt bereit"};
            case "SUPPORTS" -> new String[]{"Supports", "Unterstützt"};
            case "REQUIRES" -> new String[]{"Requires", "Setzt voraus"};
            case "CONSTRAINS" -> new String[]{"Constrains", "Schränkt ein"};
            case "UNDETERMINED" -> new String[]{"Undetermined", "Unbestimmt"};
            case "SUPPORTED" -> new String[]{"Supported", "Belegt"};
            case "UNRESOLVED" -> new String[]{"Clarification needed", "Klärung erforderlich"};
            case "CONFLICT" -> new String[]{"Conflicting evidence", "Widersprüchliche Belege"};
            case "NOT_APPLICABLE" -> new String[]{"Not applicable", "Nicht anwendbar"};
            case "OUT_OF_SCOPE" -> new String[]{"Outside the considered scope", "Außerhalb des betrachteten Umfangs"};
            case "EXPLICIT" -> new String[]{"Explicit source", "Explizite Quelle"};
            case "INFERRED" -> new String[]{"Inferred from source", "Aus Quelle abgeleitet"};
            case "UNSPECIFIED" -> new String[]{"Unspecified", "Nicht angegeben"};
            case "REQUIRED" -> new String[]{"Required", "Erforderlich"};
            case "OPTIONAL" -> new String[]{"Optional", "Optional"};
            case "ALTERNATIVE" -> new String[]{"Alternative", "Alternative"};
            case "DELIVERY" -> new String[]{"Requested delivery", "Geforderter Leistungsumfang"};
            case "EXISTING_ENVIRONMENT" -> new String[]{"Existing environment", "Bestehende Umgebung"};
            case "EXCLUDED" -> new String[]{"Excluded", "Ausgeschlossen"};
            default -> throw new IllegalArgumentException("Unknown requirement evidence label: " + value);
        };
        return words[german ? 1 : 0];
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
