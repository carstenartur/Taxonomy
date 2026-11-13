package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Presentation only: colors and counts never imply model acceptance or change graph evidence. */
public final class DiagramPresentation {
    private DiagramPresentation() { }

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
        scene.edges().stream().filter(edge -> seenEdges.add(edge.id())).forEach(edge ->
                statuses.merge(relationshipStatus(edge.metadata(), language.german()), 1, Integer::sum));
        statuses.forEach((status, count) -> lines.add(language.text("Relationships - ", "Beziehungen - ") + status + ": " + count));
        lines.add(language.text("Dashed connectors identify AI-proposed relationships; solid connectors do not imply approval.", "Gestrichelte Linien zeigen KI-Vorschläge. Durchgezogene Linien belegen keine Freigabe."));
        lines.add(language.text("Colors identify taxonomy families; a strong border marks a selection anchor, not approval.", "Farben kennzeichnen Taxonomiefamilien. Ein kräftiger Rahmen markiert einen Auswahlanker."));
        lines.add(language.text("Human confirmation is not recorded in this diagram model. AI verification is not human approval.", "Dieses Diagrammmodell erfasst keine menschliche Bestätigung. Eine KI-Prüfung ist keine menschliche Freigabe."));
        lines.add(language.text("Depth is the recorded taxonomy depth, not the horizontal layout layer.", "Die Tiefe entspricht der gespeicherten Taxonomietiefe und ist unabhängig von der horizontalen Anordnung."));
        return List.copyOf(lines);
    }
}
