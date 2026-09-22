package com.taxonomy.analysis.reformulation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Lossless, request-local encoding. Never changes domain objects or removes unique context. */
final class DiscoveryContextTable {
    private DiscoveryContextTable() {}

    static void encode(ObjectMapper json, ObjectNode data, List<JsonNode> questionLists) {
        var discoveries = new ArrayList<ObjectNode>();
        questionLists.forEach(questions -> questions.forEach(question -> {
            collect(question.path("discoveries"), discoveries);
            question.path("origins").forEach(origin -> collect(origin.path("discoveries"), discoveries));
        }));
        var counts = new HashMap<String, Integer>();
        discoveries.forEach(d -> counts.merge(d.path("context").asText(), 1, Integer::sum));
        var table = json.createObjectNode();
        var references = new LinkedHashMap<String, String>();
        for (var discovery : discoveries) {
            String context = discovery.path("context").asText();
            // Short strings cost less inline than a reference plus table entry.
            if (context.length() <= 128 || counts.get(context) < 2) continue;
            String key = references.computeIfAbsent(context, ignored -> "context-" + (references.size() + 1));
            table.put(key, context);
            discovery.remove("context");
            discovery.put("contextRef", key);
        }
        if (!table.isEmpty()) data.set("discoveryContextTable", table);
    }

    private static void collect(JsonNode discoveries, List<ObjectNode> target) {
        discoveries.forEach(discovery -> {
            if (discovery instanceof ObjectNode object && discovery.path("context").isString()) target.add(object);
        });
    }
}
