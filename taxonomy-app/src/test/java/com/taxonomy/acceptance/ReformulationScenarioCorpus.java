package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Protocol-only provider choices for a fixed application path; the sourced requirement is unchanged. */
final class ReformulationScenarioCorpus {
    static JsonNode scenario(String name) throws Exception {
        if (name.equals("flood")) return flood();
        if (!Set.of("time-recording", "cross-taxonomy").contains(name)) throw new IllegalArgumentException("Unknown authored scenario");
        JsonNode authored;
        try (var stream = ReformulationScenarioCorpus.class.getResourceAsStream("/scenarios/reformulation-" + name + ".json")) {
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
        JsonNode scoring;
        try (var stream = ReformulationScenarioCorpus.class.getResourceAsStream("/scenarios/reformulation-bounded-scoring.json")) {
            scoring = new tools.jackson.databind.ObjectMapper().readTree(stream);
        }
        var fixture = (ObjectNode) ScenarioLlmPlayback.flood().fixture();
        fixture.put("id", scoring.path("id").asText());
        fixture.put("scenarioReformulation", true);
        fixture.put("responseProvenance", scoring.path("responseProvenance").asText());
        fixture.put("purpose", "PROTOCOL_ONLY");
        fixture.remove("classificationReference");
        fixture.remove("expectedLeafScores");
        fixture.remove("expectedLeafPaths");
        fixture.putArray("expectedLeaves").add("BP-1017").add("IP-1116");
        // The reformulation provider implements exactly eight ancestor sections. Its protocol
        // inputs must stay fixed when the realistic Flood classification gains semantic branches.
        fixture.set("replies", scoring.path("replies").deepCopy());
        fixture.set("relationPlayback", scoring.path("relationPlayback").deepCopy());
        return fixture;
    }
}
