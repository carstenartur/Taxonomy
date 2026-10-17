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

    /** Complete readable provenance, including exact scoped evidence. Never infers review status. */
    public static List<String> relationshipDetails(com.taxonomy.diagram.DiagramRelationMetadata metadata) {
        var lines = new ArrayList<String>();
        lines.add("Status: " + relationshipStatus(metadata));
        if (metadata == null) return List.copyOf(lines);
        if (metadata.relationId() != null) lines.add("Relation ID: " + metadata.relationId());
        lines.add("Origin: " + (metadata.origin() == null ? "unrecorded" : metadata.origin().name()));
        if (metadata.confidence() != null) lines.add("Confidence: " + metadata.confidence());
        if (metadata.seedType() != null) lines.add("Seed type: " + metadata.seedType());
        if (metadata.includedBecause() != null) lines.add("Included because: " + metadata.includedBecause());
        if (metadata.derivationReason() != null) lines.add("Derivation reason: " + metadata.derivationReason());
        if (metadata.presenceReason() != null) lines.add("Presence reason: " + metadata.presenceReason());
        for (var evidence : metadata.requirementEvidence()) {
            lines.add("Scoped evidence: " + evidence.sourceId() + " -> " + evidence.targetId()
                    + " | " + evidence.type() + " | " + evidence.direction());
            lines.add("Contribution: " + evidence.contribution().text());
            lines.add("Original quote: " + evidence.contribution().quote());
            lines.add("Contribution condition: " + evidence.contribution().condition());
            var decision = evidence.evidence();
            if (decision != null) {
                lines.add("Outcome: " + decision.outcome() + " | Necessity: " + decision.necessity());
                lines.add("Evidence contribution: " + decision.contribution() + " | Quote: " + decision.quote());
                lines.add("Condition: " + decision.condition() + " | Alternative group: " + decision.alternativeGroup());
                lines.add("Rationale: " + decision.rationale() + " | Question: " + decision.question());
            }
        }
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
