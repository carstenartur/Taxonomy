package com.taxonomy.architecture.service;

import com.taxonomy.dto.AnalysisCopilotResult;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.CoverageGap;
import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.MissingRelation;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.RelationSearchModel.Edge;
import com.taxonomy.dto.RelationSearchModel.Necessity;
import com.taxonomy.dto.RelationSearchModel.Outcome;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import org.springframework.stereotype.Service;

/**
 * Computes Copilot reports exclusively from an analysis's retained evidence.
 *
 * <p>This service performs no repository, catalogue, embedding or provider reads.
 * Its output is stored at the analysis completion boundary, so reconnecting
 * observers receive the same report without starting more work. Missing or
 * pruned evidence stays unknown; compatibility rules do not prove a gap.
 */
@Service
public class AnalysisCopilotService {
    private static final int MAX_NODES = 25_000;
    private static final int MAX_EDGES = 25_000;
    private static final int MAX_ANCHORS = 200;
    private static final int MAX_GAPS = 500;
    private final RelationCompatibilityMatrix compatibility = new RelationCompatibilityMatrix();

    /** A report's completion state describes this computation, not architecture completeness. */
    public AnalysisCopilotResult analyze(AnalysisResult analysis, String businessText) {
        GapAnalysisView gaps = new GapAnalysisView();
        gaps.setBusinessText(businessText);
        PatternDetectionView patterns = new PatternDetectionView();
        ArchitectureRecommendation recommendation = new ArchitectureRecommendation();
        recommendation.setBusinessText(businessText);
        ArrayList<String> notes = new ArrayList<>();
        if (analysis != null
            && "SUCCESS".equals(analysis.getStatus())
            && (analysis.getAnalysisCoverage() == null || !analysis.getAnalysisCoverage().hasOpenEvaluations())) {
            if (!AnalysisScope.orDefault(analysis.getAnalysisScope()).legacyFull()) {
                notes.add("Copilot reports were skipped because the analysis scope excludes taxonomy or relation work.");
                return result("SKIPPED_SCOPE", gaps, patterns, recommendation, notes);
            }

            notes.add("Reports describe retained evidence only; search exhaustion under a pruning policy does not prove architectural completeness.");
            Bounds bounds = new Bounds();
            Map<String, TaxonomyNodeDto> nodes = index(analysis.getTree(), bounds);
            Map<String, Double> scores = analysis.getScores();
            List<Entry<String, Double>> anchors = scores.entrySet()
                .stream()
                .filter(e -> e.getValue() >= ArchitectureGapService.DEFAULT_MIN_SCORE)
                .filter(e -> nodes.containsKey(e.getKey()) && !nodes.get(e.getKey()).isTaxonomyContainer())
                .sorted(Entry.<String, Double>comparingByValue().reversed().thenComparing(Entry.comparingByKey()))
                .toList();
            if (anchors.size() > MAX_ANCHORS) {
                bounds.truncated = true;
                anchors = anchors.subList(0, MAX_ANCHORS);
            }

            if (scores.keySet().stream().anyMatch(code -> !nodes.containsKey(code))) {
                bounds.incomplete = true;
                notes.add("Some scored identities have no retained catalogue metadata and were excluded from recommendations.");
            }

            if (nodes.isEmpty() || scores.isEmpty()) {
                bounds.incomplete = true;
                notes.add("Retained catalogue or scoring evidence is unavailable.");
            }

            RelationSearchReport report = analysis.getRelationSearchReport();
            if (report == null || !report.isSearchExhausted()) {
                bounds.incomplete = true;
                notes.add("Relation search evidence is absent or unfinished; unevidenced relations remain unknown.");
            }

            Map<String, List<RetainedRelation>> outgoing = verifiedRelations(report, nodes, bounds);
            this.analyzeGaps(anchors, nodes, outgoing, gaps, bounds);
            analyzePatterns(anchors, nodes, outgoing, patterns);
            recommend(analysis, anchors, nodes, recommendation);
            if (bounds.truncated) {
                notes.add("A retained-evidence processing or report size limit was reached; the displayed report is partial.");
            }

            if (bounds.invalidEdges) {
                notes.add("Unverified relations or relations inconsistent with retained catalogue identities were excluded from positive evidence.");
            }

            return result(!bounds.incomplete && !bounds.truncated ? "COMPLETED" : "PARTIAL", gaps, patterns, recommendation, notes);
        } else {
            notes.add("Copilot reports were skipped because the retained analysis is partial or unavailable.");
            return result("SKIPPED_PARTIAL", gaps, patterns, recommendation, notes);
        }
    }

    private static AnalysisCopilotResult result(
        String state, GapAnalysisView gaps, PatternDetectionView patterns, ArchitectureRecommendation recommendation, List<String> notes
    ) {
        if (state.startsWith("SKIPPED_")) {
            gaps.getNotes().addAll(notes);
            patterns.getNotes().addAll(notes);
            recommendation.getNotes().addAll(notes);
        }

        return new AnalysisCopilotResult(1, state, gaps, patterns, recommendation, notes);
    }

    private static Map<String, TaxonomyNodeDto> index(List<TaxonomyNodeDto> tree, Bounds bounds) {
        TreeMap<String, TaxonomyNodeDto> nodes = new TreeMap<>();
        Set<TaxonomyNodeDto> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<TaxonomyNodeDto> pending = new ArrayDeque<>();
        if (tree != null) {
            tree.stream().filter(Objects::nonNull).forEach(pending::addLast);
        }

        int visited = 0;

        while (!pending.isEmpty() && visited < MAX_NODES) {
            TaxonomyNodeDto node = pending.removeFirst();
            if (seen.add(node)) {
                visited++;
                if (node.getCode() != null && node.getTaxonomyRoot() != null) {
                    nodes.putIfAbsent(node.getCode(), node);
                }

                if (node.getChildren() != null) {
                    node.getChildren().stream().filter(Objects::nonNull).forEach(pending::addLast);
                }
            }
        }

        bounds.truncated |= !pending.isEmpty();
        return nodes;
    }

    /** Normalizes direction only after checking both retained endpoint identities. */
    private static Map<String, List<RetainedRelation>> verifiedRelations(
        RelationSearchReport report, Map<String, TaxonomyNodeDto> nodes, Bounds bounds
    ) {
        TreeMap<String, List<RetainedRelation>> outgoing = new TreeMap<>();
        if (report == null) {
            return outgoing;
        }

        TreeSet<RetainedRelation> retained = new TreeSet<>(
            Comparator.comparing(RetainedRelation::source)
                .thenComparing(RetainedRelation::type)
                .thenComparing(RetainedRelation::target)
                .thenComparing(edge -> !edge.unconditional())
                .thenComparing(RetainedRelation::qualifications)
        );
        int inspected = 0;

        for (Edge edge : report.result().edges()) {
            if (inspected++ >= MAX_EDGES) {
                bounds.truncated = true;
                break;
            }

            if (edge.contribution() != null
                && edge.target() != null
                && edge.direction() != null
                && edge.evidence() != null
                && edge.evidence().outcome() == Outcome.VERIFIED
                && Objects.equals(edge.target().id(), edge.evidence().targetId())) {
                TaxonomyNodeDto assessed = nodes.get(edge.contribution().source().id());
                TaxonomyNodeDto counterpart = nodes.get(edge.target().id());
                if (assessed != null
                    && counterpart != null
                    && edge.type() != null
                    && !assessed.isTaxonomyContainer()
                    && !counterpart.isTaxonomyContainer()
                    && Objects.equals(assessed.getTaxonomyRoot(), edge.contribution().source().root())
                    && Objects.equals(counterpart.getTaxonomyRoot(), edge.target().root())) {
                    retained.add(
                        new RetainedRelation(
                            edge.sourceId(),
                            edge.targetId(),
                            edge.type(),
                            nodes.get(edge.sourceId()).getTaxonomyRoot(),
                            nodes.get(edge.targetId()).getTaxonomyRoot(),
                            edge.evidence().necessity(),
                            text(edge.contribution().condition()),
                            text(edge.evidence().condition()),
                            text(edge.evidence().alternativeGroup())
                        )
                    );
                } else {
                    bounds.invalidEdges = true;
                    bounds.incomplete = true;
                }
            } else {
                bounds.invalidEdges = true;
            }
        }

        for (RetainedRelation relation : retained) {
            outgoing.computeIfAbsent(relation.source(), key -> new ArrayList<>()).add(relation);
        }

        return outgoing;
    }

    private void analyzeGaps(
        List<Entry<String, Double>> anchors,
        Map<String, TaxonomyNodeDto> nodes,
        Map<String, List<RetainedRelation>> outgoing,
        GapAnalysisView gaps,
        Bounds bounds
    ) {
        gaps.setTotalAnchors(anchors.size());
        gaps.getNotes().add("These are compatibility checks, not established missing relations. 'Not evidenced' means needs checking against further evidence.");

        for (Entry<String, Double> anchor : anchors) {
            String code = anchor.getKey();
            String root = nodes.get(code).getTaxonomyRoot();
            boolean hasOpenCheck = false;

            for (Entry<RelationType, Set<String>> expected : this.compatibility.getExpectedOutgoingRelations(root).entrySet()) {
                for (String targetRoot : new TreeSet<>(expected.getValue())) {
                    List<RetainedRelation> compatible = outgoing.getOrDefault(code, List.of())
                        .stream()
                        .filter(edge -> edge.type().equals(expected.getKey().name()) && edge.targetRoot().equals(targetRoot))
                        .toList();
                    if (!compatible.stream().anyMatch(RetainedRelation::unconditional)) {
                        hasOpenCheck = true;
                        if (gaps.getMissingRelations().size() >= MAX_GAPS) {
                            bounds.truncated = true;
                        } else {
                            String description = compatible.isEmpty()
                                ? code
                                    + " → "
                                    + expected.getKey().name()
                                    + " → "
                                    + targetRoot
                                    + " is not evidenced in the retained verified relations; needs checking."
                                : "Only conditional, optional or alternative evidence is retained; applicability needs checking. Retained example: "
                                    + compatible.getFirst().description();
                            gaps.getMissingRelations().add(new MissingRelation(code, root, expected.getKey().name(), targetRoot, description));
                        }
                    }
                }
            }

            if (hasOpenCheck) {
                gaps.getCoverageGaps()
                    .add(
                        new CoverageGap(
                            code,
                            root,
                            anchor.getValue(),
                            "Compatible relationships or their unconditional applicability need checking; this is not a confirmed architectural gap."
                        )
                    );
            }
        }

        gaps.setTotalGaps(gaps.getMissingRelations().size());
    }

    /** Each frontier entry retains one connected path to the actual endpoint. */
    private static void analyzePatterns(
        List<Entry<String, Double>> anchors,
        Map<String, TaxonomyNodeDto> nodes,
        Map<String, List<RetainedRelation>> outgoing,
        PatternDetectionView patterns
    ) {
        int applicable = 0;

        for (Entry<String, Double> anchor : anchors) {
            for (ArchitecturePatternService.PatternTemplate template : ArchitecturePatternService.patternTemplates()) {
                if (template.steps().getFirst().sourceRoot().equals(nodes.get(anchor.getKey()).getTaxonomyRoot())) {
                    applicable++;
                    Map<String, List<RetainedRelation>> frontier = new TreeMap<>();
                    frontier.put(anchor.getKey(), List.of());
                    List<RetainedRelation> longest = List.of();

                    for (ArchitecturePatternService.PatternStep step : template.steps()) {
                        Map<String, List<RetainedRelation>> next = new TreeMap<>();

                        for (Entry<String, List<RetainedRelation>> current : frontier.entrySet()) {
                            for (RetainedRelation edge : outgoing.getOrDefault(current.getKey(), List.of())) {
                                if (step.sourceRoot().equals(edge.sourceRoot())
                                    && step.targetRoot().equals(edge.targetRoot())
                                    && step.relationType().equals(edge.type())) {
                                    ArrayList<RetainedRelation> path = new ArrayList<>(current.getValue());
                                    path.add(edge);
                                    next.merge(
                                        edge.target(), path, (existing, candidate) -> unconditionalSteps(candidate) > unconditionalSteps(existing) ? candidate : existing
                                    );
                                }
                            }
                        }

                        if (next.isEmpty()) {
                            break;
                        }

                        frontier = next;
                        longest = next.values().stream().max(Comparator.comparingLong(AnalysisCopilotService::unconditionalSteps)).orElseThrow();
                    }

                    if (!longest.isEmpty()) {
                        List<String> expected = template.steps().stream().map(Object::toString).toList();
                        List<String> present = longest.stream().map(RetainedRelation::description).toList();
                        ArrayList<String> unknown = new ArrayList<>(
                            expected.subList(present.size(), expected.size()).stream().map(step -> step + " — not evidenced; needs checking").toList()
                        );
                        longest.stream()
                            .filter(edge -> !edge.unconditional())
                            .forEach(edge -> unknown.add(edge.description() + " — unconditional applicability remains unverified; needs checking."));
                        DetectedPattern detected = new DetectedPattern(
                            template.name(), expected, present, unknown, unconditionalSteps(longest) * 100.0 / expected.size()
                        );
                        if (unknown.isEmpty()) {
                            patterns.getMatchedPatterns().add(detected);
                        } else {
                            patterns.getIncompletePatterns().add(detected);
                        }
                    }
                }
            }
        }

        patterns.setPatternCoverage(applicable == 0 ? 0.0 : patterns.getMatchedPatterns().size() * 100.0 / applicable);
        patterns.getNotes()
            .add(
                "Pattern coverage is the fraction of applicable retained anchor checks with a connected, unconditional REQUIRED verified chain; it is not global architecture completeness."
            );
        patterns.getNotes()
            .add(
                "Pattern completeness counts unconditional REQUIRED steps in the displayed chain. Conditional, optional and alternative steps retain their qualifiers and require applicability review."
            );
        patterns.getNotes().add("Unevidenced steps remain unknown; provisional relation hypotheses never prove a pattern.");
    }

    private static long unconditionalSteps(List<RetainedRelation> path) {
        return path.stream().filter(RetainedRelation::unconditional).count();
    }

    private static void recommend(
        AnalysisResult analysis, List<Entry<String, Double>> anchors, Map<String, TaxonomyNodeDto> nodes, ArchitectureRecommendation recommendation
    ) {
        for (Entry<String, Double> anchor : anchors) {
            TaxonomyNodeDto node = nodes.get(anchor.getKey());
            String reason = analysis.getReasons().get(anchor.getKey());
            RecommendedElement element = new RecommendedElement(
                anchor.getKey(),
                node.getNameEn(),
                node.getTaxonomyRoot(),
                anchor.getValue(),
                reason != null && !reason.isBlank()
                    ? "Retained assessment reason: " + reason
                    : "Retained assessment relevance: " + anchor.getValue() + "%. No assessment reason was retained."
            );
            if (anchor.getValue() >= ArchitectureRecommendationService.HIGH_SCORE_THRESHOLD) {
                recommendation.getConfirmedElements().add(element);
            } else {
                recommendation.getProposedElements().add(element);
            }
        }

        recommendation.setConfidence(anchors.stream().mapToDouble(Entry::getValue).average().orElse(0.0));
        recommendation.getReasoning().add("Candidate ranking and confidence use only retained effective relevance scores.");
        recommendation.getNotes()
            .add("Confirmed elements are high-scoring assessment matches, not human approval. Proposed elements are retained candidates for review.");
        recommendation.getNotes()
            .add("Confidence is mean retained relevance of displayed candidates, not a probability of architectural correctness or completeness.");
        recommendation.getNotes().add("No new catalogue candidates, embedding scores or relation hypotheses were generated.");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static final class Bounds {
        boolean truncated;
        boolean incomplete;
        boolean invalidEdges;
    }

    /** Conditions and alternatives qualify positive evidence throughout all report views. */
    private record RetainedRelation(
        String source,
        String target,
        String type,
        String sourceRoot,
        String targetRoot,
        Necessity necessity,
        String sourceCondition,
        String condition,
        String alternativeGroup
    ) {
        boolean unconditional() {
            return necessity == Necessity.REQUIRED && sourceCondition.isBlank() && condition.isBlank() && alternativeGroup.isBlank();
        }

        String qualifications() {
            ArrayList<String> details = new ArrayList<>();
            details.add(necessity == null ? "necessity not retained" : necessity.name());
            if (!sourceCondition.isBlank()) {
                details.add("source condition: " + sourceCondition);
            }

            if (!condition.isBlank()) {
                details.add("condition: " + condition);
            }

            if (!alternativeGroup.isBlank()) {
                details.add("alternative group: " + alternativeGroup);
            }

            return String.join("; ", details);
        }

        String description() {
            String step = source + " → " + type + " → " + target;
            return unconditional() ? step : step + " [" + qualifications() + "]";
        }
    }
}
