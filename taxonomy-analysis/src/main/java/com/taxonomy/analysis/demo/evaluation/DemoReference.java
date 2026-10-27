package com.taxonomy.analysis.demo.evaluation;

import com.taxonomy.dto.RootAssessment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Independent reference annotation (c) of a product-demo scenario.
 *
 * <p>It is deliberately kept outside {@code DemoScenario}: prompt construction
 * and replay selection have no access to it. It is read only after analyses for
 * the separate comparison.</p>
 */
public record DemoReference(String scenario, int version, List<Aspect> requiredAspects, List<Forbidden> forbiddenPhrases,
                            List<ExpectedNode> expectedNodes, List<ForbiddenNode> forbiddenNodes,
                            List<ExpectedRelation> expectedRelations, List<ExpectedRoot> expectedRootAssessments) {
    static final String SCHEMA = "taxonomy-product-demo-reference-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Aspect(String id, Map<String, String> label, List<String> anyOf) { }
    public record Forbidden(String id, Map<String, String> label, String phrase) { }
    public record ExpectedNode(String code, int minimumScore, List<String> iterations) { }
    public record ExpectedRoot(String code, RootAssessment.Status status, List<String> iterations) { }
    public record ForbiddenNode(String code, Map<String, String> reason) { }
    public record ExpectedRelation(String source, String type, String target, List<String> iterations) {
        public String key() { return source + " " + type + " " + target; }
    }

    public static DemoReference load(String scenarioId) {
        return load(scenarioId, path -> DemoReference.class.getClassLoader().getResourceAsStream(path));
    }

    public static DemoReference load(String scenarioId, Function<String, InputStream> resources) {
        if (scenarioId == null || !scenarioId.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid demo scenario ID");
        JsonNode root;
        try (InputStream stream = resources.apply("demo-scenarios/" + scenarioId + "/reference.json")) {
            if (stream == null) throw new IllegalStateException("Missing reference annotation for " + scenarioId);
            root = JSON.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read reference annotation", failure);
        }
        if (!SCHEMA.equals(root.path("schema").asString()) || !scenarioId.equals(root.path("scenario").asString()))
            throw new IllegalStateException("Reference annotation does not belong to " + scenarioId);
        var aspects = new ArrayList<Aspect>();
        for (JsonNode node : root.path("requiredAspects"))
            aspects.add(new Aspect(node.path("id").asString(), labels(node.path("label")), strings(node.path("anyOf"))));
        var forbidden = new ArrayList<Forbidden>();
        for (JsonNode node : root.path("forbiddenPhrases"))
            forbidden.add(new Forbidden(node.path("id").asString(), labels(node.path("label")), node.path("phrase").asString()));
        var nodes = new ArrayList<ExpectedNode>();
        for (JsonNode node : root.path("expectedNodes"))
            nodes.add(new ExpectedNode(node.path("code").asString(), node.path("minimumScore").asInt(), strings(node.path("iterations"))));
        var forbiddenNodes = new ArrayList<ForbiddenNode>();
        for (JsonNode node : root.path("forbiddenNodes"))
            forbiddenNodes.add(new ForbiddenNode(node.path("code").asString(), labels(node.path("reason"))));
        var relations = new ArrayList<ExpectedRelation>();
        for (JsonNode node : root.path("expectedRelations"))
            relations.add(new ExpectedRelation(node.path("source").asString(), node.path("type").asString(),
                    node.path("target").asString(), strings(node.path("iterations"))));
        var rootAssessments = new ArrayList<ExpectedRoot>();
        for (JsonNode node : root.path("expectedRootAssessments"))
            rootAssessments.add(new ExpectedRoot(node.path("code").asString(),
                    RootAssessment.Status.valueOf(node.path("status").asString()), strings(node.path("iterations"))));
        if (aspects.isEmpty() || nodes.isEmpty()) throw new IllegalStateException("Reference annotation is empty");
        return new DemoReference(scenarioId, root.path("version").asInt(), List.copyOf(aspects), List.copyOf(forbidden),
                List.copyOf(nodes), List.copyOf(forbiddenNodes), List.copyOf(relations), List.copyOf(rootAssessments));
    }

    private static Map<String, String> labels(JsonNode node) {
        var labels = new LinkedHashMap<String, String>();
        node.properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
        return Map.copyOf(labels);
    }

    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }
}
