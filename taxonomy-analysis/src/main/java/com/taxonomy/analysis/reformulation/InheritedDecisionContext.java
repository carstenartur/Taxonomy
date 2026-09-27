package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.NodeSynthesisInput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Per-call view of immutable historical decisions; the archive itself is never rewritten. */
final class InheritedDecisionContext {
    private InheritedDecisionContext() {}

    static ArrayNode forNode(NodeSynthesisInput input, ObjectMapper json) {
        var nodes = new TreeSet<String>();
        nodes.add(input.nodeId());
        if (input.parentId() != null) nodes.add(input.parentId());
        input.children().forEach(child -> nodes.add(child.nodeId()));
        input.directContributions().forEach(s -> nodes.addAll(s.architectureLinks()));
        input.children().forEach(child -> child.statementProposals().forEach(s -> nodes.addAll(s.architectureLinks())));
        var edges = new TreeSet<>(input.boundaryEdges().keySet());
        input.boundaryEdges().values().forEach(value -> {
            var edge = json.readTree(value);
            for (String field : List.of("sourceCode", "targetCode"))
                if (edge.path(field).isString()) nodes.add(edge.path(field).asText());
        });
        return select(input.baseline().frozenContext().get("inheritedDecisionContext"), json, nodes, edges,
                input.directContributions().stream().map(s -> s.id()).toList(),
                input.openDecisions().stream().flatMap(q -> q.referenceIds().stream()).toList());
    }

    static Set<String> rejected(NodeSynthesisInput input, ObjectMapper json) {
        var rejected = new LinkedHashSet<String>();
        for (var history : forNode(input, json))
            for (var statement : history.path("statements"))
                if ("REJECTED".equals(statement.path("reviewState").asText())) {
                    String wording = statement.path("wording").asText().strip();
                    if (!wording.isEmpty()) rejected.add(wording);
                }
        return rejected;
    }

    static Set<String> allRejected(String frozen, ObjectMapper json) {
        var rejected = new LinkedHashSet<String>();
        if (frozen == null) return rejected;
        for (var history : json.readTree(frozen))
            for (var statement : history.path("statements"))
                if ("REJECTED".equals(statement.path("reviewState").asText())) {
                    String wording = statement.path("wording").asText().strip();
                    if (!wording.isEmpty()) rejected.add(wording);
                }
        return rejected;
    }

    static ArrayNode select(String frozen, ObjectMapper json, Set<String> nodes, Set<String> edges,
            Collection<String> currentStatements, Collection<String> currentQuestions) {
        var result = json.createArrayNode();
        if (frozen == null) return result;
        var histories = json.readTree(frozen);
        if (!histories.isArray()) throw new IllegalArgumentException("Malformed inherited decision context");
        for (var history : histories) {
            if (!history.isObject() || !history.path("statements").isArray()
                    || !history.path("questions").isArray() || !history.path("humanAnswers").isArray())
                throw new IllegalArgumentException("Malformed inherited decision entry");
            var statementIds = new TreeSet<String>(currentStatements);
            var questionIds = new TreeSet<String>(currentQuestions);
            // Scope, affected statements, prerequisites and dependencies use the same
            // closure shape as question impact. Unknown mappings stay visible.
            boolean changed;
            do {
                int size = statementIds.size() + questionIds.size();
                for (var statement : history.path("statements"))
                    if (statementApplies(statement, nodes, edges, questionIds)) {
                        id(statement, statementIds);
                        add(statement.path("questionDependencies"), questionIds);
                    }
                for (var question : history.path("questions"))
                    if (questionApplies(question, nodes, edges, statementIds, questionIds)) {
                        id(question, questionIds);
                        add(question.path("aliases"), questionIds);
                        add(question.path("prerequisites"), questionIds);
                        add(question.path("dependentQuestionIds"), questionIds);
                        add(question.path("affectedStatementIds"), statementIds);
                    }
                changed = size != statementIds.size() + questionIds.size();
            } while (changed);
            var copy = (ObjectNode) history.deepCopy();
            var selectedStatements = json.createArrayNode();
            for (var statement : history.path("statements"))
                if (statementIds.contains(statement.path("id").asText())
                        || (!statement.path("id").isString()
                            && statementApplies(statement, nodes, edges, questionIds))) selectedStatements.add(statement);
            copy.set("statements", selectedStatements);
            var selectedQuestions = json.createArrayNode();
            for (var question : history.path("questions"))
                if (questionIds.contains(question.path("id").asText())
                        || (!question.path("id").isString()
                            && questionApplies(question, nodes, edges, statementIds, questionIds))) selectedQuestions.add(question);
            copy.set("questions", selectedQuestions);
            var answers = json.createArrayNode();
            for (var answer : history.path("humanAnswers"))
                if (questionIds.contains(answer.path("questionId").asText())
                        || !answer.path("questionId").isString()) answers.add(answer);
            copy.set("humanAnswers", answers);
            // Review without a resolvable statement reference remains historical,
            // while a scoped finding follows its referenced statements.
            if (copy.path("historicalReview").isObject() && copy.path("historicalReview").path("findings").isArray()) {
                var review = (ObjectNode) copy.path("historicalReview");
                var findings = json.createArrayNode();
                for (var finding : review.path("findings"))
                    if (finding.path("statementIds").isEmpty()
                            || intersects(finding.path("statementIds"), statementIds)) findings.add(finding);
                review.set("findings", findings);
            }
            if (!copy.path("statements").isEmpty() || !copy.path("questions").isEmpty()
                    || !answers.isEmpty() || !copy.path("historicalReview").path("findings").isEmpty()) result.add(copy);
        }
        return result;
    }

    private static boolean statementApplies(JsonNode statement, Set<String> nodes, Set<String> edges, Set<String> questions) {
        var links = statement.path("architectureLinks");
        var dependencies = statement.path("questionDependencies");
        return intersects(links, nodes) || intersects(links, edges) || intersects(dependencies, questions)
                || (links.isEmpty() && dependencies.isEmpty());
    }

    private static boolean questionApplies(JsonNode question, Set<String> nodes, Set<String> edges,
            Set<String> statements, Set<String> questions) {
        String scope = question.path("key").path("scope").asText();
        if (Set.of("global", "@document", "*").contains(scope.toLowerCase(Locale.ROOT))
                || nodes.contains(scope) || edges.contains(scope)
                || intersects(question.path("affectedStatementIds"), statements)
                || intersects(question.path("prerequisites"), questions)
                || intersects(question.path("dependentQuestionIds"), questions)
                || questions.contains(question.path("id").asText())) return true;
        boolean mapped = !scope.isBlank();
        for (var discovery : question.path("discoveries")) {
            if (intersects(discovery.path("nodeIds"), nodes) || intersects(discovery.path("edgeIds"), edges)) return true;
            mapped |= !discovery.path("nodeIds").isEmpty() || !discovery.path("edgeIds").isEmpty();
        }
        // A scope not present in the frozen/current node set may be historical
        // and unmappable, not necessarily irrelevant. Keep it visibly unresolved.
        return !mapped;
    }

    private static void id(JsonNode item, Set<String> ids) {
        if (item.path("id").isString()) ids.add(item.path("id").asText());
    }
    private static void add(JsonNode values, Set<String> ids) {
        for (var item : values) if (item.isString()) ids.add(item.asText());
    }
    private static boolean intersects(JsonNode values, Set<String> ids) {
        for (var item : values) if (item.isString() && ids.contains(item.asText())) return true;
        return false;
    }
}
