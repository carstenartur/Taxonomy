package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.*;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Projects verified evidence into the existing view DTOs, without relation propagation. */
public final class EvidenceRelationProjection {
    private EvidenceRelationProjection() { }

    public static void apply(ArchitectureViewContext context, RelationSearchReport report) {
        Map<String, RequirementElementView> elements = new LinkedHashMap<>();
        Map<String, Node> identities = new HashMap<>();
        List<RequirementRelationshipView> relationships = new ArrayList<>();
        Map<EdgeSignature, RequirementRelationshipView> relationIndex = new LinkedHashMap<>();
        List<String> notes = context.getView().getNotes();
        int omitted = 0, choices = 0;
        for (Edge edge : report.result().edges()) {
            Node source = edge.contribution().source(), target = edge.target();
            if (edge.evidence().outcome() != Outcome.VERIFIED || source.container() || target.container()
                    || source.id().equals(target.id()) || !target.id().equals(edge.evidence().targetId())) {
                throw new IllegalArgumentException("Only verified, concrete, distinct endpoints can be projected");
            }
            for (Node node : List.of(source, target)) {
                Node prior = identities.putIfAbsent(node.id(), node);
                if (prior != null && !prior.equals(node)) throw new IllegalArgumentException("Conflicting catalogue endpoint identity");
            }
            // Showing alternative and optional branches as simultaneous requirements would
            // silently make an adoption decision. Their complete evidence stays in the report.
            if (edge.evidence().necessity() != Necessity.REQUIRED) { choices++; continue; }
            EdgeSignature signature = new EdgeSignature(edge.sourceId(), edge.targetId(), edge.type());
            RequirementRelationshipView previous = relationIndex.get(signature);
            if (previous != null) {
                var evidence = new ArrayList<>(previous.getRequirementEvidence());
                if (!evidence.contains(edge)) evidence.add(edge);
                previous.setRequirementEvidence(evidence);
                previous.setPresenceReason(summary(previous.getPresenceReason() + " | Additional requirement scope retained in evidence report."));
                continue;
            }
            int needed = (elements.containsKey(source.id()) ? 0 : 1) + (elements.containsKey(target.id()) ? 0 : 1);
            if (context.getMaxArchitectureNodes() > 0 && elements.size() + needed > context.getMaxArchitectureNodes()) {
                omitted++; continue;
            }
            elements.computeIfAbsent(source.id(), id -> element(source, context.getScores(), edge.contribution().text()));
            elements.computeIfAbsent(target.id(), id -> element(target, context.getScores(), edge.evidence().contribution()));
            RequirementRelationshipView relation = new RequirementRelationshipView();
            relation.setSourceCode(edge.sourceId()); relation.setTargetCode(edge.targetId()); relation.setRelationType(edge.type());
            relation.setOrigin(RelationOrigin.LLM_SUPPORTED);
            relation.setRequirementEvidence(List.of(edge));
            String reason = "Proposed, separately checked against requirement: " + edge.evidence().rationale()
                    + " | Source contribution: " + edge.contribution().text() + " | Target contribution: " + edge.evidence().contribution()
                    + " | Evidence: " + edge.contribution().quote() + " / " + edge.evidence().quote()
                    + " | Conditions: " + edge.contribution().condition() + " / " + edge.evidence().condition();
            relation.setPresenceReason(summary(reason)); relation.setDerivationReason(summary(reason)); relation.setIncludedBecause(summary(reason));
            // Confidence is intentionally left unset (legacy numeric DTO default 0).
            // The source/target relevance scores are not probabilities of this relationship.
            relationships.add(relation); relationIndex.put(signature, relation);
        }
        context.setAnchors(new ArrayList<>());
        context.getElements().clear(); context.getElements().addAll(elements.values());
        context.getRelationships().clear(); context.getRelationships().addAll(relationships);
        RequirementArchitectureView view = context.getView();
        view.setRelationSearchReport(report);
        view.setAnchors(context.getAnchors()); view.setIncludedElements(context.getElements()); view.setIncludedRelationships(context.getRelationships());
        view.setTotalAnchors(0); view.setTotalElements(elements.size()); view.setTotalRelationships(relationships.size()); view.setMaxHopDistance(0);
        notes.add("Requirement-scoped relation proposals; not accepted into the active architecture. Confidence has not been calibrated.");
        if (relationships.isEmpty()) notes.add("No verified required relationships available; no score-product or catalogue-seed fallback was used.");
        if (choices > 0) notes.add("DECISIONS_PENDING: " + choices + " optional/alternative relations remain in the analysis evidence report, not in the required view.");
        if (omitted > 0) notes.add("NODE_LIMIT: " + omitted + " relations omitted from this view; complete evidence is unchanged in the analysis report.");
        if (!report.isSearchExhausted()) notes.add("RELATION_SEARCH_PARTIAL: see the analysis report for unassessed work, questions and budget limits.");
    }

    private record EdgeSignature(String source, String target, String type) { }

    /** Queryable snapshot columns are bounded; complete quotes/scopes stay in the immutable report. */
    private static String summary(String text) {
        if (text.length() <= 1800) return text;
        int end = 1760;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end) + " [full evidence in analysis report]";
    }

    private static RequirementElementView element(Node node, Map<String,Integer> scores, String contribution) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(node.id()); element.setTitle(node.name()); element.setTaxonomySheet(node.root());
        Integer raw = scores.get(node.id());
        int score = raw == null ? 0 : Math.max(0, Math.min(100, raw));
        element.setDirectLlmScore(score); element.setRelevance(score / 100.0);
        element.setOrigin(NodeOrigin.RELATION_EVIDENCE); element.setSelectedForImpact(true);
        element.setIncludedBecause(summary(contribution)); element.setPresenceReason(summary("Requirement-scoped contribution: " + contribution));
        return element;
    }
}
