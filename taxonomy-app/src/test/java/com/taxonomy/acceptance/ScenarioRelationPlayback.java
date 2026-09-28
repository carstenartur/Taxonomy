package com.taxonomy.acceptance;

import com.taxonomy.dto.RelationSearchModel.Contribution;
import com.taxonomy.dto.RelationSearchModel.Decision;
import com.taxonomy.dto.RelationSearchModel.Direction;
import com.taxonomy.dto.RelationSearchModel.Necessity;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.dto.RelationSearchModel.Outcome;
import com.taxonomy.dto.RelationSearchModel.Phase;
import com.taxonomy.dto.RelationSearchModel.Query;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Authored test responses at the raw LLM boundary; never a second search implementation. */
final class ScenarioRelationPlayback {
    static final String PREFIX = "You assess requirement-scoped architectural relationships. Protocol: relation-downwalk-v1.\n";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String original;
    private final Set<String> offeredIds = new HashSet<>();
    private final Map<String, JsonNode> contributions = new LinkedHashMap<>();
    private final Map<String, List<String>> paths = new LinkedHashMap<>();
    private final List<JsonNode> claims = new ArrayList<>();
    private final Map<String, Integer> verified = new LinkedHashMap<>();

    ScenarioRelationPlayback(JsonNode fixture) {
        original = fixture.at("/requirement/text").asString();
        fixture.path("bindings").propertyNames().forEach(offeredIds::add);
        fixture.path("replies").forEach(rule -> rule.path("keys").forEach(key -> offeredIds.add(key.asString())));
        JsonNode corpus = fixture.path("relationPlayback");
        for (JsonNode value : corpus.path("sourceContributions")) {
            String id = value.path("nodeId").asString();
            require(offeredIds.contains(id), "Unknown contribution source: " + id);
            require(!value.path("text").asString().isBlank(), "Missing scoped contribution");
            require(original.contains(value.path("quote").asString()) && !value.path("quote").asString().isBlank(), "Fabricated contribution quote");
            require(contributions.putIfAbsent(id, value.deepCopy()) == null, "Duplicate source contribution");
        }
        for (var entry : corpus.path("paths").properties()) {
            List<String> path = new ArrayList<>();
            entry.getValue().forEach(id -> path.add(id.asString()));
            require(!path.isEmpty() && path.getLast().equals(entry.getKey()) && offeredIds.containsAll(path), "Invalid catalogue path");
            require(new HashSet<>(path).size() == path.size(), "Cyclic fixture path");
            paths.put(entry.getKey(), List.copyOf(path));
        }
        Set<String> signatures = new HashSet<>();
        for (JsonNode claim : corpus.path("claims")) {
            String source = claim.path("sourceId").asString(), target = claim.path("targetId").asString();
            require(contributions.containsKey(source) && contributions.containsKey(target)
                    && paths.containsKey(source) && paths.containsKey(target) && !source.equals(target), "Invalid fixture endpoints");
            String quote = claim.path("quote").asString();
            require(!quote.isBlank() && original.contains(quote), "Fabricated relationship quote");
            require(signatures.add(signature(claim)), "Duplicate fixture claim");
            claims.add(claim.deepCopy());
        }
        require(!contributions.isEmpty() && !claims.isEmpty(), "Missing relation response corpus");
    }

    Reply respond(String prompt) {
        int inputOffset = prompt.indexOf("INPUT\n");
        require(prompt.startsWith(PREFIX) && inputOffset >= 0, "Unknown relation protocol");
        JsonNode input = JSON.readTree(prompt.substring(inputOffset + 6));
        require(original.equals(input.path("original").asString()), "Unknown scenario requirement");
        if (input.has("nodes")) {
            List<Map<String, Object>> selections = new ArrayList<>();
            Set<String> ids = new TreeSet<>();
            for (JsonNode node : input.get("nodes")) {
                String id = node.path("id").asString();
                require(offeredIds.contains(id) && ids.add(id), "Unknown or repeated source: " + id);
                JsonNode contribution = contributions.get(id);
                selections.add(Map.of("nodeId", id, "outcome", contribution == null ? "REJECT" : "EXPLICIT",
                        "contributions", contribution == null ? List.of() : List.of(Map.of(
                                "text", contribution.path("text").asString(), "quote", contribution.path("quote").asString(),
                                "condition", contribution.path("condition").asString())),
                        "rationale", contribution == null ? "Authored fixture: navigation context, not an additional project component."
                                : "Authored fixture: particular contribution quoted from this requirement.", "question", ""));
            }
            return new Reply("relation:EXTRACT:" + String.join(",", ids), JSON.writeValueAsString(Map.of("selections", selections)));
        }
        Query query = JSON.treeToValue(input, Query.class);
        Contribution contribution = query.contribution();
        JsonNode known = contributions.get(contribution.source().id());
        require(known != null && known.path("text").asString().equals(contribution.text())
                && known.path("quote").asString().equals(contribution.quote())
                && known.path("condition").asString().equals(contribution.condition()), "Unknown source contribution");
        List<Decision> decisions = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (Node node : query.candidates()) {
            require(offeredIds.contains(node.id()) && ids.add(node.id()), "Unknown or repeated target: " + node.id());
            decisions.add(decision(query, node));
        }
        return new Reply("relation:" + query.phase() + ":" + contribution.source().id() + ":" + query.type()
                + ":" + query.direction() + ":" + String.join(",", ids), JSON.writeValueAsString(Map.of("decisions", decisions)));
    }

    void verifyCoverage(int repetitions) {
        for (JsonNode claim : claims) {
            int count = verified.getOrDefault(signature(claim), 0);
            // Each oriented claim is checked once from each of its two independently
            // extracted endpoints. The two Copilot passes must repeat both checks.
            if (count != repetitions * 2) {
                throw new AssertionError(signature(claim) + ": expected " + (repetitions * 2) + " verified replies, got " + count);
            }
        }
    }

    private Decision decision(Query query, Node node) {
        boolean descendant = false;
        for (JsonNode claim : claims) {
            boolean outgoing = query.direction() == Direction.OUTGOING;
            String from = claim.path(outgoing ? "sourceId" : "targetId").asString();
            String counterpart = claim.path(outgoing ? "targetId" : "sourceId").asString();
            if (!from.equals(query.contribution().source().id()) || !claim.path("type").asString().equals(query.type())) { continue; }
            if (counterpart.equals(node.id()) && !node.container()) {
                Decision expected = new Decision(node.id(), Outcome.MATCH, contributions.get(counterpart).path("text").asString(),
                        claim.path("quote").asString(), Necessity.valueOf(claim.path("necessity").asString()),
                        claim.path("condition").asString(), claim.path("alternativeGroup").asString(),
                        "Authored fixture: quoted functional connection between these particular endpoints.", "");
                if (query.phase() == Phase.VERIFY) {
                    if (!sameClaim(expected, query.proposal())) { return negative(node.id(), Outcome.REJECT); }
                    verified.merge(signature(claim), 1, Integer::sum);
                    return new Decision(expected.targetId(), Outcome.VERIFIED, expected.contribution(), expected.quote(),
                            expected.necessity(), expected.condition(), expected.alternativeGroup(),
                            "Authored fixture: checked against the stored claim, not copied blindly from the proposal.", "");
                }
                return expected;
            }
            descendant |= paths.get(counterpart).contains(node.id());
        }
        return negative(node.id(), descendant && query.phase() == Phase.NAVIGATE ? Outcome.DESCEND : Outcome.REJECT);
    }
    private static boolean sameClaim(Decision expected, Decision supplied) {
        return expected.targetId().equals(supplied.targetId()) && expected.contribution().equals(supplied.contribution())
                && expected.quote().equals(supplied.quote()) && expected.necessity() == supplied.necessity()
                && expected.condition().equals(supplied.condition()) && expected.alternativeGroup().equals(supplied.alternativeGroup());
    }
    private static Decision negative(String id, Outcome outcome) {
        return new Decision(id, outcome, "", "", null, "", "",
                outcome == Outcome.DESCEND ? "Inspect the existing catalogue path to the required counterpart."
                        : "This fixture does not request that oriented functional relationship.", "");
    }
    private static String signature(JsonNode claim) {
        return claim.path("sourceId").asString() + ":" + claim.path("type").asString() + ":" + claim.path("targetId").asString();
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalArgumentException(message); } }
    record Reply(String id, String content) { }
}
