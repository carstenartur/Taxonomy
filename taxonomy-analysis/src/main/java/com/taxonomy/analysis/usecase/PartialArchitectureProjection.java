package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.*;
import java.util.*;

/**
 * Bounded, local-only projection of accepted assessments from the result's own catalogue snapshot.
 * Complements an evidence view; never infers edges, consults live catalogue data or calls a provider.
 */
public final class PartialArchitectureProjection {
    private static final int MAX_SNAPSHOT_NODES = 100_000;
    private static final int MAX_PATH_DEPTH = 256;
    private PartialArchitectureProjection() { }

    public static RequirementArchitectureView complete(AnalysisResult result, RequirementArchitectureView view, int maxNodes) {
        Objects.requireNonNull(result);
        if (view == null) view = new RequirementArchitectureView();
        var notes = new LinkedHashSet<>(view.getNotes() == null ? List.<String>of() : view.getNotes());
        var index = new LinkedHashMap<String, TaxonomyNodeDto>();
        var ambiguous = new HashSet<String>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<TaxonomyNodeDto, Boolean>());
        var pending = new ArrayDeque<TaxonomyNodeDto>();
        boolean truncated = false;
        if (result.getTree() != null) for (var root : result.getTree()) {
            if (pending.size() >= MAX_SNAPSHOT_NODES) { truncated = true; break; }
            if (root != null) pending.addLast(root);
        }
        int inspected = 0;
        while (!pending.isEmpty() && inspected++ < MAX_SNAPSHOT_NODES) {
            var node = pending.removeFirst();
            if (!visited.add(node)) continue;
            String code = node.getCode();
            if (code != null && !code.isBlank() && index.putIfAbsent(code, node) != null) ambiguous.add(code);
            if (node.getChildren() != null) for (var child : node.getChildren()) {
                if (pending.size() >= MAX_SNAPSHOT_NODES) { truncated = true; break; }
                if (child != null) pending.addLast(child);
            }
        }
        ambiguous.forEach(index::remove);
        if (truncated || !pending.isEmpty() || !ambiguous.isEmpty()) notes.add("CATALOGUE_PROJECTION_LIMIT: only unambiguous retained catalogue nodes are shown.");

        var elements = new ArrayList<>(view.getIncludedElements() == null ? List.<RequirementElementView>of() : view.getIncludedElements());
        var included = new HashSet<String>();
        elements.forEach(element -> included.add(element.getNodeCode()));
        // Typed score interpretation belongs to AnalysisResult, not to rendering.
        var scores = result.getScores();
        var details = result.getScoreDetails();
        var ranked = scores.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0 && entry.getValue() <= 100)
                .filter(entry -> details.get(entry.getKey()) != null
                        && details.get(entry.getKey()).effectiveRelevance() == entry.getValue())
                .filter(entry -> index.containsKey(entry.getKey()) && !index.get(entry.getKey()).isTaxonomyContainer())
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .toList();
        int limit = maxNodes > 0 ? maxNodes : MAX_SNAPSHOT_NODES;
        int added = 0;
        for (var entry : ranked) {
            if (included.contains(entry.getKey())) continue;
            if (elements.size() >= limit) { notes.add("NODE_LIMIT: complete assessments remain in the analysis result."); break; }
            var node = index.get(entry.getKey());
            var detail = details.get(node.getCode());
            var element = new RequirementElementView();
            element.setNodeCode(node.getCode());
            element.setTitle(node.getNameEn() == null || node.getNameEn().isBlank() ? node.getCode() : node.getNameEn());
            element.setTaxonomySheet(node.getTaxonomyRoot()); element.setScoreDetail(detail);
            element.setDirectLlmScore(detail.rawScore());
            element.setRelevance(detail.effectiveRelevance() / 100.0);
            element.setOrigin(NodeOrigin.DIRECT_SCORED); element.setAnchor(true);
            element.setPresenceReason("Directly assessed relevance; this alone does not establish a relationship or required implementation.");
            element.setIncludedBecause(element.getPresenceReason());
            element.setSelectedForImpact(true);
            elements.add(element); included.add(node.getCode()); added++;
        }
        // Containment is the retained hierarchy, not a generated architectural relationship.
        for (var element : elements) {
            var node = index.get(element.getNodeCode());
            if (node == null || element.getHierarchyPath() != null) continue;
            var path = new LinkedList<String>(); var seen = new HashSet<String>();
            String parent = null;
            while (node != null && path.size() < MAX_PATH_DEPTH && seen.add(node.getCode())) {
                path.addFirst(node.getCode());
                var ancestor = index.get(node.getParentCode());
                if (ancestor == null || !Objects.equals(ancestor.getTaxonomyRoot(), node.getTaxonomyRoot())) break;
                if (path.size() >= MAX_PATH_DEPTH) {
                    // The unvisited ancestry may close a cycle beyond this bounded scan.
                    // Retain the assessed element, but never certify incomplete containment.
                    parent = null;
                    notes.add("HIERARCHY_PROJECTION_LIMIT: ancestry could not be fully checked; affected nodes are shown without containment.");
                    break;
                }
                if (parent == null && included.contains(ancestor.getCode()) && !seen.contains(ancestor.getCode())) parent = ancestor.getCode();
                node = ancestor;
            }
            element.setHierarchyPath(String.join(" > ", path)); element.setTaxonomyDepth(Math.max(0, path.size() - 1));
            // A cyclic path is not valid containment, even if its nodes are independently assessed.
            if (node != null && index.get(node.getParentCode()) != null && seen.contains(node.getParentCode())) parent = null;
            element.setParentNodeCode(parent);
        }
        if (added > 0) notes.add("DIRECT_ASSESSMENT_ONLY: additional nodes retain their assessed relevance; no relationship is inferred from a missing answer.");
        if (elements.isEmpty()) notes.add("NO_ASSESSED_ARCHITECTURE: no usable assessed catalogue elements are available yet.");
        else notes.removeIf(note -> note.startsWith("NO_ASSESSED_ARCHITECTURE:"));
        var elementsByCode = new HashMap<String, RequirementElementView>();
        elements.forEach(element -> elementsByCode.putIfAbsent(element.getNodeCode(), element));
        var anchors = new LinkedHashMap<String, RequirementAnchor>();
        if (view.getAnchors() != null) for (var anchor : view.getAnchors()) {
            if (anchor == null || anchor.getNodeCode() == null || anchors.containsKey(anchor.getNodeCode())) continue;
            var element = elementsByCode.get(anchor.getNodeCode());
            var node = index.get(anchor.getNodeCode());
            if (element != null && element.isAnchor() && node != null && !node.isTaxonomyContainer()) {
                anchors.put(anchor.getNodeCode(), anchor);
            }
        }
        for (var element : elements) {
            if (element.getOrigin() != NodeOrigin.DIRECT_SCORED || !element.isAnchor()) continue;
            var detail = element.getScoreDetail();
            if (detail == null || anchors.containsKey(element.getNodeCode())) continue;
            String kind = detail.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            anchors.put(element.getNodeCode(), new RequirementAnchor(element.getNodeCode(),
                    detail.effectiveRelevance(), "Direct " + kind + " assessment; raw score "
                    + detail.rawScore() + ", effective relevance " + detail.effectiveRelevance() + "%."));
        }
        view.setIncludedElements(elements); view.setTotalElements(elements.size());
        view.setTotalRelationships(view.getIncludedRelationships() == null ? 0 : view.getIncludedRelationships().size());
        view.setAnchors(new ArrayList<>(anchors.values()));
        view.setTotalAnchors(view.getAnchors().size());
        view.setNotes(new ArrayList<>(notes)); view.setRelationSearchReport(result.getRelationSearchReport());
        view.setAnalysisCoverage(result.getAnalysisCoverage()); view.setAnalysisStatus(result.getStatus());
        return view;
    }

}
