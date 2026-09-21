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
        return frozen+"\nINPUT_DATA_JSON\n"+json.writeValueAsString(data)
            +(errors==null?"":"\nVALIDATION_ERRORS (repair the same input once): "+json.writeValueAsString(errors));
    }
}
