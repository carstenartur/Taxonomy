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
        if (type == null) return "#F1F5F9";
        return switch (type) {
            case "Capabilities", "CP", "Capability" -> "#DBEAFE";
            case "Business Processes", "Business Roles", "BP", "BR", "Process", "BusinessRole" -> "#DCFCE7";
            case "Core Services", "COI Services", "Services", "CR", "CI", "CoreService", "COIService" -> "#FEF3C7";
            case "User Applications", "Applications", "UA", "UserApplication" -> "#F3E8FF";
            case "Information Products", "IP", "InformationProduct" -> "#FFE4E6";
            case "Communications Services", "CO", "CommunicationsService" -> "#CFFAFE";
            default -> "#F1F5F9";
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
        var nodes = new LinkedHashMap<String, com.taxonomy.diagram.DiagramSceneNode>();
        scene.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
        long containers = nodes.values().stream().filter(node -> node.container()).count();
        long relationships = scene.edges().stream().map(edge -> edge.id()).distinct().count();
        Map<String, Integer> taxonomies = new TreeMap<>();
        nodes.values().stream().filter(node -> !node.container()).forEach(node ->
                taxonomies.merge(node.type() == null ? "Unclassified" : node.type(), 1, Integer::sum));
        var lines = new ArrayList<String>();
        lines.add("Exported view | Elements: " + (nodes.size() - containers) + " | Containers: " + containers
                + " | Relationships: " + relationships);
        taxonomies.forEach((type, count) -> lines.add(type + ": " + count));
        Map<String, Integer> statuses = new TreeMap<>();
        var seenEdges = new java.util.HashSet<String>();
        scene.edges().stream().filter(edge -> seenEdges.add(edge.id())).forEach(edge ->
                statuses.merge(relationshipStatus(edge.metadata()), 1, Integer::sum));
        statuses.forEach((status, count) -> lines.add("Relationships - " + status + ": " + count));
        lines.add("Dashed connectors identify AI-proposed relationships; solid connectors do not imply approval.");
        lines.add("Colors identify taxonomy families; a strong border marks a selection anchor, not approval.");
        lines.add("Human confirmation is not recorded in this diagram model. AI verification is not human approval.");
        lines.add("Depth is the recorded taxonomy depth, not the horizontal layout layer.");
        return List.copyOf(lines);
    }
}
