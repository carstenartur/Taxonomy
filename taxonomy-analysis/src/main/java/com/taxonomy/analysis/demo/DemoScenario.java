package com.taxonomy.analysis.demo;

import com.taxonomy.identity.StableIdentityHash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Versioned product-demo scenario: the scenario definition (a) and the
 * pre-produced provider replies (b). The independent reference annotation (c)
 * is deliberately not part of this type; see {@code evaluation.DemoReference}.
 */
public record DemoScenario(String id, int version, String fingerprint, Map<String, String> title,
                           Map<String, String> banner, String catalogueSource, Map<String, String> catalogueAnchors,
                           Requirement requirement, String r0Text, List<String> r1RequiredPhrases,
                           List<DemoDecision> demoDecisions, List<DemoReview> demoReviews,
                           List<Station> stations, Map<String, Iteration> iterations) {

    public static final List<String> ROOTS = List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
    static final String SCENARIO_SCHEMA = "taxonomy-product-demo-scenario-v1";
    static final String REPLIES_SCHEMA = "taxonomy-product-demo-replies-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Requirement(String key, String title, String language, String text) { }
    public record DemoDecision(String question, List<String> values, String rationale) { }
    public record DemoReview(String wording, String action, String rationale) { }
    public record Station(String id, String de, String en) { }
    public record Score(int score, String reason) { }
    public record Relation(String source, String type, List<String> path, String contribution) {
        public String target() { return path.getLast(); }
    }
    public record Addition(String anchorRoot, String provenance, String wording) { }
    public record Question(String id, String anchorRoot, String subject, String dimension, String wording,
                           String rationale, String kind, Map<String, String> consequences, String consequenceSummary) {
        public List<String> options() { return List.copyOf(consequences.keySet()); }
    }
    public record Reformulation(String defaultSummary, Map<String, String> summaries,
                                List<Addition> additions, List<Question> questions) { }
    public record Iteration(String name, String zeroReason, Map<String, Score> roots,
                            Map<String, Map<String, Score>> expansions, Map<String, String> justifications,
                            List<Relation> relations, Reformulation reformulation) {
        /** Authored score of a node, or {@code null} when the scenario does not score it positively. */
        public Integer authoredScore(String code) {
            Score root = roots.get(code);
            if (root != null) return root.score();
            for (var children : expansions.values()) {
                Score child = children.get(code);
                if (child != null) return child.score();
            }
            return null;
        }

        /** All codes the iteration scores (roots and declared children). */
        public Map<String, Integer> declaredScores() {
            var scores = new LinkedHashMap<String, Integer>();
            roots.forEach((code, score) -> scores.put(code, score.score()));
            expansions.values().forEach(children -> children.forEach((code, score) -> scores.put(code, score.score())));
            return Collections.unmodifiableMap(scores);
        }
    }

    public Iteration iteration(String name) {
        Iteration iteration = iterations.get(name);
        if (iteration == null) throw new IllegalArgumentException("Unknown demo iteration " + name + " for " + id);
        return iteration;
    }

    public Question question(String questionId) {
        return iterations.values().stream().flatMap(i -> i.reformulation().questions().stream())
                .filter(q -> q.id().equals(questionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo question " + questionId));
    }

    public int maxIteration() {
        return iterations.keySet().stream().mapToInt(k -> Integer.parseInt(k.substring(1))).max().orElse(0);
    }

    /** Loads (a) and (b) only. A missing reference annotation never affects replay. */
    public static DemoScenario load(String id, Function<String, InputStream> resources) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid demo scenario ID");
        byte[] scenarioBytes = read(resources, "demo-scenarios/" + id + "/scenario.json");
        byte[] replyBytes = read(resources, "demo-scenarios/" + id + "/replies.json");
        JsonNode scenario = JSON.readTree(scenarioBytes);
        JsonNode replies = JSON.readTree(replyBytes);
        require(SCENARIO_SCHEMA.equals(scenario.path("schema").asString()), "Unsupported scenario schema");
        require(REPLIES_SCHEMA.equals(replies.path("schema").asString()), "Unsupported replies schema");
        require(id.equals(scenario.path("id").asString()), "Scenario ID mismatch");
        require(id.equals(replies.path("scenario").asString()), "Reply book belongs to another scenario");
        int version = scenario.path("version").asInt(0);
        require(version > 0 && version == replies.path("version").asInt(-1), "Scenario and reply versions must match");

        var requirementNode = scenario.path("requirement");
        var requirement = new Requirement(text(requirementNode, "key"), text(requirementNode, "title"),
                text(requirementNode, "language"), text(requirementNode, "text"));
        require(Set.of("de", "en").contains(requirement.language()), "Scenario language must be de or en");
        String r0 = text(scenario.path("iterations").path("R0"), "exactText");
        require(r0.equals(requirement.text()), "R0 must equal the scenario requirement");
        var phrases = strings(scenario.path("iterations").path("R1"), "requiredPhrases");

        var iterations = new LinkedHashMap<String, Iteration>();
        for (var entry : replies.path("iterations").properties()) {
            iterations.put(entry.getKey(), iteration(entry.getKey(), entry.getValue()));
        }
        var declaredIterations = new LinkedHashSet<String>();
        scenario.path("iterations").properties().forEach(e -> declaredIterations.add(e.getKey()));
        require(declaredIterations.equals(iterations.keySet()) && iterations.keySet().equals(Set.of("R0", "R1")),
                "Scenario and reply book must define exactly R0 and R1");

        var decisions = new ArrayList<DemoDecision>();
        for (var node : scenario.path("demoDecisions")) {
            decisions.add(new DemoDecision(text(node, "question"), strings(node, "values"), text(node, "rationale")));
        }
        var reviews = new ArrayList<DemoReview>();
        for (var node : scenario.path("demoReviews")) {
            reviews.add(new DemoReview(text(node, "wording"), text(node, "action"), text(node, "rationale")));
        }
        var stations = new ArrayList<Station>();
        for (var node : scenario.path("stations")) {
            stations.add(new Station(text(node, "id"), text(node, "de"), text(node, "en")));
        }
        var anchors = new LinkedHashMap<String, String>();
        scenario.path("catalogue").path("anchors").properties().forEach(e -> anchors.put(e.getKey(), e.getValue().asString()));
        String fingerprint = StableIdentityHash.sha256(new String(scenarioBytes, StandardCharsets.UTF_8)
                + "\n--replies--\n" + new String(replyBytes, StandardCharsets.UTF_8));
        var result = new DemoScenario(id, version, fingerprint, localized(scenario.path("title")),
                localized(scenario.path("banner")), text(scenario.path("catalogue"), "source"), Map.copyOf(anchors),
                requirement, r0, phrases, List.copyOf(decisions), List.copyOf(reviews), List.copyOf(stations),
                Collections.unmodifiableMap(iterations));
        result.validateStructure();
        return result;
    }

    private void validateStructure() {
        for (var iteration : iterations.values()) {
            require(iteration.roots().keySet().equals(Set.copyOf(ROOTS)),
                    iteration.name() + ": every taxonomy root needs an authored root reply");
            for (var parent : iteration.expansions().entrySet()) {
                Integer parentScore = iteration.authoredScore(parent.getKey());
                require(parentScore != null && parentScore > 0,
                        iteration.name() + ": expansion of unscored parent " + parent.getKey());
                int sum = parent.getValue().values().stream().mapToInt(Score::score).sum();
                require(sum == parentScore, iteration.name() + ": children of " + parent.getKey()
                        + " must sum to its authored score " + parentScore + " but sum to " + sum);
            }
            for (var relation : iteration.relations()) {
                require(relation.path().size() >= 2 && ROOTS.contains(relation.path().getFirst()),
                        iteration.name() + ": relation path must start at a taxonomy root");
                Integer sourceScore = iteration.authoredScore(relation.source());
                require(sourceScore != null && sourceScore > 0, iteration.name() + ": relation source must be scored");
            }
            var questionIds = new LinkedHashSet<String>();
            var subjects = new LinkedHashSet<String>();
            for (var question : iteration.reformulation().questions()) {
                require(questionIds.add(question.id()) && subjects.add(question.subject()), "Duplicate demo question " + question.id());
                require(ROOTS.contains(question.anchorRoot()), "Question anchor must be a taxonomy root");
                require(question.consequences().size() >= 2, "Choice question " + question.id() + " needs two options");
                require(Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE").contains(question.kind()), "Unsupported question kind");
            }
            for (var addition : iteration.reformulation().additions()) {
                require(ROOTS.contains(addition.anchorRoot()), "Addition anchor must be a taxonomy root");
                require(Set.of("MODEL_ADDITION", "CATALOGUE_INSPIRATION", "ARCHITECTURE_HYPOTHESIS").contains(addition.provenance()),
                        "Replay additions cannot claim original, human or adopted provenance");
            }
        }
        var consequences = new ArrayList<String>();
        for (var decision : demoDecisions) {
            var question = question(decision.question());
            require(question.consequences().keySet().containsAll(decision.values()) && !decision.values().isEmpty(),
                    "Demo decision " + decision.question() + " uses an unknown option");
            decision.values().forEach(value -> consequences.add(question.consequences().get(value)));
        }
        require(Set.copyOf(consequences).equals(Set.copyOf(r1RequiredPhrases)),
                "R1 required phrases must be exactly the consequences of the visible demo decisions");
        var additions = iterations.values().stream().flatMap(i -> i.reformulation().additions().stream())
                .map(Addition::wording).toList();
        for (var review : demoReviews) {
            require("REJECT".equals(review.action()) && additions.contains(review.wording()),
                    "Demo review must reject an authored addition");
        }
        require(maxIteration() == 1, "This scenario supports exactly one adopted iteration");
    }

    private static Iteration iteration(String name, JsonNode node) {
        require(name.matches("R[0-9]{1,2}"), "Invalid iteration name " + name);
        var roots = new LinkedHashMap<String, Score>();
        node.path("roots").properties().forEach(e -> roots.put(e.getKey(), score(e.getValue())));
        var expansions = new LinkedHashMap<String, Map<String, Score>>();
        for (var parent : node.path("expansions").properties()) {
            var children = new LinkedHashMap<String, Score>();
            parent.getValue().properties().forEach(e -> children.put(e.getKey(), score(e.getValue())));
            require(!children.isEmpty(), name + ": empty expansion " + parent.getKey());
            expansions.put(parent.getKey(), Collections.unmodifiableMap(children));
        }
        var justifications = new LinkedHashMap<String, String>();
        node.path("justifications").properties().forEach(e -> justifications.put(e.getKey(), nonBlank(e.getValue().asString(), "justification")));
        var relations = new ArrayList<Relation>();
        for (var relation : node.path("relations")) {
            relations.add(new Relation(text(relation, "source"), text(relation, "type"), strings(relation, "path"), text(relation, "contribution")));
        }
        var reformulation = node.path("reformulation");
        var summaries = new LinkedHashMap<String, String>();
        reformulation.path("summaries").properties().forEach(e -> summaries.put(e.getKey(), nonBlank(e.getValue().asString(), "summary")));
        var additions = new ArrayList<Addition>();
        for (var addition : reformulation.path("additions")) {
            additions.add(new Addition(text(addition, "anchorRoot"), text(addition, "provenance"), text(addition, "wording")));
        }
        var questions = new ArrayList<Question>();
        for (var question : reformulation.path("questions")) {
            var consequences = new LinkedHashMap<String, String>();
            question.path("consequences").properties().forEach(e -> consequences.put(nonBlank(e.getKey(), "option"), nonBlank(e.getValue().asString(), "consequence")));
            questions.add(new Question(text(question, "id"), text(question, "anchorRoot"), text(question, "subject"),
                    text(question, "dimension"), text(question, "wording"), text(question, "rationale"), text(question, "kind"),
                    Collections.unmodifiableMap(consequences), text(question, "consequenceSummary")));
        }
        return new Iteration(name, text(node, "zeroReason"), Collections.unmodifiableMap(roots),
                Collections.unmodifiableMap(expansions), Collections.unmodifiableMap(justifications), List.copyOf(relations),
                new Reformulation(text(reformulation, "defaultSummary"), Collections.unmodifiableMap(summaries),
                        List.copyOf(additions), List.copyOf(questions)));
    }

    private static Score score(JsonNode node) {
        require(node.path("score").isIntegralNumber(), "Score must be an integer");
        int value = node.path("score").asInt();
        require(value >= 0 && value <= 100, "Score must be within 0..100");
        return new Score(value, text(node, "reason"));
    }

    private static Map<String, String> localized(JsonNode node) {
        return Map.of("de", text(node, "de"), "en", text(node, "en"));
    }

    private static byte[] read(Function<String, InputStream> resources, String path) {
        try (InputStream stream = resources.apply(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing demo scenario resource " + path);
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read demo scenario resource " + path, failure);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        require(value != null && value.isString() && !value.asString().isBlank(), "Missing scenario text: " + field);
        return value.asString();
    }

    private static String nonBlank(String value, String field) {
        require(value != null && !value.isBlank(), "Missing scenario text: " + field);
        return value;
    }

    static List<String> strings(JsonNode node, String field) {
        JsonNode array = node.path(field);
        require(array.isArray() && !array.isEmpty(), "Missing scenario list: " + field);
        var values = new ArrayList<String>();
        for (var item : array) {
            require(item.isString() && !item.asString().isBlank(), "Invalid scenario list entry: " + field);
            values.add(item.asString());
        }
        require(new LinkedHashSet<>(values).size() == values.size(), "Duplicate scenario list entry: " + field);
        return List.copyOf(values);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("Invalid demo scenario: " + message);
    }
}
