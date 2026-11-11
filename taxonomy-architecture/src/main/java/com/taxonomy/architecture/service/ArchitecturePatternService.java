package com.taxonomy.architecture.service;

import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.PatternDetectionEvidence;
import com.taxonomy.relations.service.RelationProjectionReadService.SnapshotRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Evaluates connected accepted chains in one explicitly selected canonical snapshot. */
@Service
public class ArchitecturePatternService {
    public static final String TEMPLATE_VERSION = "1.0.0";
    private static final int DEFAULT_MIN_SCORE = 50;
    private final GraphSnapshotReadService snapshots;
    public ArchitecturePatternService(GraphSnapshotReadService snapshots) { this.snapshots = snapshots; }

    record PatternStep(String sourceRoot, String relationType, String targetRoot) {
        @Override
        public String toString() {
            return sourceRoot + " → " + relationType + " → " + targetRoot;
        }
    }

    /**
     * A named sequence of steps.
     */
    record PatternTemplate(String name, List<PatternStep> steps) {}

    /** Shared definitions; evaluation uses selected canonical or frozen retained evidence. */
    static List<PatternTemplate> patternTemplates() {
        return List.of(
                new PatternTemplate("Full Stack", List.of(
                        new PatternStep("CP", "REALIZES", "CR"),
                        new PatternStep("CR", "SUPPORTS", "BP"),
                        new PatternStep("BP", "CONSUMES", "IP")
                )),
                new PatternTemplate("App Chain", List.of(
                        new PatternStep("UA", "USES", "CR"),
                        new PatternStep("CR", "SUPPORTS", "BP")
                )),
                new PatternTemplate("Role Chain", List.of(
                        new PatternStep("BR", "ASSIGNED_TO", "BP"),
                        new PatternStep("BP", "CONSUMES", "IP")
                ))
        );
    }


    @Transactional(readOnly = true)
    public PatternDetectionView detectForNode(RepositoryContext context, String commitId, String nodeCode) {
        var result = detectForScores(context, commitId, Map.of(nodeCode, 100d), DEFAULT_MIN_SCORE);
        result.setNodeCode(nodeCode);
        return result;
    }

    @Transactional(readOnly = true)
    public PatternDetectionView detectForScores(RepositoryContext context, String commitId,
                                                Map<String, Double> scores, int minScore) {
        if (minScore < 0 || minScore > 100) throw new IllegalArgumentException("minScore must be between 0 and 100");
        int threshold = minScore == 0 ? DEFAULT_MIN_SCORE : minScore;
        var selected = snapshots.read(context, commitId);
        var nodes = selected.nodes();
        boolean complete = nodes.values().stream().allMatch(node -> !node.taxonomyRoot().isBlank());
        Map<String, List<SnapshotRelation>> outgoing = new TreeMap<>();
        for (var relation : selected.proof().relations()) {
            if (!"accepted".equals(relation.status())) continue;
            var source = nodes.get(relation.sourceCode()); var target = nodes.get(relation.targetCode());
            if (source == null || target == null || source.taxonomyRoot().isBlank() || target.taxonomyRoot().isBlank()) {
                complete = false;
                continue;
            }
            outgoing.computeIfAbsent(relation.sourceCode(), key -> new ArrayList<>()).add(relation);
        }
        outgoing.values().forEach(edges -> edges.sort(Comparator.comparing(SnapshotRelation::targetCode)
                .thenComparing(edge -> edge.relationType().name())));
        var result = new PatternDetectionView();
        int eligible = 0, evaluated = 0;
        for (var anchor : new TreeMap<>(scores == null ? Map.<String,Double>of() : scores).entrySet()) {
            if (anchor.getValue() == null || !Double.isFinite(anchor.getValue()) || anchor.getValue() < threshold) continue;
            var node = nodes.get(anchor.getKey());
            if (node == null) continue;
            var applicable = patternTemplates().stream().filter(template -> template.steps().getFirst().sourceRoot().equals(node.taxonomyRoot())).toList();
            if (applicable.isEmpty()) continue;
            eligible++;
            for (var template : applicable) {
                evaluated++;
                var detected = checkPattern(anchor.getKey(), template, outgoing, nodes);
                if (detected.getCompleteness() == 100d) result.getMatchedPatterns().add(detected);
                else result.getIncompletePatterns().add(detected);
            }
        }
        result.setPatternCoverage(evaluated == 0 ? null : result.getMatchedPatterns().size() * 100d / evaluated);
        String completeness = !complete ? "INCOMPLETE_CANONICAL_EVIDENCE"
                : eligible == 0 ? "NOT_EVALUATED_NO_ANCHORS" : "COMPLETE_WITHIN_PATTERN_TEMPLATES";
        result.setEvidence(new PatternDetectionEvidence(context.repositoryId(), context.workspaceId(),
                com.taxonomy.model.WorkspaceOverlayScope.keyFor(context.workspaceId()), context.branch(), selected.proof().commitId(),
                "CANONICAL_SNAPSHOT", "GIT_BRANCH_PROJECTION", "READY", TEMPLATE_VERSION, threshold, eligible, completeness));
        if (eligible == 0) result.getNotes().add("No declared, threshold-qualified anchors have an applicable pattern template; pattern coverage was not assessed.");
        if (!complete) result.getNotes().add("Unknown canonical element types or unresolved accepted endpoints prevent complete pattern evidence.");
        result.getNotes().add("Coverage counts evaluated anchor/template pairs within this selected revision. Missing steps are not evidenced by accepted canonical relations; they are not business obligation violations or claims about external architecture.");
        snapshots.verifyHead(selected);
        return result;
    }

    private DetectedPattern checkPattern(String anchor, PatternTemplate template,
            Map<String, List<SnapshotRelation>> outgoing, Map<String, GraphSnapshotReadService.SnapshotNode> nodes) {
        Map<String,List<SnapshotRelation>> frontier = new TreeMap<>();
        frontier.put(anchor,List.of());
        List<SnapshotRelation> longest = List.of();
        for (var step : template.steps()) {
            Map<String,List<SnapshotRelation>> next = new TreeMap<>();
            for (var current : frontier.entrySet()) {
                if (!step.sourceRoot().equals(nodes.get(current.getKey()).taxonomyRoot())) continue;
                for (var edge : outgoing.getOrDefault(current.getKey(),List.of())) {
                    if (!step.relationType().equals(edge.relationType().name())
                            || !step.targetRoot().equals(nodes.get(edge.targetCode()).taxonomyRoot())) continue;
                    var path = new ArrayList<>(current.getValue()); path.add(edge);
                    next.putIfAbsent(edge.targetCode(), List.copyOf(path));
                }
            }
            // An absent middle step ends this connected path; never reuse its old frontier.
            if (next.isEmpty()) break;
            frontier = next; longest = next.values().iterator().next();
        }
        var expected = template.steps().stream().map(PatternStep::toString).toList();
        var present = longest.stream().map(edge -> edge.sourceCode()+" "+edge.relationType()+" "+edge.targetCode()).toList();
        var missing = expected.subList(present.size(),expected.size()).stream().map(step -> step+" — not evidenced in selected canonical snapshot").toList();
        return new DetectedPattern(template.name(),expected,present,missing,present.size()*100d/expected.size());
    }
}
