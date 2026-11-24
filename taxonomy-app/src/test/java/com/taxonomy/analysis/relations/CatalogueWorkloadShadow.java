package com.taxonomy.analysis.relations;

import com.taxonomy.acceptance.ScenarioLlmPlayback;
import com.taxonomy.analysis.service.AnalysisLanguageContext;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;

/** Uses the original local transcript as a closed oracle for the existing distributed scheduler. */
public final class CatalogueWorkloadShadow {
    private CatalogueWorkloadShadow() { }

    public static Map<String, Object> observe(TaxonomyService taxonomy, String original, Map<String, ? extends Number> scores,
            RequirementRelationSearch.Options options, RelationCompatibilityMatrix rules, String provider, AnalysisLanguage language,
            String snapshotId, List<ScenarioLlmPlayback.AcceptedCall> exchanges) throws Exception {
        long started = System.nanoTime();
        var result = new TreeMap<String, Object>();
        var transcript = new Transcript(exchanges);
        var full = new Shard(taxonomy, null);
        try (var locale = AnalysisLanguageContext.open(language)) {
            long preparing = System.nanoTime();
            var plan = search(full, rules, provider, transcript).prepare("closed-transcript", original, scores,
                    List.of(snapshotId), options, true);
            result.put("prepareNanos", System.nanoTime() - preparing);
            if (transcript.issue != null) return unavailable(result, transcript.issue, started);
            if (plan.inlineReport() != null) return unavailable(result, "Inline preparation", started);
            if (!plan.stopReason().isEmpty()) return unavailable(result, plan.stopReason(), started);
            result.put("planItems", plan.items().size());
            result.put("sourceCalls", plan.sourceCalls());
            return waves(result, taxonomy, original, rules, provider, plan, transcript, started);
        }
    }

    private static Map<String, Object> waves(Map<String, Object> result, TaxonomyService taxonomy, String original,
            RelationCompatibilityMatrix rules, String provider, RelationSearchDistribution.Plan plan, Transcript transcript, long started) {
        var shards = new HashMap<String, Shard>();
        var persisted = new ArrayList<RelationSearchDistribution.WorkResult>();
        var contiguous = new TreeMap<Integer, Integer>();
        var regrouped = new TreeMap<Integer, Integer>();
        long readyNanos = 0, evaluateNanos = 0;
        int verifiedWaves = 0, funded = 0;
        String unavailable = null;
        while (true) {
            long at = System.nanoTime();
            var wave = RelationSearchDistribution.ready(plan, persisted);
            readyNanos += System.nanoTime() - at;
            if (wave.isEmpty()) break;
            var finished = new ArrayList<RelationSearchDistribution.WorkResult>();
            for (var grant : wave) {
                at = System.nanoTime();
                var shard = shards.computeIfAbsent(grant.targetRoot().code(), code -> new Shard(taxonomy, code));
                var branch = search(shard, rules, provider, transcript).evaluate(original, plan, grant);
                evaluateNanos += System.nanoTime() - at;
                if (transcript.issue != null || !branch.stopReason().isEmpty()) {
                    unavailable = transcript.issue == null ? branch.stopReason() : transcript.issue;
                }
                finished.add(branch);
            }
            if (unavailable != null) break;
            // Only this fully evaluated wave may refund capacity before another ready call.
            var exactGroups = new LinkedHashMap<IntentKey, Set<String>>();
            var run = new HashSet<String>();
            IntentKey previous = null;
            for (var work : wave) {
                var item = plan.items().get(work.ordinal());
                if (work.maxCalls() != item.maxQueries() || work.maxWorkItems() != item.maxQueries()) {
                    countWidth(contiguous, run.size());
                    run.clear();
                    previous = null;
                    continue;
                }
                funded++;
                var intent = RelationSearchDistribution.intent(plan, item);
                var key = new IntentKey(intent.contribution(), intent.type(), intent.direction());
                String root = work.targetRoot().code();
                exactGroups.computeIfAbsent(key, unused -> new HashSet<>()).add(root);
                if (!key.equals(previous) || run.contains(root)) {
                    countWidth(contiguous, run.size());
                    run.clear();
                }
                run.add(root);
                previous = key;
            }
            countWidth(contiguous, run.size());
            exactGroups.values().forEach(roots -> countWidth(regrouped, roots.size()));
            persisted.addAll(finished);
            verifiedWaves++;
        }
        result.put("readyNanos", readyNanos);
        result.put("evaluateNanos", evaluateNanos);
        result.put("verifiedWaves", verifiedWaves);
        result.put("verifiedGrants", persisted.size());
        result.put("fullyFundedGrants", funded);
        result.put("contiguousSiblingWidths", contiguous);
        result.put("regroupedSiblingWidths", regrouped);
        result.put("exactTranscriptMatches", transcript.matches);
        if (unavailable != null) return unavailable(result, unavailable, started);
        RelationSearchDistribution.validateResults(plan, persisted);
        result.put("state", "AVAILABLE");
        result.put("elapsedNanos", System.nanoTime() - started);
        return result;
    }

    public static int joinedNavigateCharacters(Query first, List<Node> nodes) {
        var joined = new Query(first.original(), first.contribution(), first.type(), first.direction(),
                Phase.NAVIGATE, nodes.stream().distinct().sorted(Comparator.comparing(Node::id)).toList(), null);
        return RelationSearchProtocol.requestCharacters(RelationSearchProtocol.decisionPrompt(joined, Map.of()));
    }

    private static void countWidth(Map<Integer, Integer> widths, int width) {
        if (width >= 2) widths.merge(width, 1, Integer::sum);
    }

    private static Map<String, Object> unavailable(Map<String, Object> result, String reason, long started) {
        result.put("state", "UNAVAILABLE_BEYOND_RECORDED_PREFIX");
        result.put("reason", reason);
        result.put("elapsedNanos", System.nanoTime() - started);
        return result;
    }

    private record IntentKey(Contribution contribution, String type, Direction direction) { }

    private static RequirementRelationSearch search(Shard shard, RelationCompatibilityMatrix rules, String provider,
                                                      Transcript transcript) {
        return new RequirementRelationSearch(shard, rules, transcript, () -> { }, provider);
    }

    private static final class Shard implements RequirementRelationSearch.InputCatalogue {
        private final TaxonomyService taxonomy;
        private final String root;
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<String, List<Node>> children = new HashMap<>();
        Shard(TaxonomyService taxonomy, String root) { this.taxonomy = taxonomy; this.root = root; }

        @Override public Node find(String id) {
            if (root != null) throw new IllegalStateException("Target grant cannot access source nodes");
            return nodes.computeIfAbsent(id, code -> {
                var node = taxonomy.getNodeByCode(code);
                return node == null ? null : RequirementRelationSearchService.catalogueNodes(taxonomy, List.of(node)).getFirst();
            });
        }
        @Override public List<Node> roots() {
            return RequirementRelationSearchService.catalogueNodes(taxonomy, taxonomy.getRootNodes().stream()
                    .filter(node -> root == null || root.equals(node.getTaxonomyRoot())).toList());
        }
        @Override public List<Node> children(Node node) {
            if (root != null && !root.equals(node.root())) throw new IllegalArgumentException("Foreign target root");
            return children.computeIfAbsent(node.id(), id -> {
                var children = FrozenCatalogueContext.current().children(id);
                if (root != null && children.stream().anyMatch(child -> !root.equals(child.getTaxonomyRoot())))
                    throw new IllegalArgumentException("Foreign target children");
                return RequirementRelationSearchService.catalogueNodes(taxonomy, children);
            });
        }
    }

    private static final class Transcript implements Function<String, String> {
        private final ScenarioLlmPlayback authored = ScenarioLlmPlayback.flood();
        private final ObjectMapper json = new ObjectMapper();
        private final Map<String, Set<String>> pairs = new HashMap<>();
        String issue;
        int matches;

        Transcript(List<ScenarioLlmPlayback.AcceptedCall> exchanges) throws Exception {
            authored.recordCompactCalls();
            for (var exchange : exchanges)
                pairs.computeIfAbsent(exchange.promptSha256(), unused -> new HashSet<>()).add(exchange.responseSha256());
        }

        @Override public String apply(String prompt) {
            if (issue != null) throw new IllegalStateException(issue);
            String hash = ScenarioLlmPlayback.sha256(prompt);
            var observed = pairs.getOrDefault(hash, Set.of());
            if (observed.size() != 1) return fail((observed.isEmpty() ? "Missing" : "Ambiguous") + " exact prompt " + hash);
            String envelope = authored.respond(prompt);
            if (!observed.contains(ScenarioLlmPlayback.sha256(envelope))) return fail("Different exact response for " + hash);
            matches++;
            return json.readTree(envelope).at("/choices/0/message/content").asText();
        }

        private String fail(String message) {
            issue = message;
            throw new IllegalStateException(message);
        }
    }
}
