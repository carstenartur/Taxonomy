package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.NodeSynthesisInput;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ReformulationPromptBuilder {
    public static final String PROMPT_VERSION="reformulation-node-v1";
    public static final String SCHEMA_VERSION="reformulation-response-v1";
    public static final String INTERACTIVE_PROMPT_VERSION="reformulation-node-v2";
    public static final String INTERACTIVE_SCHEMA_VERSION="reformulation-response-v2";
    private final ObjectMapper json;
    public ReformulationPromptBuilder(ObjectMapper json) { this.json=json; }
    public static String template() {return readTemplate("/prompts/reformulation-node.txt");}
    public static String interactiveTemplate() {return readTemplate("/prompts/reformulation-node-v2.txt");}
    private static String readTemplate(String path) {
        try(var stream=ReformulationPromptBuilder.class.getResourceAsStream(path)) {
            if(stream==null) throw new IllegalStateException("Missing reformulation prompt");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException failure) { throw new IllegalStateException("Cannot read reformulation prompt",failure); }
    }
    public String build(NodeSynthesisInput input,String errors) {
        String frozen=input.baseline().frozenContext().getOrDefault("reformulationPrompt",template());
        var data=(tools.jackson.databind.node.ObjectNode)json.valueToTree(input);
        var baseline=(tools.jackson.databind.node.ObjectNode)data.path("baseline");
        baseline.remove("snapshotPayload");
        var context=(tools.jackson.databind.node.ObjectNode)baseline.path("frozenContext");
        // Full archives remain persisted. Calls use selected node/terminal/child/boundary inputs,
        // not repeated entire snapshots, unrelated branches or current workspace provenance.
        context.retain("project","sourceVersion","reformulationPromptVersion","reformulationSchemaVersion");
        if (input.preservationContract().contains("\nBOUNDED_AGGREGATE_V1:")) {
            // Only repeated archive descriptions are projected, never original text,
            // questions, options, conditions, rationales, source spans or relation IDs.
            // The input and cached/published domain objects retain their full contexts.
            compactDiscoveryContexts(data.path("openDecisions"));
            data.path("children").forEach(child -> compactDiscoveryContexts(child.path("questionProposals")));
        }
        return frozen+"\nINPUT_DATA_JSON\n"+json.writeValueAsString(data)
            +(errors==null?"":"\nVALIDATION_ERRORS (repair the same input once): "+json.writeValueAsString(errors));
    }
    private static void compactDiscoveryContexts(tools.jackson.databind.JsonNode questions) {
        questions.forEach(question -> {
            compactDiscoveries(question.path("discoveries"));
            question.path("origins").forEach(origin -> compactDiscoveries(origin.path("discoveries")));
        });
    }
    private static void compactDiscoveries(tools.jackson.databind.JsonNode discoveries) {
        discoveries.forEach(discovery -> {
            if (discovery instanceof tools.jackson.databind.node.ObjectNode object && discovery.path("context").asText().length() > 512)
                object.put("context", "Full node context retained in the stored group discovery at " + discovery.path("location").asText());
        });
    }
}
