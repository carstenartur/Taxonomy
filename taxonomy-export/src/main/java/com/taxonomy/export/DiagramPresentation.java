package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramCanonicalEvidence;
import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.dto.RelationSearchModel.Direction;
import com.taxonomy.dto.RelationSearchModel.Edge;
import com.taxonomy.dto.RelationSearchModel.Necessity;
import com.taxonomy.dto.RelationSearchModel.Outcome;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Presentation only: colors and counts never imply model acceptance or change graph evidence. */
public final class DiagramPresentation {
    private DiagramPresentation() { }

    static List<String> responsibilityDetails(Assignment assignment, DiagramLanguage language) {
        if (assignment == null) return List.of();
        return List.of(
                language.text("Responsible unit: ", "Zuständige Einheit: ") + assignment.name() + " (" + assignment.unitId() + ")",
                language.text("Assignment source: ", "Quelle der Zuweisung: ") + assignment.sourceId());
    }

    public static String fill(String type) {
        return switch (layer(type)) {
            case "cap" -> "#DBEAFE";
            case "proc" -> "#DCFCE7";
            case "svc" -> "#FEF3C7";
            case "app" -> "#F3E8FF";
            case "info" -> "#FFE4E6";
            case "comm" -> "#CFFAFE";
            default -> "#F1F5F9";
        };
    }

    /** Presentation family only; never infers catalogue identity from an element identifier. */
    public static String layer(String type) {
        if (type == null) return "default";
        return switch (type) {
            case "Capabilities", "CP", "Capability" -> "cap";
            case "Business Processes", "Business Roles", "BP", "BR", "Process", "BusinessRole" -> "proc";
            case "Core Services", "COI Services", "Services", "CR", "CI", "CoreService", "COIService" -> "svc";
            case "User Applications", "Applications", "UA", "UserApplication" -> "app";
            case "Information Products", "IP", "InformationProduct" -> "info";
            case "Communications Services", "CO", "CommunicationsService" -> "comm";
            case "Systems", "SY", "System" -> "system";
            case "Components", "CM", "Component" -> "component";
            default -> "default";
        };
    }

    public static String relationshipStatus(com.taxonomy.diagram.DiagramRelationMetadata metadata) {
        if (metadata == null) return "Origin unrecorded";
        if (metadata.proposed()) return "AI-proposed";
        if (metadata.origin() == null) return "Origin unrecorded";
        return switch (metadata.origin()) {
            case TAXONOMY_SEED -> "Catalogue seed";
            case PROPAGATED_TRACE -> "Propagated trace";
            case IMPACT_DERIVED -> "Derived impact";
            case LLM_SUPPORTED, SUGGESTED_CANDIDATE -> "AI-proposed";
        };
    }

    public static String relationshipStatus(com.taxonomy.diagram.DiagramRelationMetadata metadata, boolean german) {
        if (!german) return relationshipStatus(metadata);
        if (metadata == null) return "Herkunft nicht aufgezeichnet";
        if (metadata.proposed()) return "KI-Vorschlag";
        if (metadata.origin() == null) return "Herkunft nicht aufgezeichnet";
        return switch (metadata.origin()) {
            case TAXONOMY_SEED -> "Katalogbeziehung";
            case PROPAGATED_TRACE -> "Fortgeführte Herleitung";
            case IMPACT_DERIVED -> "Abgeleitete Auswirkung";
            case LLM_SUPPORTED, SUGGESTED_CANDIDATE -> "KI-Vorschlag";
        };
    }

    /** Complete readable provenance, including exact scoped evidence. Never infers review status. */
    public static List<String> relationshipDetails(com.taxonomy.diagram.DiagramRelationMetadata metadata) {
        return relationshipDetails(metadata, false);
    }

    public static List<String> relationshipDetails(com.taxonomy.diagram.DiagramRelationMetadata metadata, boolean german) {
        var lines = new ArrayList<String>();
        lines.add("Status: " + relationshipStatus(metadata, german));
        if (metadata == null) return List.copyOf(lines);
        if (metadata.relationId() != null) lines.add((german ? "Beziehungskennung: " : "Relation ID: ") + metadata.relationId());
        lines.add((german ? "Herkunft: " : "Origin: ") + (metadata.origin() == null ? (german ? "nicht aufgezeichnet" : "unrecorded") : metadata.origin().name()));
        if (metadata.confidence() != null) lines.add((german ? "Konfidenz: " : "Confidence: ") + metadata.confidence());
        if (metadata.seedType() != null) lines.add((german ? "Ausgangstyp: " : "Seed type: ") + metadata.seedType());
        if (metadata.includedBecause() != null) lines.add((german ? "Aufgenommen weil: " : "Included because: ") + metadata.includedBecause());
        if (metadata.derivationReason() != null) lines.add((german ? "Herleitungsbegründung: " : "Derivation reason: ") + metadata.derivationReason());
        if (metadata.presenceReason() != null) lines.add((german ? "Begründung der Aufnahme: " : "Presence reason: ") + metadata.presenceReason());
        if (metadata.requirementEvidence().size() > 1) {
            addScopedEvidence(lines, metadata.requirementEvidence(), german);
            return List.copyOf(lines);
        }
        for (var evidence : metadata.requirementEvidence()) {
            lines.add((german ? "Anforderungsbezogene Evidenz: " : "Scoped evidence: ") + evidence.sourceId() + " -> " + evidence.targetId()
                    + " | " + evidence.type() + " | " + evidence.direction());
            lines.add((german ? "Beitrag: " : "Contribution: ") + evidence.contribution().text());
            lines.add((german ? "Originalzitat: " : "Original quote: ") + evidence.contribution().quote());
            lines.add((german ? "Bedingung des Beitrags: " : "Contribution condition: ") + evidence.contribution().condition());
            var decision = evidence.evidence();
            if (decision != null) {
                lines.add((german ? "Ergebnis: " : "Outcome: ") + decision.outcome() + (german ? " | Notwendigkeit: " : " | Necessity: ") + decision.necessity());
                lines.add((german ? "Evidenzbeitrag: " : "Evidence contribution: ") + decision.contribution() + (german ? " | Zitat: " : " | Quote: ") + decision.quote());
                lines.add((german ? "Bedingung: " : "Condition: ") + decision.condition() + (german ? " | Alternativgruppe: " : " | Alternative group: ") + decision.alternativeGroup());
                lines.add((german ? "Begründung: " : "Rationale: ") + decision.rationale() + (german ? " | Frage: " : " | Question: ") + decision.question());
            }
        }
        return List.copyOf(lines);
    }

    /** Keep the roles and direction of each assessment while printing an identical original value once per relationship. */
    private static void addScopedEvidence(List<String> lines, List<Edge> scopes, boolean german) {
        var fieldsByValue = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < scopes.size(); index++) {
            Edge scope = scopes.get(index);
            String number = Integer.toString(index + 1);
            var decision = scope.evidence();
            String heading = (german ? "Nachweis " : "Evidence ") + number + ": "
                    + scope.sourceId() + " -> " + scope.targetId() + " | " + scope.type() + " | " + scope.direction();
            if (decision != null) {
                if (decision.outcome() != null) heading += (german ? " | Ergebnis: " : " | Outcome: ") + decision.outcome();
                if (decision.necessity() != null) heading += (german ? " | Notwendigkeit: " : " | Necessity: ") + decision.necessity();
            }
            lines.add(heading);
            recordScopeFields(fieldsByValue, number + " · ", scope, german);
        }
        fieldsByValue.forEach((value, fields) -> lines.add(String.join(", ", fields) + ": " + value));
    }

    private static void recordField(Map<String, List<String>> fieldsByValue, String label, String value) {
        if (value != null && !value.isBlank()) {
            fieldsByValue.computeIfAbsent(value, ignored -> new ArrayList<>()).add(label);
        }
    }

    /** Render each distinct recorded value once, with its roles and assessment number. */
    public static List<String> relationshipReportDetails(DiagramRelationMetadata metadata,
            Map<String, String> supplemental, boolean german) {
        var lines = new ArrayList<String>();
        var fieldsByValue = new LinkedHashMap<String, List<String>>();
        lines.add("Status: " + relationshipStatus(metadata, german));
        Set<String> generatedSummaries = Set.of();
        if (metadata != null) {
            if (metadata.relationId() != null) lines.add((german ? "Beziehungskennung: " : "Relation ID: ") + metadata.relationId());
            String origin = metadata.origin() == null ? (german ? "nicht aufgezeichnet" : "unrecorded")
                    : german ? switch (metadata.origin()) {
                        case TAXONOMY_SEED -> "Katalogbeziehung";
                        case PROPAGATED_TRACE -> "Fortgeführte Herleitung";
                        case IMPACT_DERIVED -> "Abgeleitete Auswirkung";
                        case LLM_SUPPORTED -> "Durch KI gestützt";
                        case SUGGESTED_CANDIDATE -> "Vorgeschlagener Kandidat";
                    } : metadata.origin().name();
            lines.add((german ? "Herkunft: " : "Origin: ") + origin);
            if (metadata.confidence() != null) lines.add((german ? "Konfidenz: " : "Confidence: ") + metadata.confidence());
            if (metadata.seedType() != null) {
                String seed = german ? switch (metadata.seedType()) {
                    case TYPE_DEFAULT -> "Standardbeziehung zwischen Typen";
                    case FRAMEWORK_SEED -> "Aus Architekturrahmenwerk";
                    case SOURCE_DERIVED -> "Aus Quelldokument";
                } : metadata.seedType().name();
                lines.add((german ? "Ausgangstyp: " : "Seed type: ") + seed);
            }
            var scopes = metadata.requirementEvidence();
            generatedSummaries = scopes.stream().filter(edge -> edge.evidence() != null
                            && edge.evidence().outcome() == Outcome.VERIFIED
                            && edge.evidence().necessity() == Necessity.REQUIRED)
                    .map(edge -> RequirementRelationshipView.projectedEvidenceSummary(edge, scopes.size() > 1))
                    .collect(Collectors.toUnmodifiableSet());
            recordReportField(fieldsByValue, german ? "Aufgenommen weil" : "Included because",
                    metadata.includedBecause(), generatedSummaries);
            recordReportField(fieldsByValue, german ? "Herleitungsbegründung" : "Derivation reason",
                    metadata.derivationReason(), generatedSummaries);
            recordReportField(fieldsByValue, german ? "Begründung der Aufnahme" : "Presence reason",
                    metadata.presenceReason(), generatedSummaries);
            for (int i = 0; i < scopes.size(); i++) {
                addReportScope(lines, fieldsByValue, scopes.get(i), i + 1, scopes.size() > 1, german);
            }
        }
        if (supplemental != null) {
            recordReportField(fieldsByValue, german ? "Gespeicherte Auswahlbegründung" : "Recorded selection reason",
                    supplemental.get("presenceReason"), generatedSummaries);
            recordField(fieldsByValue, german ? "Gespeicherter Prüfkommentar" : "Recorded review comment",
                    supplemental.get("decisionComment"));
        }
        fieldsByValue.forEach((value, fields) -> lines.add(String.join(", ", fields) + ": " + value));
        return List.copyOf(lines);
    }

    private static void recordReportField(Map<String, List<String>> fields, String label, String value, Set<String> generated) {
        // Only omit a byte-for-byte match for the legacy projection of the structured evidence.
        if (value != null && !generated.contains(value)) recordField(fields, label, value);
    }

    private static void addReportScope(List<String> lines, Map<String, List<String>> fields, Edge edge,
            int number, boolean multiple, boolean german) {
        String title = multiple ? (german ? "Nachweis " : "Evidence ") + number
                : (german ? "Anforderungsbezogene Evidenz" : "Scoped evidence");
        String direction = german ? (edge.direction() == Direction.OUTGOING ? "Ausgehend" : "Eingehend")
                : edge.direction().name();
        String separator = german ? " · " : " | ";
        String heading = title + ": " + edge.sourceId() + (german ? " → " : " -> ") + edge.targetId()
                + separator + edge.type() + separator + direction;
        var decision = edge.evidence();
        if (decision != null) {
            if (decision.outcome() != null) heading += separator + (german ? "Ergebnis: " : "Outcome: ") + reportOutcome(decision.outcome(), german);
            if (decision.necessity() != null) heading += separator + (german ? "Notwendigkeit: " : "Necessity: ") + reportNecessity(decision.necessity(), german);
        }
        lines.add(heading);
        recordScopeFields(fields, multiple ? number + " · " : "", edge, german);
    }

    private static void recordScopeFields(Map<String, List<String>> fields, String prefix, Edge edge, boolean german) {
        var contribution = edge.contribution();
        recordField(fields, prefix + (german ? "Beitrag" : "Contribution"), contribution.text());
        recordField(fields, prefix + (german ? "Originalzitat" : "Original quote"), contribution.quote());
        recordField(fields, prefix + (german ? "Bedingung des Beitrags" : "Contribution condition"), contribution.condition());
        var decision = edge.evidence();
        if (decision == null) return;
        recordField(fields, prefix + (german ? "Evidenzbeitrag" : "Evidence contribution"), decision.contribution());
        recordField(fields, prefix + (german ? "Zitat" : "Quote"), decision.quote());
        recordField(fields, prefix + (german ? "Bedingung" : "Condition"), decision.condition());
        recordField(fields, prefix + (german ? "Alternativgruppe" : "Alternative group"), decision.alternativeGroup());
        recordField(fields, prefix + (german ? "Begründung" : "Rationale"), decision.rationale());
        recordField(fields, prefix + (german ? "Frage" : "Question"), decision.question());
    }

    private static String reportOutcome(Outcome outcome, boolean german) {
        if (!german) return outcome.name();
        return switch (outcome) {
            case DESCEND -> "Unterknoten prüfen";
            case MATCH -> "Treffer";
            case REJECT -> "Verworfen";
            case UNRESOLVED -> "Offen";
            case VERIFIED -> "Verifiziert";
        };
    }

    private static String reportNecessity(Necessity necessity, boolean german) {
        if (!german) return necessity.name();
        return switch (necessity) {
            case REQUIRED -> "Erforderlich";
            case OPTIONAL -> "Optional";
            case ALTERNATIVE -> "Alternative";
        };
    }

    /** Original mapping/review evidence stored separately from the frozen graph provenance. */
    public static List<String> relationshipEvidenceDetails(Map<String, String> fields, boolean german) {
        if (fields == null || fields.isEmpty()) return List.of();
        var lines = new ArrayList<String>();
        String presence = fields.get("presenceReason");
        if (presence != null && !presence.isBlank())
            lines.add((german ? "Gespeicherte Auswahlbegründung: " : "Recorded selection reason: ") + presence);
        String comment = fields.get("decisionComment");
        if (comment != null && !comment.isBlank())
            lines.add((german ? "Gespeicherter Prüfkommentar: " : "Recorded review comment: ") + comment);
        return List.copyOf(lines);
    }

    static String canonicalRelationshipStatus(DiagramCanonicalEvidence evidence, String id) {
        var relation = evidence.relationships().get(id);
        return relation == null || relation.status() == null || relation.status().isBlank() ? "unrecorded" : relation.status();
    }

    static String canonicalRelationshipStatus(DiagramCanonicalEvidence evidence, String id, DiagramLanguage language) {
        String status = canonicalRelationshipStatus(evidence, id);
        return "unrecorded".equals(status) ? language.text(status, "nicht aufgezeichnet") : status;
    }

    static boolean canonicalRelationshipProposed(DiagramCanonicalEvidence evidence, String id) {
        String status = canonicalRelationshipStatus(evidence, id);
        return "proposed".equals(status) || "provisional".equals(status);
    }

    static List<String> canonicalRelationshipDetails(DiagramCanonicalEvidence evidence, String id, DiagramLanguage language) {
        var lines = new ArrayList<String>();
        lines.add(language.text("Model status: ", "Modellstatus: ") + canonicalRelationshipStatus(evidence, id, language));
        var recorded = evidence.relationships().get(id);
        String provenance = recorded == null || recorded.provenance() == null || recorded.provenance().isBlank()
                ? language.text("unrecorded", "nicht aufgezeichnet") : recorded.provenance();
        lines.add(language.text("Recorded provenance: ", "Gespeicherte Herkunft: ") + provenance);
        if (recorded != null && recorded.confidence() != null) lines.add(language.text("Recorded confidence: ", "Gespeicherte Konfidenz: ") + recorded.confidence());
        if (recorded != null) for (var witness : recorded.witnesses()) {
            addField(lines, language.text("Evidence", "Beleg"), witness.id());
            addField(lines, language.text("Source view", "Quellsicht"), witness.view());
            addField(lines, "Snapshot", witness.snapshot());
            addField(lines, language.text("Evidence type", "Belegtyp"), witness.type());
            addField(lines, language.text("Source model", "Quellmodell"), witness.model());
            addField(lines, language.text("Evidence origin", "Belegherkunft"), witness.origin());
            addField(lines, language.text("Assessment direction", "Prüfrichtung"), witness.direction());
            if (witness.confidence() != null) addField(lines, language.text("Evidence confidence", "Belegkonfidenz"), witness.confidence().toString());
            addField(lines, language.text("Rationale", "Begründung"), witness.summary());
            addField(lines, language.text("Assessment outcome", "Prüfergebnis"), witness.outcome());
            addField(lines, language.text("Necessity", "Notwendigkeit"), witness.necessity());
            addField(lines, language.text("Alternative group", "Alternativgruppe"), witness.alternative());
            addField(lines, language.text("Open question", "Offene Frage"), witness.question());
            endpointDetails(lines, witness.assessed(), language);
            endpointDetails(lines, witness.candidate(), language);
        }
        return List.copyOf(lines);
    }

    private static void endpointDetails(List<String> lines, DiagramCanonicalEvidence.Endpoint endpoint, DiagramLanguage language) {
        String prefix = endpoint.id() == null || endpoint.id().isBlank() ? "" : endpoint.id() + " · ";
        addField(lines, prefix + language.text("Contribution", "Beitrag"), endpoint.contribution());
        addField(lines, prefix + language.text("Original quote", "Originalzitat"), endpoint.quote());
        addField(lines, prefix + language.text("Condition", "Bedingung"), endpoint.condition());
    }

    private static void addField(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(label + ": " + value);
    }

    /** Scores are fractions of one; a positive value must never be presented as zero. */
    public static String percent(double relevance) {
        if (!Double.isFinite(relevance) || relevance < 0 || relevance > 1) return "n/a";
        BigDecimal value = BigDecimal.valueOf(relevance).movePointRight(2);
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.000001")) < 0)
            return "<0.000001%";
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    /** Counts refer only to this exported scene, not the full catalogue or duplicated detail pages. */
    public static List<String> inventorySummary(DiagramScene scene) {
        return inventorySummary(scene, DiagramLanguage.ENGLISH);
    }

    static List<String> inventorySummary(DiagramScene scene, DiagramLanguage language) {
        return inventorySummary(scene, language, null);
    }

    static List<String> inventorySummary(DiagramScene scene, DiagramLanguage language, DiagramCanonicalEvidence canonical) {
        var nodes = new LinkedHashMap<String, com.taxonomy.diagram.DiagramSceneNode>();
        scene.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
        long containers = nodes.values().stream().filter(node -> node.container()).count();
        long relationships = scene.edges().stream().map(edge -> edge.id()).distinct().count();
        Map<String, Integer> taxonomies = new TreeMap<>();
        nodes.values().stream().filter(node -> !node.container()).forEach(node ->
                taxonomies.merge(node.type() == null ? language.text("Unclassified", "Nicht klassifiziert") : node.type(), 1, Integer::sum));
        var lines = new ArrayList<String>();
        lines.add(language.text("Exported view | Elements: ", "Exportierte Ansicht | Elemente: ") + (nodes.size() - containers) + language.text(" | Containers: ", " | Container: ") + containers
                + language.text(" | Relationships: ", " | Beziehungen: ") + relationships);
        taxonomies.forEach((type, count) -> lines.add(type + ": " + count));
        Map<String, Integer> statuses = new TreeMap<>();
        var seenEdges = new java.util.HashSet<String>();
        scene.edges().stream().filter(edge -> seenEdges.add(edge.id())).forEach(edge -> {
            String status = canonical == null ? relationshipStatus(edge.metadata(), language.german())
                    : canonicalRelationshipStatus(canonical, edge.id(), language);
            statuses.merge(status, 1, Integer::sum);
        });
        statuses.forEach((status, count) -> lines.add(language.text("Relationships - ", "Beziehungen - ") + status + ": " + count));
        if (canonical != null) {
            lines.add(language.text("Scores and taxonomy depth: unrecorded in the canonical model.", "Bewertungen und Taxonomietiefe sind im kanonischen Modell nicht erfasst."));
            lines.add(language.text("Relationship statuses are the saved model states. Review and provenance evidence are separate.", "Der Beziehungsstatus entspricht dem gespeicherten Modellstand. Herkunft und Prüfbelege sind gesondert ausgewiesen."));
            if (canonical.viewId() != null && !canonical.viewId().isBlank()) lines.add(language.text("Source view: ", "Quellsicht: ") + canonical.viewId());
            for (var source : canonical.analysisSources()) lines.add(language.text("Recorded analysis source: ", "Gespeicherte Analysequelle: ") + source.viewId()
                    + " | Snapshot " + source.snapshotId() + " | " + Objects.toString(source.status(), language.text("status unrecorded", "Status nicht aufgezeichnet")));
            return List.copyOf(lines);
        }
        lines.add(language.text("Dashed connectors identify AI-proposed relationships; solid connectors do not imply approval.", "Gestrichelte Linien zeigen KI-Vorschläge. Durchgezogene Linien belegen keine Freigabe."));
        lines.add(language.text("Colors identify taxonomy families; a strong border marks a selection anchor, not approval.", "Farben kennzeichnen Taxonomiefamilien. Ein kräftiger Rahmen markiert einen Auswahlanker."));
        lines.add(language.text("Human confirmation is not recorded in this diagram model. AI verification is not human approval.", "Dieses Diagrammmodell erfasst keine menschliche Bestätigung. Eine KI-Prüfung ist keine menschliche Freigabe."));
        lines.add(language.text("Depth is the recorded taxonomy depth, not the horizontal layout layer.", "Die Tiefe entspricht der gespeicherten Taxonomietiefe und ist unabhängig von der horizontalen Anordnung."));
        return List.copyOf(lines);
    }
}
