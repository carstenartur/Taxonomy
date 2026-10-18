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
    private final Map<String, List<JsonNode>> contributionsByNode = new LinkedHashMap<>();
    private final Map<String, String> sourceRejections = new LinkedHashMap<>();
    private final Map<String, String> specificRejections = new LinkedHashMap<>();
    private final Map<String, Integer> extracted = new LinkedHashMap<>();
    private final boolean strictSources;
    private final ScenarioRelationExclusions exclusions;
    private final Map<String, List<String>> paths = new LinkedHashMap<>();
    private final List<JsonNode> claims = new ArrayList<>();
    private final Map<String, Integer> verified = new LinkedHashMap<>();

    ScenarioRelationPlayback(JsonNode fixture) {
        original = fixture.at("/requirement/text").asString();
        fixture.path("bindings").propertyNames().forEach(offeredIds::add);
        fixture.path("replies").forEach(rule -> rule.path("keys").forEach(key -> offeredIds.add(key.asString())));
        JsonNode corpus = fixture.path("relationPlayback");
        strictSources = "EXPLICIT_ALL_POSITIVE".equals(corpus.path("sourceReplyPolicy").asString());
        for (JsonNode value : corpus.path("sourceContributions")) {
            String id = value.path("nodeId").asString();
            require(offeredIds.contains(id), "Unknown contribution source: " + id);
            require(!value.path("text").asString().isBlank(), "Missing scoped contribution");
            require(original.contains(value.path("quote").asString()) && !value.path("quote").asString().isBlank(), "Fabricated contribution quote");
            require(contributions.putIfAbsent(contributionKey(id, value.path("contributionId").asString("default")), value.deepCopy()) == null,
                    "Duplicate source contribution");
            contributionsByNode.computeIfAbsent(id, key -> new ArrayList<>()).add(value.deepCopy());
            require(contributionsByNode.get(id).size() <= 4, "The real source protocol permits at most four distinct contributions");
        }
        for (var rejection : corpus.path("sourceRejections")) {
            String id = rejection.path("nodeId").asString();
            String rationale = rejection.path("rationale").asString();
            require(offeredIds.contains(id) && !contributionsByNode.containsKey(id) && !rationale.isBlank(), "Invalid source rejection");
            require(sourceRejections.putIfAbsent(id, rationale) == null, "Duplicate source rejection");
        }
        if (strictSources) {
            for (var rule : fixture.path("replies")) for (var answer : rule.path("answers").properties()) {
                String code = answer.getKey();
                if (code.contains("-") && answer.getValue().path("score").asDouble() > 0) {
                    require(contributionsByNode.containsKey(code) || sourceRejections.containsKey(code),
                            "Missing positive source assessment: " + code);
                }
            }
        }
        for (var rejection : corpus.path("specificRejections")) {
            require(!rejection.path("rationale").asString().isBlank(), "Missing oriented rejection reason");
            require(specificRejections.putIfAbsent(signature(rejection), rejection.path("rationale").asString()) == null,
                    "Duplicate oriented rejection");
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
            require(contributions.containsKey(claimContributionKey(claim, "source")) && contributions.containsKey(claimContributionKey(claim, "target"))
                    && paths.containsKey(source) && paths.containsKey(target) && !source.equals(target), "Invalid fixture endpoints");
            String quote = claim.path("quote").asString();
            require(!quote.isBlank() && original.contains(quote), "Fabricated relationship quote");
            require(signatures.add(signature(claim)), "Duplicate fixture claim");
            require(!strictSources || (!claim.path("rationale").asString().isBlank()
                    && !claim.path("verificationRationale").asString().isBlank()), "Missing relationship reasoning");
            claims.add(claim.deepCopy());
        }
        require(!contributions.isEmpty() && !claims.isEmpty(), "Missing relation response corpus");
        exclusions = strictSources ? new ScenarioRelationExclusions(fixture) : null;
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
                List<JsonNode> scoped = contributionsByNode.getOrDefault(id, List.of());
                require(!strictSources || !scoped.isEmpty() || sourceRejections.containsKey(id),
                        "Missing source response: " + id);
                extracted.merge(id, 1, Integer::sum);
                selections.add(Map.of("nodeId", id, "outcome", scoped.isEmpty() ? "REJECT" : "EXPLICIT",
                        "contributions", scoped.stream().map(contribution -> Map.of(
                                "text", contribution.path("text").asString(), "requirementQuote", contribution.path("quote").asString(),
                                "condition", contribution.path("condition").asString())).toList(),
                        "rationale", scoped.isEmpty() ? sourceRejections.getOrDefault(id,
                                "Bounded protocol-only fixture: this source has no additional contribution in the fixed workflow.")
                                : String.join(" ", scoped.stream().map(part -> part.path("rationale").asString(
                                        "The original quote requests this bounded functional contribution.")).distinct().toList()),
                        "question", ""));
            }
            return new Reply("relation:EXTRACT:" + String.join(",", ids), JSON.writeValueAsString(Map.of("selections", selections)));
        }
        Query query = JSON.treeToValue(input, Query.class);
        Contribution contribution = query.contribution();
        JsonNode known = knownContribution(contribution);
        require(known != null, "Unknown source contribution");
        List<Decision> decisions = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (Node node : query.candidates()) {
            require(offeredIds.contains(node.id()) && ids.add(node.id()), "Unknown or repeated target: " + node.id());
            decisions.add(decision(query, node));
        }
        List<Object> response = new ArrayList<>();
        for (var decision : decisions) {
            if (query.phase() == Phase.VERIFY) {
                response.add(Map.of("targetId", decision.targetId(), "outcome", decision.outcome(),
                        "rationale", decision.rationale(), "question", decision.question()));
            } else {
                var value = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(decision);
                value.set("requirementQuote", value.remove("quote"));
                response.add(value);
            }
        }
        return new Reply("relation:" + query.phase() + ":" + contribution.source().id() + ":" + query.type()
                + ":" + query.direction() + ":" + known.path("contributionId").asString("default")
                + ":" + String.join(",", ids), JSON.writeValueAsString(Map.of("decisions", response)));
    }

    void verifyCoverage(int repetitions) {
        if (strictSources) {
            Set<String> required = new TreeSet<>(contributionsByNode.keySet()); required.addAll(sourceRejections.keySet());
            for (String code : required) if (extracted.getOrDefault(code, 0) != repetitions) {
                throw new AssertionError(code + ": expected " + repetitions + " explicit source assessments, got " + extracted.getOrDefault(code, 0));
            }
        }
        for (JsonNode claim : claims) {
            int count = verified.getOrDefault(signature(claim), 0);
            // Each oriented claim is checked once from each of its two independently
            // extracted endpoints. The two Copilot passes must repeat both checks.
            if (count != repetitions * 2) {
                throw new AssertionError(signature(claim) + ": expected " + (repetitions * 2) + " verified replies, got " + count);
            }
        }
        if (exclusions != null) exclusions.verifyCoverage(repetitions);
    }

    Map<String, Object> coverage() {
        var result = new LinkedHashMap<String, Object>();
        result.put("sourcePolicy", strictSources ? "EXPLICIT_ALL_POSITIVE" : "PROTOCOL_ONLY");
        result.put("sourceAssessments", Map.copyOf(extracted));
        result.put("authoredSourceContributions", contributions.size());
        result.put("authoredClaims", claims.size());
        result.put("verifiedClaims", Map.copyOf(verified));
        if (exclusions != null) result.put("negativeAssessments", exclusions.coverage());
        return result;
    }

    private Decision decision(Query query, Node node) {
        String descendantRationale = null;
        for (JsonNode claim : claims) {
            boolean outgoing = query.direction() == Direction.OUTGOING;
            String from = claim.path(outgoing ? "sourceId" : "targetId").asString();
            String counterpart = claim.path(outgoing ? "targetId" : "sourceId").asString();
            String currentKey = claimContributionKey(claim, outgoing ? "source" : "target");
            JsonNode current = knownContribution(query.contribution());
            if (!from.equals(query.contribution().source().id()) || !claim.path("type").asString().equals(query.type())
                    || current == null || !currentKey.equals(contributionKey(from, current.path("contributionId").asString("default")))) { continue; }
            if (counterpart.equals(node.id()) && !node.container()) {
                JsonNode targetContribution = contributions.get(claimContributionKey(claim, outgoing ? "target" : "source"));
                Decision expected = new Decision(node.id(), Outcome.MATCH, targetContribution.path("text").asString(),
                        claim.path("quote").asString(), Necessity.valueOf(claim.path("necessity").asString()),
                        claim.path("condition").asString(), claim.path("alternativeGroup").asString(),
                        claim.path("rationale").asString("The original quote connects these specific endpoint functions."), "");
                if (query.phase() == Phase.VERIFY) {
                    if (!sameClaim(expected, query.proposal())) {
                        return new Decision(node.id(), Outcome.REJECT, "", "", null, "", "",
                                "The supplied proposal changes the stored endpoint contribution, exact quote, necessity, condition or alternative group. It is not the authored claim being verified.", "");
                    }
                    verified.merge(signature(claim), 1, Integer::sum);
                    return new Decision(expected.targetId(), Outcome.VERIFIED, expected.contribution(), expected.quote(),
                            expected.necessity(), expected.condition(), expected.alternativeGroup(),
                            claim.path("verificationRationale").asString("The proposed type, direction, contribution and conditions match the quoted functional requirement."), "");
                }
                return expected;
            }
            if (descendantRationale == null && paths.get(counterpart).contains(node.id())) {
                descendantRationale = "Inspect the offered descendants for this bounded counterpart function: "
                        + claim.path("rationale").asString("The original quote connects these specific endpoint functions.");
            }
        }
        if (descendantRationale != null && query.phase() == Phase.NAVIGATE) {
            require(descendantRationale.length() <= 800, "Authored navigation reason exceeds the real protocol limit");
            return new Decision(node.id(), Outcome.DESCEND, "", "", null, "", "", descendantRationale, "");
        }
        return negative(query, node);
    }
    private static boolean sameClaim(Decision expected, Decision supplied) {
        return expected.targetId().equals(supplied.targetId()) && expected.contribution().equals(supplied.contribution())
                && expected.quote().equals(supplied.quote()) && expected.necessity() == supplied.necessity()
                && expected.condition().equals(supplied.condition()) && expected.alternativeGroup().equals(supplied.alternativeGroup());
    }
    private Decision negative(Query query, Node node) {
        if (exclusions != null) {
            var known = knownContribution(query.contribution());
            String partId = known.path("contributionId").asString("default");
            var answer = exclusions.navigation(query, node, partId);
            if (answer == null) answer = exclusions.answer(query, node, partId);
            return new Decision(node.id(), answer.outcome(), "", "", null, "", "", answer.rationale(), answer.question());
        }
        String source = query.direction() == Direction.OUTGOING ? query.contribution().source().id() : node.id();
        String target = query.direction() == Direction.OUTGOING ? node.id() : query.contribution().source().id();
        String rationale = specificRejections.get(source + ":" + query.type() + ":" + target);
        if (rationale == null) rationale = "Bounded protocol-only fixture: no additional relationship response is defined for this transport test.";
        return new Decision(node.id(), Outcome.REJECT, "", "", null, "", "", rationale, "");
    }
    private static String signature(JsonNode claim) {
        return claim.path("sourceId").asString() + ":" + claim.path("type").asString() + ":" + claim.path("targetId").asString();
    }
    private JsonNode knownContribution(Contribution contribution) {
        return contributionsByNode.getOrDefault(contribution.source().id(), List.of()).stream()
                .filter(part -> part.path("text").asString().equals(contribution.text())
                        && part.path("quote").asString().equals(contribution.quote())
                        && part.path("condition").asString().equals(contribution.condition()))
                .findFirst().orElse(null);
    }
    private static String contributionKey(String nodeId, String partId) { return nodeId + "@" + partId; }
    private static String claimContributionKey(JsonNode claim, String endpoint) {
        return contributionKey(claim.path(endpoint + "Id").asString(), claim.path(endpoint + "ContributionId").asString("default"));
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalArgumentException(message); } }
    record Reply(String id, String content) { }
}
