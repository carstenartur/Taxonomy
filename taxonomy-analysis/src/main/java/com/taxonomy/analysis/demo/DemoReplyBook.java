package com.taxonomy.analysis.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic replacement for exactly one external model reply.
 *
 * <p>The product assembles the real prompt; this class only selects the authored
 * reply for the bound scenario and iteration. It never reads the reference
 * annotation, never consults the network and refuses prompts it cannot answer
 * exactly. Identical prompts always yield byte-identical replies.</p>
 */
public final class DemoReplyBook {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String RELATION_PREFIX = "You assess requirement-scoped architectural relationships. Protocol: relation-downwalk-v1.\n";
    private static final String NODE_MARKER = "\nINPUT_DATA_JSON\n";
    private static final String RECONCILE_MARKER = "\nRECONCILIATION_DATA_JSON\n";
    private static final String ROOT_MARKER = "This is one root assessment";
    private static final String PRODUCT_MARKER = "Concrete Information Product candidates";
    private static final Pattern KEYS = Pattern.compile("using EXACTLY these keys: ([^\\n]+)");
    private static final Pattern PARENT_SCORE = Pattern.compile(
            "The scores must sum to (\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)(?=\\.(?:\\s|$)|\\s|$)");
    private static final Pattern LEAF = Pattern.compile("Node to explain: (\\S+)");

    private final DemoScenario scenario;
    private final DemoScenario.Iteration iteration;

    public DemoReplyBook(DemoScenario scenario, String iteration) {
        this.scenario = scenario;
        this.iteration = scenario.iteration(iteration);
    }

    public String scenarioId() { return scenario.id(); }
    public String iteration() { return iteration.name(); }

    public String reply(String prompt) {
        if (prompt == null || prompt.isBlank()) throw failure(DemoReplayException.Code.UNKNOWN_PROMPT, "empty prompt");
        if (prompt.startsWith(RELATION_PREFIX)) return relation(prompt);
        int reconcile = prompt.lastIndexOf(RECONCILE_MARKER);
        if (reconcile >= 0) return reconcile(payload(prompt, reconcile + RECONCILE_MARKER.length()));
        int node = prompt.lastIndexOf(NODE_MARKER);
        if (node >= 0) return node(payload(prompt, node + NODE_MARKER.length()));
        Matcher parent = PARENT_SCORE.matcher(prompt);
        Matcher leaf = LEAF.matcher(prompt);
        boolean parentPrompt = parent.find();
        boolean leafPrompt = leaf.find();
        if (!prompt.contains(ROOT_MARKER) && !prompt.contains(PRODUCT_MARKER) && !parentPrompt && !leafPrompt)
            throw failure(DemoReplayException.Code.UNKNOWN_PROMPT, "prompt kind is not part of the scenario");
        requireAnalysisIteration(prompt);
        if (prompt.contains(ROOT_MARKER)) return roots(keys(prompt));
        if (prompt.contains(PRODUCT_MARKER))
            throw failure(DemoReplayException.Code.MISSING_REPLY, "the scenario authors no concrete-product assessment for " + keys(prompt));
        if (parentPrompt) return children(keys(prompt), com.taxonomy.dto.AnalysisPercentages.require(new java.math.BigDecimal(parent.group(1))));
        return justification(leaf.group(1));
    }

    // ── Iteration identity ───────────────────────────────────────────────────

    private boolean isR0() { return "R0".equals(iteration.name()); }

    /** The bound iteration must match the analysed requirement text. */
    private void requireAnalysisIteration(String prompt) {
        if (isR0()) {
            if (!prompt.contains(scenario.r0Text()) || scenario.r1RequiredPhrases().stream().anyMatch(prompt::contains))
                throw failure(DemoReplayException.Code.ITERATION_MISMATCH, "prompt does not analyse the exact R0 text");
        } else if (!scenario.r1RequiredPhrases().stream().allMatch(prompt::contains)) {
            throw failure(DemoReplayException.Code.ITERATION_MISMATCH, iteration.name()
                    + " replies require the adopted demo decisions in the analysed text");
        }
    }

    private void requireReformulationIteration(String originalText) {
        boolean matches = isR0() ? scenario.r0Text().equals(originalText)
                : scenario.r1RequiredPhrases().stream().allMatch(originalText::contains);
        if (!matches) throw failure(DemoReplayException.Code.ITERATION_MISMATCH,
                "reformulation source does not belong to " + iteration.name());
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    private String roots(List<String> keys) {
        var reply = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            var assessment = iteration.roots().get(key);
            if (assessment == null) throw failure(DemoReplayException.Code.MISSING_REPLY, "no root reply for " + key);
            var decision = new LinkedHashMap<String, Object>();
            decision.put("status", assessment.status().name());
            decision.put("score", assessment.score());
            decision.put("reason", assessment.reason());
            decision.put("question", assessment.question());
            reply.put(key, decision);
        }
        return JSON.writeValueAsString(reply);
    }

    private String children(List<String> keys, double parentScore) {
        String parent = null;
        Map<String, DemoScenario.Score> declared = null;
        for (var expansion : iteration.expansions().entrySet()) {
            if (expansion.getValue().keySet().stream().anyMatch(keys::contains)) {
                parent = expansion.getKey();
                declared = expansion.getValue();
                break;
            }
        }
        if (declared == null)
            throw failure(DemoReplayException.Code.MISSING_REPLY, "no authored expansion for candidates " + keys);
        if (!keys.containsAll(declared.keySet()))
            throw failure(DemoReplayException.Code.MISSING_REPLY, "candidate set of " + parent + " differs from the scenario catalogue");
        var scaled = scale(declared, keys, parentScore);
        var reply = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            var score = declared.get(key);
            reply.put(key, Map.of("score", scaled.getOrDefault(key, 0d),
                    "reason", score == null ? iteration.zeroReason() : score.reason()));
        }
        return JSON.writeValueAsString(reply);
    }

    /** Decimal-preserving scaling so authored proportions meet the product's exact parent budget. */
    static Map<String, Double> scale(Map<String, DemoScenario.Score> declared, List<String> keys, double target) {
        var weights = new LinkedHashMap<String, Double>();
        for (String key : keys) {
            var score = declared.get(key);
            weights.put(key, score == null ? 0d : score.score());
        }
        return com.taxonomy.dto.AnalysisPercentages.normalize(weights, target);
    }

    private String justification(String code) {
        String text = iteration.justifications().get(code);
        if (text == null) throw failure(DemoReplayException.Code.MISSING_REPLY, "no authored justification for " + code);
        return text;
    }

    // ── Relations (relation-downwalk-v1) ────────────────────────────────────

    private String relation(String prompt) {
        int input = prompt.indexOf("INPUT\n");
        if (input < 0) throw failure(DemoReplayException.Code.UNKNOWN_PROMPT, "relation prompt without input");
        JsonNode query = JSON.readTree(prompt.substring(input + 6));
        String original = query.path("original").asString();
        requireAnalysisIteration(original);
        if (query.has("nodes")) {
            var selections = new ArrayList<Map<String, Object>>();
            for (JsonNode node : query.path("nodes")) {
                String id = node.path("id").asString();
                var own = iteration.relations().stream().filter(r -> r.source().equals(id)).toList();
                var contributions = new ArrayList<Map<String, Object>>();
                new LinkedHashSet<>(own.stream().map(DemoScenario.Relation::contribution).toList())
                        .forEach(text -> contributions.add(Map.of("text", text, "quote", original, "condition", "")));
                var selection = new LinkedHashMap<String, Object>();
                selection.put("nodeId", id);
                selection.put("outcome", contributions.isEmpty() ? "REJECT" : "EXPLICIT");
                selection.put("contributions", contributions.stream().limit(4).toList());
                selection.put("rationale", contributions.isEmpty()
                        ? "Szenario-Antwort: keine Beziehung aus diesem Element vorgesehen."
                        : "Szenario-Antwort: Beziehungsquelle laut Szenario.");
                selection.put("question", "");
                selections.add(selection);
            }
            return JSON.writeValueAsString(Map.of("selections", selections));
        }
        String source = query.path("contribution").path("source").path("id").asString();
        String text = query.path("contribution").path("text").asString();
        boolean outgoing = "OUTGOING".equals(query.path("direction").asString());
        String type = query.path("type").asString();
        var relevant = outgoing ? iteration.relations().stream()
                .filter(r -> r.source().equals(source) && r.type().equals(type)
                        && (text.isEmpty() || r.contribution().equals(text))).toList() : List.<DemoScenario.Relation>of();
        boolean verify = "VERIFY".equals(query.path("phase").asString());
        var decisions = new ArrayList<Map<String, Object>>();
        for (JsonNode node : query.path("candidates")) {
            String id = node.path("id").asString();
            var target = relevant.stream().filter(r -> r.target().equals(id)).findFirst().orElse(null);
            boolean match = target != null;
            if (verify && match) {
                JsonNode proposal = query.path("proposal");
                match = id.equals(proposal.path("targetId").asString())
                        && target.contribution().equals(proposal.path("contribution").asString())
                        && original.equals(proposal.path("quote").asString())
                        && "REQUIRED".equals(proposal.path("necessity").asString())
                        && proposal.path("condition").asString().isEmpty()
                        && proposal.path("alternativeGroup").asString().isEmpty();
            }
            boolean descend = !verify && !match && relevant.stream()
                    .anyMatch(r -> r.path().subList(0, r.path().size() - 1).contains(id));
            String outcome = match ? (verify ? "VERIFIED" : "MATCH") : descend ? "DESCEND" : "REJECT";
            var decision = new LinkedHashMap<String, Object>();
            decision.put("targetId", id);
            decision.put("outcome", outcome);
            decision.put("contribution", match ? target.contribution() : "");
            decision.put("quote", match ? original : "");
            decision.put("necessity", match ? "REQUIRED" : null);
            decision.put("condition", "");
            decision.put("alternativeGroup", "");
            decision.put("rationale", "Szenario-Antwort: " + outcome);
            decision.put("question", "");
            decisions.add(decision);
        }
        return JSON.writeValueAsString(Map.of("decisions", decisions));
    }

    // ── Reformulation ────────────────────────────────────────────────────────

    private static JsonNode payload(String prompt, int start) {
        String json = prompt.substring(start);
        int errors = json.indexOf("\nVALIDATION_ERRORS");
        if (errors >= 0) json = json.substring(0, errors);
        return JSON.readTree(json);
    }

    private String reconcile(JsonNode data) {
        requireReformulationIteration(data.path("baseline").path("originalText").asString());
        var reply = new LinkedHashMap<String, Object>();
        reply.put("affectedSectionIds", List.of());
        reply.put("sourceResolutions", List.of());
        reply.put("findings", List.of());
        return JSON.writeValueAsString(reply);
    }

    private String node(JsonNode data) {
        requireReformulationIteration(data.path("baseline").path("originalText").asString());
        String nodeId = data.path("nodeId").asString();
        boolean root = data.path("parentId").isMissingNode() || data.path("parentId").isNull();
        String rootCode = nodeId.contains("-") ? nodeId.substring(0, nodeId.indexOf('-')) : nodeId;
        boolean anchored = root && DemoScenario.ROOTS.contains(rootCode);

        var statementIds = new LinkedHashSet<String>();
        var questionIds = new LinkedHashSet<String>();
        var rejected = new LinkedHashSet<String>();
        var wordings = new HashSet<String>();
        var subjects = new HashSet<String>();
        var questionsById = new LinkedHashMap<String, JsonNode>();
        for (JsonNode statement : data.path("directContributions")) collectStatement(statement, statementIds, rejected, wordings);
        for (JsonNode child : data.path("children")) {
            for (JsonNode statement : child.path("statementProposals")) collectStatement(statement, statementIds, rejected, wordings);
            child.path("preservedStatementIds").forEach(id -> statementIds.add(id.asString()));
            for (JsonNode question : child.path("questionProposals")) collectQuestion(question, questionIds, subjects, questionsById);
            child.path("preservedQuestionIds").forEach(id -> questionIds.add(id.asString()));
        }
        for (JsonNode question : data.path("openDecisions")) collectQuestion(question, questionIds, subjects, questionsById);
        collectInheritedRejections(data.path("inheritedDecisionContext"), rejected);

        var reformulation = iteration.reformulation();
        var statements = new ArrayList<Map<String, Object>>();
        if (anchored) {
            for (var addition : reformulation.additions()) {
                if (addition.anchorRoot().equals(rootCode)) addStatement(statements, addition.wording(), addition.provenance(), List.of(), rejected, wordings);
            }
            for (JsonNode answer : data.path("answers")) {
                if (!answer.path("disposition").isMissingNode() && !answer.path("disposition").isNull()
                        && !"ANSWER".equals(answer.path("disposition").asString())) continue;
                JsonNode question = questionsById.get(answer.path("questionId").asString());
                var values = new ArrayList<String>();
                answer.path("values").forEach(value -> values.add(value.asString()));
                var authored = question != null ? authoredQuestion(question.path("key").path("subject").asString())
                        : authoredQuestionByValues(values);
                if (authored == null || !authored.anchorRoot().equals(rootCode)) continue;
                // A dependency may only reference a question visible in this exact input.
                var dependencies = question != null ? List.of(question.path("id").asString()) : List.<String>of();
                for (String value : values) {
                    String consequence = authored.consequences().get(value);
                    if (consequence == null) throw failure(DemoReplayException.Code.MISSING_REPLY,
                            "no authored consequence for " + authored.id() + " = " + value);
                    addStatement(statements, consequence, "MODEL_ADDITION", dependencies, rejected, wordings);
                }
            }
        }
        var questions = new ArrayList<Map<String, Object>>();
        if (anchored) {
            for (var question : reformulation.questions()) {
                if (!question.anchorRoot().equals(rootCode) || subjects.contains(question.subject())) continue;
                var schema = new LinkedHashMap<String, Object>();
                schema.put("kind", question.kind());
                schema.put("options", question.options());
                schema.put("unit", null);
                schema.put("minimum", null);
                schema.put("maximum", null);
                var proposal = new LinkedHashMap<String, Object>();
                proposal.put("subject", question.subject());
                proposal.put("dimension", question.dimension());
                proposal.put("scope", nodeId);
                proposal.put("wording", question.wording());
                proposal.put("rationale", question.rationale());
                proposal.put("affectedStatementIds", List.of());
                proposal.put("sourceSpans", List.of());
                proposal.put("nodeIds", List.of(nodeId));
                proposal.put("edgeIds", List.of());
                proposal.put("answerSchema", schema);
                proposal.put("prerequisites", List.of());
                proposal.put("consequences", question.consequenceSummary());
                questions.add(proposal);
            }
        }
        var reply = new LinkedHashMap<String, Object>();
        reply.put("summary", anchored ? reformulation.summaries().getOrDefault(rootCode, reformulation.defaultSummary())
                : reformulation.defaultSummary());
        reply.put("statementProposals", statements);
        reply.put("preservedStatementIds", List.copyOf(statementIds));
        reply.put("questionProposals", questions);
        reply.put("preservedQuestionIds", List.copyOf(questionIds));
        reply.put("uncoveredSourceRefs", List.of());
        reply.put("conflictCandidates", List.of());
        return JSON.writeValueAsString(reply);
    }

    private DemoScenario.Question authoredQuestion(String subject) {
        return scenario.iterations().values().stream().flatMap(i -> i.reformulation().questions().stream())
                .filter(q -> q.subject().equals(subject)).findFirst().orElse(null);
    }

    /** Only an unambiguous option value identifies an authored question without its visible record. */
    private DemoScenario.Question authoredQuestionByValues(List<String> values) {
        if (values.isEmpty()) return null;
        var matches = scenario.iterations().values().stream().flatMap(i -> i.reformulation().questions().stream())
                .filter(q -> q.consequences().keySet().containsAll(values)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static void addStatement(List<Map<String, Object>> statements, String wording, String provenance,
                                     List<String> dependencies, Set<String> rejected, Set<String> existing) {
        // Exact rejected wording is evidence, never a new offer; duplicates are not repeated.
        if (existing.contains(wording) || rejected.stream().anyMatch(r -> !r.isEmpty() && wording.contains(r))) return;
        existing.add(wording);
        var statement = new LinkedHashMap<String, Object>();
        statement.put("wording", wording);
        statement.put("provenance", provenance);
        statement.put("sourceSpans", List.of());
        statement.put("architectureLinks", List.of());
        statement.put("questionDependencies", dependencies);
        statement.put("conditionalValidity", null);
        statements.add(statement);
    }

    private static void collectStatement(JsonNode statement, Set<String> ids, Set<String> rejected, Set<String> wordings) {
        ids.add(statement.path("id").asString());
        String wording = statement.path("wording").asString();
        wordings.add(wording);
        if ("REJECTED".equals(statement.path("reviewState").asString())) rejected.add(wording.strip());
    }

    private static void collectQuestion(JsonNode question, Set<String> ids, Set<String> subjects, Map<String, JsonNode> byId) {
        String id = question.path("id").asString();
        ids.add(id);
        byId.put(id, question);
        question.path("aliases").forEach(alias -> byId.put(alias.asString(), question));
        subjects.add(question.path("key").path("subject").asString());
    }

    private static void collectInheritedRejections(JsonNode node, Set<String> rejected) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            if ("REJECTED".equals(node.path("reviewState").asString()) && node.path("wording").isString())
                rejected.add(node.path("wording").asString().strip());
            node.properties().forEach(e -> collectInheritedRejections(e.getValue(), rejected));
        } else if (node.isArray()) {
            node.forEach(item -> collectInheritedRejections(item, rejected));
        }
    }

    private static List<String> keys(String prompt) {
        Matcher matcher = KEYS.matcher(prompt);
        if (!matcher.find()) throw failure(DemoReplayException.Code.UNKNOWN_PROMPT, "prompt does not declare expected keys");
        return Arrays.stream(matcher.group(1).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static DemoReplayException failure(DemoReplayException.Code code, String message) {
        return new DemoReplayException(code, message);
    }
}
