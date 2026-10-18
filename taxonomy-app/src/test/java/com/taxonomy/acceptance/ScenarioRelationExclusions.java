package com.taxonomy.acceptance;

import com.taxonomy.dto.RelationSearchModel.Direction;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.dto.RelationSearchModel.Outcome;
import com.taxonomy.dto.RelationSearchModel.Phase;
import com.taxonomy.dto.RelationSearchModel.Query;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Authored negative/uncertain answers; absence from the positive list is never evidence. */
final class ScenarioRelationExclusions {
    record Answer(Outcome outcome, String rationale, String question, List<String> ruleIds) { }
    private record Rule(String id, Set<String> sources, Set<String> contributionIds, String type,
                        Direction direction, Set<Phase> phases, Set<String> candidates, Set<String> subtrees,
                        String rationale, String question, boolean unresolved) { }

    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, Integer> evaluations = new TreeMap<>();
    private final Map<String, Integer> ruleUsage = new TreeMap<>();
    private final String sha256;

    ScenarioRelationExclusions(JsonNode fixture) {
        JsonNode authored = fixture.at("/relationPlayback/exclusions");
        require(authored.isObject(), "Missing authored relation exclusions");
        require(authored.path("schemaVersion").asInt() == 1, "Unknown relation exclusion schema");
        require(fixture.path("id").asString().equals(authored.path("scenarioId").asString()), "Foreign exclusion scenario");
        require(ScenarioLlmPlayback.sha256(fixture.at("/requirement/text").asString())
                .equals(authored.path("requirementSha256").asString()), "Foreign exclusion requirement");
        sha256 = ScenarioLlmPlayback.sha256(authored.toString());
        Set<String> offered = new HashSet<>();
        fixture.path("bindings").propertyNames().forEach(offered::add);
        for (JsonNode scope : fixture.path("replies")) {
            String parent = scope.path("id").asString().replaceFirst("-(categories|products-[0-9]+)$", "");
            for (JsonNode candidate : scope.path("keys")) {
                String id = candidate.asString(); offered.add(id);
                if (!id.equals(parent)) {
                    String previous = parents.putIfAbsent(id, parent);
                    require(previous == null || previous.equals(parent), "Conflicting candidate ancestry: " + id);
                }
            }
        }
        for (var path : fixture.at("/relationPlayback/paths").properties()) {
            for (int index = 1; index < path.getValue().size(); index++) {
                String id = path.getValue().get(index).asString(), parent = path.getValue().get(index - 1).asString();
                String previous = parents.putIfAbsent(id, parent);
                require(previous == null || previous.equals(parent), "Conflicting authored path: " + id);
            }
        }
        for (String code : offered) ancestors(code); // Fail on cycles even when the affected rule is not visited.
        var contributions = new HashMap<String, Set<String>>();
        for (JsonNode part : fixture.at("/relationPlayback/sourceContributions")) {
            contributions.computeIfAbsent(part.path("nodeId").asString(), ignored -> new HashSet<>())
                    .add(part.path("contributionId").asString("default"));
        }
        Set<String> ids = new HashSet<>();
        for (String section : List.of("rules", "openScopes")) {
            require(authored.path(section).isArray(), "Missing explicit exclusion section: " + section);
            for (JsonNode entry : authored.path(section)) {
                String id = text(entry, "id"); require(ids.add(id), "Duplicate exclusion identity: " + id);
                Set<String> sources = strings(entry.path("sourceIds")), parts = strings(entry.path("sourceContributionIds"));
                Set<String> candidates = strings(entry.path("candidateIds")), subtrees = strings(entry.path("candidateSubtreeIds"));
                require(!sources.isEmpty() && !parts.isEmpty(), "Empty source contribution scope: " + id);
                require(!candidates.isEmpty() || !subtrees.isEmpty(), "Unbounded negative candidate scope: " + id);
                require(offered.containsAll(candidates) && offered.containsAll(subtrees), "Unknown excluded candidate: " + id);
                for (String source : sources) require(contributions.containsKey(source)
                                && contributions.get(source).stream().anyMatch(parts::contains),
                        "Unknown excluded source contribution: " + id + "/" + source);
                for (String part : parts) require(sources.stream().anyMatch(source -> contributions.get(source).contains(part)),
                        "Unused contribution scope: " + id + "/" + part);
                Set<Phase> phases = new HashSet<>();
                for (String phase : strings(entry.path("phases"))) phases.add(Phase.valueOf(phase));
                require(!phases.isEmpty(), "Missing exclusion phase: " + id);
                String type = text(entry, "type"); com.taxonomy.model.RelationType.valueOf(type);
                Set<String> quotes = strings(entry.path("quoteIds"));
                require(!quotes.isEmpty() && Set.of("F1", "F2", "F3", "F4", "F5", "A1", "SCOPE").containsAll(quotes),
                        "Missing original-clause scope: " + id);
                String rationale = text(entry, "rationale");
                require(rationale.length() <= 800, "Exclusion rationale exceeds the actual protocol limit: " + id);
                boolean unresolved = section.equals("openScopes");
                String question = unresolved ? text(entry, "question") : "";
                rules.add(new Rule(id, sources, parts, type, Direction.valueOf(text(entry, "direction")),
                        Set.copyOf(phases), candidates, subtrees, rationale, question, unresolved));
            }
        }
        require(!rules.isEmpty(), "Empty authored exclusion corpus");
        verifyPositiveClaims(fixture.at("/relationPlayback/claims"));
    }

    /** A response's precedence must never conceal contradictory authored evidence. */
    private void verifyPositiveClaims(JsonNode claims) {
        for (JsonNode claim : claims) for (Direction direction : Direction.values()) {
            String own = direction == Direction.OUTGOING ? "source" : "target";
            String other = direction == Direction.OUTGOING ? "target" : "source";
            String source = claim.path(own + "Id").asString();
            String part = claim.path(own + "ContributionId").asString("default");
            String counterpart = claim.path(other + "Id").asString();
            String type = claim.path("type").asString();
            String identity = claim.path("sourceId").asString() + ":" + type + ":" + claim.path("targetId").asString();
            for (Rule rule : rules) {
                if (!rule.sources().contains(source) || !rule.contributionIds().contains(part)
                        || !rule.type().equals(type) || rule.direction() != direction) continue;
                for (Phase phase : Phase.values()) {
                    if (!rule.phases().contains(phase)) continue;
                    // NAVIGATE must leave the entire real path to the positive endpoint open.
                    // VERIFY only assesses the endpoint; rejecting a different ancestor there
                    // does not prevent navigation to the authored counterpart.
                    Set<String> candidates = phase == Phase.NAVIGATE ? ancestors(counterpart) : Set.of(counterpart);
                    for (String candidate : candidates) {
                        require(!covers(rule, candidate), "Conflicting authored relation decisions: " + identity
                                + " [" + source + "@" + part + ":" + direction + "] is positive, but " + rule.id()
                                + " selects " + (rule.unresolved() ? "UNRESOLVED" : "REJECT")
                                + " for " + phase + ":" + candidate);
                    }
                }
            }
        }
    }

    private boolean covers(Rule rule, String candidate) {
        return rule.candidates().contains(candidate) || rule.subtrees().stream().anyMatch(ancestors(candidate)::contains);
    }

    Answer answer(Query query, Node node, String contributionId) {
        List<Rule> matched = rules.stream().filter(rule -> applies(rule, query, contributionId)
                && covers(rule, node.id())).toList();
        String scope = query.contribution().source().id() + "@" + contributionId + ":" + query.type()
                + ":" + query.direction() + ":" + query.phase() + ":" + node.id();
        require(!matched.isEmpty(), "Missing authored relation decision: " + scope);
        require(matched.stream().map(Rule::unresolved).distinct().count() == 1,
                "Conflicting negative and unresolved authored scopes: " + scope);
        boolean unresolved = matched.getFirst().unresolved();
        String rationale = String.join(" ", matched.stream().map(Rule::rationale).distinct().toList());
        String question = String.join(" ", matched.stream().map(Rule::question).filter(value -> !value.isBlank()).distinct().toList());
        require(rationale.length() <= 800 && question.length() <= 800, "Overlapping decision scopes exceed the actual response limit: " + scope);
        evaluations.merge(scope, 1, Integer::sum);
        matched.forEach(rule -> ruleUsage.merge(rule.id(), 1, Integer::sum));
        return new Answer(unresolved ? Outcome.UNRESOLVED : Outcome.REJECT, rationale, question,
                matched.stream().map(Rule::id).toList());
    }

    /** An authored concrete uncertainty is reached through the same real catalogue hierarchy. */
    Answer navigation(Query query, Node node, String contributionId) {
        if (query.phase() != Phase.NAVIGATE) return null;
        var matched = rules.stream().filter(rule -> rule.unresolved() && applies(rule, query, contributionId)
                && java.util.stream.Stream.concat(rule.candidates().stream(), rule.subtrees().stream())
                        .anyMatch(candidate -> !candidate.equals(node.id()) && ancestors(candidate).contains(node.id()))).toList();
        if (matched.isEmpty()) return null;
        String rationale = "Inspect the offered descendants to evaluate this specific unresolved identity: "
                + String.join(" ", matched.stream().map(Rule::rationale).distinct().toList());
        require(rationale.length() <= 800, "Authored uncertainty navigation exceeds the actual response limit");
        String scope = query.contribution().source().id() + "@" + contributionId + ":" + query.type()
                + ":" + query.direction() + ":NAVIGATE:" + node.id() + ":OPEN_PATH";
        evaluations.merge(scope, 1, Integer::sum);
        matched.forEach(rule -> ruleUsage.merge(rule.id(), 1, Integer::sum));
        return new Answer(Outcome.DESCEND, rationale, "", matched.stream().map(Rule::id).toList());
    }

    private static boolean applies(Rule rule, Query query, String contributionId) {
        return rule.sources().contains(query.contribution().source().id()) && rule.contributionIds().contains(contributionId)
                && rule.type().equals(query.type()) && rule.direction() == query.direction() && rule.phases().contains(query.phase());
    }

    void verifyCoverage(int repetitions) {
        require(!evaluations.isEmpty(), "No explicit negative candidate assessments were exercised");
        for (var evaluation : evaluations.entrySet()) {
            if (evaluation.getValue() != repetitions) throw new AssertionError(evaluation.getKey() + ": expected "
                    + repetitions + " independently executed authored assessments, got " + evaluation.getValue());
        }
    }

    Map<String, Object> coverage() {
        var result = new LinkedHashMap<String, Object>();
        result.put("evidenceKind", "AUTHORED_NEGATIVE_AND_UNRESOLVED_SCOPES");
        result.put("referenceSha256", sha256); result.put("ruleCount", rules.size());
        result.put("evaluatedScopeCount", evaluations.size()); result.put("evaluatedScopes", Map.copyOf(evaluations));
        result.put("ruleUsage", Map.copyOf(ruleUsage));
        return result;
    }

    private Set<String> ancestors(String code) {
        Set<String> result = new TreeSet<>();
        String current = code;
        while (current != null) {
            require(result.add(current), "Cyclic exclusion ancestry at " + current);
            current = parents.get(current);
        }
        return result;
    }
    private static Set<String> strings(JsonNode value) {
        require(value.isArray(), "Expected an explicit scope list"); Set<String> result = new TreeSet<>();
        for (JsonNode item : value) require(item.isString() && !item.asString().isBlank() && result.add(item.asString()), "Invalid or duplicate scope member");
        return Set.copyOf(result);
    }
    private static String text(JsonNode value, String key) {
        require(value.path(key).isString() && !value.path(key).asString().isBlank(), "Missing authored " + key);
        return value.path(key).asString();
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
