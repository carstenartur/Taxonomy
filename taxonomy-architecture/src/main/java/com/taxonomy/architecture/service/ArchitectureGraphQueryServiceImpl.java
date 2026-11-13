package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.architecture.service.GraphSnapshotReadService.SelectedGraphSnapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.Snapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.SnapshotRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.dto.ChangeImpactView;
import com.taxonomy.dto.GraphNeighborhoodView;
import com.taxonomy.dto.ImpactElement;
import com.taxonomy.dto.ImpactRelationship;
import com.taxonomy.dto.RequirementImpactView;
import com.taxonomy.pipeline.PipelineConstants;

/**
 * Implementation of {@link ArchitectureGraphQueryService}.
 *
 * <p>Traverses taxonomy relations using BFS with relevance decay per hop.
 * Only Git-present relations of policy types (SUPPORTS, REALIZES, USES) are traversed.
 */
@Service
public class ArchitectureGraphQueryServiceImpl implements ArchitectureGraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureGraphQueryServiceImpl.class);

    /** Maximum allowed hops (clamped). */
    private static final int MAX_HOP_LIMIT = 5;

    /** Minimum relevance threshold — anything below is discarded. */
    static final double MIN_RELEVANCE = 0.20;

    /** Per-hop decay factor. */
    static final double HOP_DECAY = 0.70;

    // Anchor thresholds from the shared pipeline constants.
    private static final int ANCHOR_THRESHOLD_HIGH = PipelineConstants.ANCHOR_THRESHOLD_HIGH;
    private static final int ANCHOR_THRESHOLD_LOW  = PipelineConstants.ANCHOR_THRESHOLD_LOW;
    private static final int MIN_ANCHORS           = PipelineConstants.MIN_ANCHORS;

    /**
     * Propagation weights per relation type.
     * SUPPORTS propagates strongly (service-to-process),
     * REALIZES medium (capability-to-service),
     * USES weaker (app-to-service).
     */
    static final Map<String, Double> TYPE_WEIGHTS = Map.of(
            "SUPPORTS", 0.80,
            "REALIZES", 0.75,
            "USES", 0.60
    );

    private final GraphSnapshotReadService snapshots;

    public ArchitectureGraphQueryServiceImpl(GraphSnapshotReadService snapshots) {
        this.snapshots = snapshots;
    }

    // ── 1. Requirement Impact ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public RequirementImpactView findImpactForRequirement(RepositoryContext context, String commitId, Map<String, Double> scores,
                                                           String businessText, int maxHops) {
        RequirementImpactView view = new RequirementImpactView();
        view.setBusinessText(businessText);
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);
        SelectedGraphSnapshot selected = snapshots.read(context, commitId);
        Snapshot snapshot = selected.proof();
        view.setEvidence(evidence(snapshot, maxHops, clampedHops, "git-present-weighted-bfs-v1"));

        if (scores != null) scores.values().forEach(score -> {
            if (score == null || !Double.isFinite(score) || score < 0 || score > 100)
                throw new IllegalArgumentException("Scores must be finite percentages");
        });
        if (scores == null || scores.isEmpty()) {
            view.getNotes().add("No scores provided; impact analysis cannot be performed.");
            view.setEvidence(view.getEvidence().withCompleteness("NOT_EVALUATED_NO_ANCHORS"));
            snapshots.verifyHead(selected);
            return view;
        }

        // Select anchors using the same logic as RequirementArchitectureViewService
        List<Map.Entry<String, Double>> anchors = selectAnchors(scores);
        if (anchors.isEmpty()) {
            view.getNotes().add("No nodes met the anchor threshold; impact view is empty.");
            view.setEvidence(view.getEvidence().withCompleteness("NOT_EVALUATED_NO_ANCHORS"));
            snapshots.verifyHead(selected);
            return view;
        }

        // Build anchor relevance map
        Map<String, Double> anchorRelevances = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : anchors) {
            anchorRelevances.put(entry.getKey(), entry.getValue() / 100.0);
        }

        // BFS traversal from anchors
        TraversalResult traversal = bfsTraversal(snapshot, anchorRelevances, clampedHops, Direction.BOTH);

        // Convert to view elements
        view.setImpactedElements(toImpactElements(traversal, selected));
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalElements(view.getImpactedElements().size());
        view.setTotalRelationships(view.getTraversedRelationships().size());

        if (view.getImpactedElements().isEmpty()) {
            view.getNotes().add("Anchors were found but no connected elements via relations in the selected snapshot.");
        }

        log.info("Requirement impact: {} anchors, {} elements, {} relationships (maxHops={})",
                anchors.size(), view.getTotalElements(), view.getTotalRelationships(), clampedHops);

        snapshots.verifyHead(selected);
        return view;
    }

    // ── 2. Upstream / Downstream ───────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public GraphNeighborhoodView findUpstream(RepositoryContext context, String commitId, String nodeCode, int maxHops) {
        return findNeighborhood(context, commitId, nodeCode, maxHops, Direction.UPSTREAM);
    }

    @Override
    @Transactional(readOnly = true)
    public GraphNeighborhoodView findDownstream(RepositoryContext context, String commitId, String nodeCode, int maxHops) {
        return findNeighborhood(context, commitId, nodeCode, maxHops, Direction.DOWNSTREAM);
    }

    private GraphNeighborhoodView findNeighborhood(RepositoryContext context, String commitId, String nodeCode, int maxHops, Direction direction) {
        GraphNeighborhoodView view = new GraphNeighborhoodView();
        view.setOriginNodeCode(nodeCode);
        view.setDirection(direction.name());
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);
        SelectedGraphSnapshot selected = snapshots.read(context, commitId);
        Snapshot snapshot = selected.proof();
        view.setEvidence(evidence(snapshot, maxHops, clampedHops, "git-present-weighted-bfs-v1"));

        if (!selected.nodes().containsKey(nodeCode) && snapshot.relations().stream().noneMatch(
                edge -> edge.sourceCode().equals(nodeCode) || edge.targetCode().equals(nodeCode))) {
            view.getNotes().add("Node is not declared or connected in the selected snapshot: " + nodeCode);
        }

        Map<String, Double> startMap = Map.of(nodeCode, 1.0);
        TraversalResult traversal = bfsTraversal(snapshot, startMap, clampedHops, direction);

        // Remove the origin node from results
        List<ImpactElement> neighbors = toImpactElements(traversal, selected).stream()
                .filter(e -> !e.getNodeCode().equals(nodeCode))
                .collect(Collectors.toList());

        view.setNeighbors(neighbors);
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalNeighbors(neighbors.size());
        view.setTotalRelationships(traversal.relationships.size());

        if (neighbors.isEmpty()) {
            view.getNotes().add("No " + direction.name().toLowerCase() + " neighbors found for " + nodeCode);
        }

        log.info("{} query for {}: {} neighbors, {} relationships (maxHops={})",
                direction, nodeCode, neighbors.size(), traversal.relationships.size(), clampedHops);

        snapshots.verifyHead(selected);
        return view;
    }

    // ── 3. Failure / Change Impact ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ChangeImpactView findFailureImpact(RepositoryContext context, String commitId, String nodeCode, int maxHops) {
        return findFailureImpact(snapshots.read(context, commitId), nodeCode, maxHops);
    }

    @Override
    public ChangeImpactView findFailureImpact(SelectedGraphSnapshot selected, String nodeCode, int maxHops) {
        ChangeImpactView view = new ChangeImpactView();
        view.setFailedNodeCode(nodeCode);
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);
        Snapshot snapshot = selected.proof();
        view.setEvidence(evidence(snapshot, maxHops, clampedHops, "git-present-weighted-bfs-v1"));

        if (!selected.nodes().containsKey(nodeCode) && snapshot.relations().stream().noneMatch(
                edge -> edge.sourceCode().equals(nodeCode) || edge.targetCode().equals(nodeCode))) {
            view.getNotes().add("Node is not declared or connected in the selected snapshot: " + nodeCode);
        }

        // Failure propagates through both directions (anything connected is at risk)
        Map<String, Double> startMap = Map.of(nodeCode, 1.0);
        TraversalResult traversal = bfsTraversal(snapshot, startMap, clampedHops, Direction.BOTH);

        List<ImpactElement> allAffected = toImpactElements(traversal, selected).stream()
                .filter(e -> !e.getNodeCode().equals(nodeCode))
                .collect(Collectors.toList());

        // Split into directly affected (hop 1) and indirectly affected (hop 2+)
        List<ImpactElement> direct = allAffected.stream()
                .filter(e -> e.getHopDistance() == 1)
                .collect(Collectors.toList());
        List<ImpactElement> indirect = allAffected.stream()
                .filter(e -> e.getHopDistance() > 1)
                .collect(Collectors.toList());

        view.setDirectlyAffected(direct);
        view.setIndirectlyAffected(indirect);
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalAffected(allAffected.size());
        view.setTotalRelationships(traversal.relationships.size());

        if (allAffected.isEmpty()) {
            view.getNotes().add("No connected elements found for " + nodeCode);
        }

        log.info("Failure impact for {}: {} direct, {} indirect, {} relationships (maxHops={})",
                nodeCode, direct.size(), indirect.size(), traversal.relationships.size(), clampedHops);

        snapshots.verifyHead(selected);
        return view;
    }

    // ── Internal Traversal Engine ──────────────────────────────────────────

    private enum Direction { UPSTREAM, DOWNSTREAM, BOTH }

    /**
     * Intermediate result from BFS traversal.
     */
    private static class TraversalResult {
        final Map<String, Double> relevanceMap;
        final Map<String, Integer> hopMap;
        final Map<String, String> reasonMap;
        final List<ImpactRelationship> relationships;

        TraversalResult(Map<String, Double> relevanceMap, Map<String, Integer> hopMap,
                        Map<String, String> reasonMap, List<ImpactRelationship> relationships) {
            this.relevanceMap = relevanceMap;
            this.hopMap = hopMap;
            this.reasonMap = reasonMap;
            this.relationships = relationships;
        }
    }

    /**
     * Generic BFS traversal with relevance propagation and direction control.
     */
    private TraversalResult bfsTraversal(Snapshot snapshot, Map<String, Double> startRelevances,
                                          int maxHops, Direction direction) {
        Map<String, Double> relevanceMap = new LinkedHashMap<>(startRelevances);
        Map<String, Integer> hopMap = new LinkedHashMap<>();
        Map<String, String> reasonMap = new LinkedHashMap<>();
        List<ImpactRelationship> relationships = new ArrayList<>();

        // Initialize start nodes
        for (Map.Entry<String, Double> entry : startRelevances.entrySet()) {
            hopMap.put(entry.getKey(), 0);
            reasonMap.put(entry.getKey(), "origin");
        }

        Map<String, Double> currentFrontier = new LinkedHashMap<>(startRelevances);
        Set<String> visitedRelationIds = new HashSet<>();
        Map<String, List<SnapshotRelation>> adjacency = new LinkedHashMap<>();
        for (var edge : snapshot.relations()) {
            if (!TYPE_WEIGHTS.containsKey(edge.relationType().name())) continue;
            if (direction != Direction.UPSTREAM) adjacency.computeIfAbsent(edge.sourceCode(), ignored -> new ArrayList<>()).add(edge);
            if (direction != Direction.DOWNSTREAM) adjacency.computeIfAbsent(edge.targetCode(), ignored -> new ArrayList<>()).add(edge);
        }

        for (int hop = 1; hop <= maxHops; hop++) {
            Map<String, Double> nextFrontier = new LinkedHashMap<>();

            // Freeze each layer's scores: discoveries in this hop are expanded only next hop.
            for (var frontierEntry : currentFrontier.entrySet()) {
                String nodeCode = frontierEntry.getKey();
                double sourceRelevance = frontierEntry.getValue();
                if (sourceRelevance < MIN_RELEVANCE) continue;

                List<SnapshotRelation> relations = adjacency.getOrDefault(nodeCode, List.of());

                for (SnapshotRelation rel : relations) {
                    String targetCode = determineTarget(rel, nodeCode, direction);
                    if (targetCode == null) continue;

                    Double typeWeight = TYPE_WEIGHTS.get(rel.relationType().name());
                    if (typeWeight == null) continue;

                    double propagated = sourceRelevance * typeWeight;
                    if (hop > 1) {
                        propagated *= HOP_DECAY;
                    }

                    if (propagated < MIN_RELEVANCE) continue;

                    double existing = relevanceMap.getOrDefault(targetCode, 0.0);
                    if (propagated > existing) {
                        relevanceMap.put(targetCode, propagated);
                        hopMap.put(targetCode, hop);
                        reasonMap.put(targetCode,
                                "propagated via " + rel.relationType().name() + " from " + nodeCode);
                        nextFrontier.put(targetCode, propagated);
                    }

                    // Record traversed relationship (deduplicate by relation ID)
                    if (visitedRelationIds.add(rel.sourceCode() + "\u0000" + rel.relationType() + "\u0000" + rel.targetCode())) {
                        ImpactRelationship ir = new ImpactRelationship();
                        ir.setRelationId(rel.id());
                        ir.setSourceCode(rel.sourceCode());
                        ir.setTargetCode(rel.targetCode());
                        ir.setRelationType(rel.relationType().name());
                        ir.setPropagatedRelevance(propagated);
                        ir.setHopDistance(hop);
                        ir.setEvidence(new GraphEdgeEvidence(rel.authoritativeCommitId(), rel.causationId(),
                                rel.provenance(), rel.status(), rel.confidence()));
                        relationships.add(ir);
                    }
                }
            }

            currentFrontier = nextFrontier;
            if (currentFrontier.isEmpty()) break;
        }

        return new TraversalResult(relevanceMap, hopMap, reasonMap, relationships);
    }

    private String determineTarget(SnapshotRelation rel, String nodeCode, Direction direction) {
        if (direction != Direction.UPSTREAM && rel.sourceCode().equals(nodeCode)) return rel.targetCode();
        if (direction != Direction.DOWNSTREAM && rel.targetCode().equals(nodeCode)) return rel.sourceCode();
        return null;
    }

    static GraphQueryEvidence evidence(Snapshot snapshot, int requested, int effective, String policy) {
        var context = snapshot.context();
        boolean weightedTraversal = "git-present-weighted-bfs-v1".equals(policy);
        return new GraphQueryEvidence(context.repositoryId(), context.workspaceId(),
                com.taxonomy.model.WorkspaceOverlayScope.keyFor(context.workspaceId()),
                context.branch(), snapshot.commitId(), "GIT_BRANCH_PROJECTION", "READY", snapshot.relations().size(), policy,
                requested, effective, weightedTraversal ? MIN_RELEVANCE : 0, weightedTraversal ? HOP_DECAY : 1,
                weightedTraversal ? ANCHOR_THRESHOLD_HIGH : 0, weightedTraversal ? ANCHOR_THRESHOLD_LOW : 0,
                weightedTraversal ? MIN_ANCHORS : 0, weightedTraversal ? TYPE_WEIGHTS : Map.of(),
                "COMPLETE_WITHIN_QUERY_POLICY",
                "No path means no path in the selected snapshot under this query policy; it does not establish universal absence.");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<ImpactElement> toImpactElements(TraversalResult traversal, SelectedGraphSnapshot selected) {
        List<ImpactElement> elements = new ArrayList<>();
        Set<String> endpointCodes = new HashSet<>();
        selected.proof().relations().forEach(edge -> {
            endpointCodes.add(edge.sourceCode());
            endpointCodes.add(edge.targetCode());
        });

        for (Map.Entry<String, Double> entry : traversal.relevanceMap.entrySet()) {
            String code = entry.getKey();
            ImpactElement el = new ImpactElement();
            el.setNodeCode(code);
            el.setRelevance(entry.getValue());
            el.setHopDistance(traversal.hopMap.getOrDefault(code, 0));
            el.setIncludedBecause(traversal.reasonMap.getOrDefault(code, "unknown"));

            var node = selected.nodes().get(code);
            el.setTitle(node == null || node.title() == null ? code : node.title());
            el.setTaxonomySheet(node == null ? "" : node.taxonomyRoot());
            boolean endpoint = endpointCodes.contains(code);
            el.setNodeEvidence(new GraphNodeEvidence(node != null ? "SELECTED_DSL_ELEMENT"
                    : endpoint ? "SELECTED_RELATION_ENDPOINT" : "REQUEST_ANCHOR",
                    node != null || endpoint ? selected.proof().commitId() : null, node != null));

            elements.add(el);
        }

        // Sort by relevance descending
        elements.sort(Comparator.comparingDouble(ImpactElement::getRelevance).reversed());

        return elements;
    }

    private List<Map.Entry<String, Double>> selectAnchors(Map<String, Double> scores) {
        // Sort by score descending
        List<Map.Entry<String, Double>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        // Prefer all nodes above HIGH threshold
        List<Map.Entry<String, Double>> highAnchors = sorted.stream()
                .filter(e -> e.getValue() >= ANCHOR_THRESHOLD_HIGH)
                .collect(Collectors.toList());

        if (highAnchors.size() >= MIN_ANCHORS) {
            return highAnchors;
        }

        // Fallback: top-N above LOW threshold
        List<Map.Entry<String, Double>> lowAnchors = sorted.stream()
                .filter(e -> e.getValue() >= ANCHOR_THRESHOLD_LOW)
                .limit(MIN_ANCHORS)
                .collect(Collectors.toList());

        return lowAnchors;
    }

    private int clampHops(int maxHops) {
        return Math.max(1, Math.min(maxHops, MAX_HOP_LIMIT));
    }
}
