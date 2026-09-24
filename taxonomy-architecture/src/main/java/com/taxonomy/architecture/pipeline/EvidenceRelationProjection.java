package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.*;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Projects verified evidence into the existing view DTOs, without relation propagation. */
public final class EvidenceRelationProjection {
    private EvidenceRelationProjection() { }

    /** Effective scores without provenance do not establish an original model score. */
    public static void apply(ArchitectureViewContext context, RelationSearchReport report) {
        apply(context, report, Map.of());
    }

    public static void apply(ArchitectureViewContext context, RelationSearchReport report,
                             Map<String, AnalysisScoreDetail> scoreDetails) {
        Objects.requireNonNull(context); Objects.requireNonNull(report);
        Map<String, AnalysisScoreDetail> details = Map.copyOf(scoreDetails);
        Map<String, RequirementElementView> elements = new LinkedHashMap<>();
        Map<String, Node> identities = new HashMap<>();
        List<RequirementRelationshipView> relationships = new ArrayList<>();
        Map<EdgeSignature, Set<Edge>> grouped = new LinkedHashMap<>();
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
            if (edge.evidence().necessity() != Necessity.REQUIRED) choices++;
            EdgeSignature signature = new EdgeSignature(edge.sourceId(), edge.targetId(), edge.type());
            grouped.computeIfAbsent(signature, unused -> new LinkedHashSet<>()).add(edge);
        }
        for (Set<Edge> evidence : grouped.values()) {
            // Group before filtering: options on an already-required signature remain
            // inspectable, but options alone never create required nodes or relationships.
            Edge edge = evidence.stream().filter(e -> e.evidence().necessity() == Necessity.REQUIRED)
                    .findFirst().orElse(null);
            if (edge == null) continue;
            Node source = edge.contribution().source(), target = edge.target();
            int needed = (elements.containsKey(source.id()) ? 0 : 1) + (elements.containsKey(target.id()) ? 0 : 1);
            if (context.getMaxArchitectureNodes() > 0 && elements.size() + needed > context.getMaxArchitectureNodes()) {
                omitted++; continue;
            }
            elements.computeIfAbsent(source.id(), id -> element(source, context.getScores(), details, edge.contribution().text()));
            elements.computeIfAbsent(target.id(), id -> element(target, context.getScores(), details, edge.evidence().contribution()));
            RequirementRelationshipView relation = new RequirementRelationshipView();
            relation.setSourceCode(edge.sourceId()); relation.setTargetCode(edge.targetId()); relation.setRelationType(edge.type());
            relation.setOrigin(RelationOrigin.LLM_SUPPORTED);
            relation.setRequirementEvidence(List.copyOf(evidence));
            String reason = "Proposed, separately checked against requirement: " + edge.evidence().rationale()
                    + " | Source contribution: " + edge.contribution().text() + " | Target contribution: " + edge.evidence().contribution()
                    + " | Evidence: " + edge.contribution().quote() + " / " + edge.evidence().quote()
                    + " | Conditions: " + edge.contribution().condition() + " / " + edge.evidence().condition();
            if (evidence.size() > 1) reason += " | Additional requirement scopes and choices retained in structured evidence.";
            relation.setPresenceReason(summary(reason)); relation.setDerivationReason(summary(reason)); relation.setIncludedBecause(summary(reason));
            // Numeric confidence remains its legacy layout/index default, not measured evidence.
            relationships.add(relation);
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
        if (choices > 0) notes.add("DECISIONS_PENDING: " + choices + " optional/alternative contexts remain unaccepted in structured evidence; no optional-only edge is included in the required view.");
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

    private static RequirementElementView element(Node node, Map<String,Integer> effectiveScores,
            Map<String, AnalysisScoreDetail> details, String contribution) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(node.id()); element.setTitle(node.name()); element.setTaxonomySheet(node.root());
        AnalysisScoreDetail detail = details.get(node.id());
        if (detail != null && !node.id().equals(detail.nodeCode())) {
            throw new IllegalArgumentException("Assessment belongs to another catalogue endpoint");
        }
        element.setScoreDetail(detail);
        element.setDirectLlmScore(detail == null ? 0 : detail.rawScore());
        Integer effective = effectiveScores.get(node.id());
        // A numeric layout fallback is not an assessment; scoreDetail remains explicitly null.
        element.setRelevance((detail != null ? detail.effectiveRelevance()
                : effective == null ? 0 : Math.max(0, Math.min(100, effective))) / 100.0);
        element.setOrigin(NodeOrigin.RELATION_EVIDENCE); element.setSelectedForImpact(true);
        element.setIncludedBecause(summary(contribution)); element.setPresenceReason(summary("Requirement-scoped contribution: " + contribution));
        return element;
    }
}
