package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Authored provider choices for a bounded application path; the sourced requirement is unchanged. */
final class ReformulationCivilianCorpus {
    static JsonNode scenario(String name) throws Exception {
        if (name.equals("flood")) return flood();
        if (!Set.of("time-recording", "cross-taxonomy").contains(name)) throw new IllegalArgumentException("Unknown authored civilian scenario");
        JsonNode authored;
        try (var stream = ReformulationCivilianCorpus.class.getResourceAsStream("/scenarios/reformulation-" + name + ".json")) {
            authored = new tools.jackson.databind.ObjectMapper().readTree(stream);
        }
        var fixture = (ObjectNode) flood();
        String source = authored.at("/requirement/text").asText();
        fixture.put("id", authored.path("id").asText()).put("responseProvenance", authored.path("responseProvenance").asText());
        fixture.putArray("sources"); fixture.set("application", authored.path("application"));
        ((ObjectNode) fixture.path("requirement")).put("key", "AUTHORED-" + name).put("title", "Authored " + name).put("text", source);
        for (var contribution : fixture.at("/relationPlayback/sourceContributions")) {
            ((ObjectNode) contribution).put("quote", source).put("condition", "Authored catalogue-front assumption; not a quality judgment.")
                    .put("text", contribution.path("nodeId").asText().equals("BP-1017")
                            ? "Acquire the source data described in this authored requirement." : "Represent the source data described in this authored requirement.");
        }
        fixture.at("/relationPlayback/claims").forEach(claim -> ((ObjectNode) claim).put("quote", source));
        return fixture;
    }
    static JsonNode flood() throws Exception {
        var fixture = (ObjectNode) ScenarioLlmPlayback.flood().fixture();
        fixture.put("id", "civilian-flood-reformulation-bounded-v1");
        fixture.put("civilianReformulation", true);
        fixture.put("responseProvenance", "Authored acceptance assumptions: only BP and IP are scored relevant. "
                + "This is not a completeness judgment about the source or live-model quality evidence.");
        var selected = Set.of("BP", "IP");
        var rules = fixture.putArray("replies");
        for (var rule : ScenarioLlmPlayback.flood().fixture().path("replies")) {
            String id = rule.path("id").asText();
            if (selected.stream().anyMatch(root -> id.startsWith(root)) || !id.contains("-")) {
                var copy = (ObjectNode) rule.deepCopy();
                if (!id.contains("-") && !selected.contains(id)) {
                    ((ObjectNode) copy.path("answers").path(id)).put("score", 0)
                            .put("reason", "Authored bounded acceptance choice; original constraints remain binding.");
                }
                rules.add(copy);
            }
        }
        var corpus = (ObjectNode) fixture.path("relationPlayback");
        var contributions = corpus.path("sourceContributions").deepCopy();
        var kept = corpus.putArray("sourceContributions");
        contributions.forEach(value -> { if (Set.of("BP-1017", "IP-1116").contains(value.path("nodeId").asText())) kept.add(value); });
        var claims = corpus.path("claims").deepCopy();
        var retained = corpus.putArray("claims");
        claims.forEach(value -> { if (value.path("sourceId").asText().equals("BP-1017")
                && value.path("targetId").asText().equals("IP-1116")) retained.add(value); });
        return fixture;
    }
}
