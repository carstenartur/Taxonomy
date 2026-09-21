package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Predicate;

/** Bounded sibling grouping. Stored details are never replaced by an aggregate summary. */
final class BoundedNodeSynthesis {
    static final int MAX_GROUPS = 16;
    private static final String GROUP_CONTRACT = "\nBOUNDED_GROUP_V1: Formulate only the supplied sibling group. Other siblings still exist. "
            + "Keep the complete original, restrictions, decisions and directed boundary relations. Do not invent a taxonomy node for the group.";
    private static final String AGGREGATE_CONTRACT = "\nBOUNDED_AGGREGATE_V1: Combine the group summaries into this parent's short formulation. "
            + "Detailed statements remain separately stored under preservedStatementIds; do not pretend their text was rechecked here. "
            + "All questions and details must survive. Find cross-group decisions without deciding them. Do not infer absent features are excluded.";
    private final ObjectMapper json;

    BoundedNodeSynthesis(ObjectMapper json) { this.json = json; }

    List<NodeSynthesisInput> partition(NodeSynthesisInput input, Predicate<NodeSynthesisInput> fits) {
        ObjectNode description = description(input.nodeDescription());
        var items = new ArrayList<Item>();
        if (description != null && description.path("terminalContributions").isArray()) {
            description.path("terminalContributions").forEach(n -> items.add(new Item(null, n)));
        }
        input.children().forEach(child -> items.add(new Item(child, null)));
        if (items.size() < 2 || !fits.test(group(input, description, List.of()))) throw tooLarge();
        var groups = new ArrayList<NodeSynthesisInput>();
        var selected = new ArrayList<Item>();
        for (Item item : items) {
            selected.add(item);
            if (fits.test(group(input, description, selected))) continue;
            selected.removeLast();
            if (selected.isEmpty()) throw tooLarge();
            groups.add(group(input, description, selected));
            if (groups.size() >= MAX_GROUPS) throw tooLarge();
            selected.clear(); selected.add(item);
            if (!fits.test(group(input, description, selected))) throw tooLarge();
        }
        groups.add(group(input, description, selected));
        if (groups.size() < 2) throw tooLarge();
        return List.copyOf(groups);
    }

    private NodeSynthesisInput group(NodeSynthesisInput input, ObjectNode description, List<Item> selected) {
        String context = input.nodeDescription();
        if (description != null && description.path("terminalContributions").isArray()) {
            ObjectNode copy = description.deepCopy();
            var terminals = copy.putArray("terminalContributions");
            selected.stream().filter(i -> i.terminal() != null).forEach(i -> terminals.add(i.terminal()));
            context = json.writeValueAsString(copy);
        }
        var children = selected.stream().map(Item::child).filter(Objects::nonNull).toList();
        return dependencyClosedGroup(input, context, children);
    }

    private NodeSynthesisInput dependencyClosedGroup(NodeSynthesisInput input, String context, List<NodeSynthesisResult> children) {
        var available = new LinkedHashMap<String, DecisionQuestion>();
        input.openDecisions().forEach(q -> q.referenceIds().forEach(id -> available.put(id, q)));
        input.children().forEach(c -> c.questionProposals().forEach(q -> q.referenceIds().forEach(id -> available.put(id, q))));
        var evidence = new LinkedHashMap<String, Statement>();
        input.directContributions().forEach(s -> evidence.put(s.id(), s));
        input.children().forEach(c -> c.statementProposals().forEach(s -> evidence.put(s.id(), s)));
        var needed = new LinkedHashSet<String>();
        input.openDecisions().forEach(q -> needed.add(q.id()));
        children.forEach(c -> c.questionProposals().forEach(q -> needed.add(q.id())));
        input.directContributions().forEach(s -> needed.addAll(s.questionDependencies()));
        children.forEach(c -> c.statementProposals().forEach(s -> needed.addAll(s.questionDependencies())));
        var pending = new ArrayDeque<>(needed);
        while (!pending.isEmpty()) {
            var question = available.get(pending.removeFirst());
            if (question == null) throw new IllegalArgumentException("Unknown grouped decision prerequisite");
            var dependencies = new LinkedHashSet<>(question.prerequisites());
            question.affectedStatementIds().stream().map(evidence::get).filter(Objects::nonNull)
                    .forEach(s -> dependencies.addAll(s.questionDependencies()));
            for (String prerequisite : dependencies) if (needed.add(prerequisite)) pending.addLast(prerequisite);
        }
        var childQuestionIds = new HashSet<String>();
        var childStatementIds = new HashSet<String>();
        children.forEach(c -> {
            c.questionProposals().forEach(q -> childQuestionIds.addAll(q.referenceIds()));
            c.statementProposals().forEach(s -> childStatementIds.add(s.id())); childStatementIds.addAll(c.preservedStatementIds());
        });
        var decisions = new LinkedHashMap<String, DecisionQuestion>();
        input.openDecisions().forEach(q -> addQuestion(decisions, q));
        for (String id : needed) if (!childQuestionIds.contains(id)) addQuestion(decisions, available.get(id));
        var direct = new LinkedHashMap<String, Statement>(); input.directContributions().forEach(s -> direct.put(s.id(), s));
        decisions.values().forEach(q -> q.affectedStatementIds().forEach(id -> {
            if (!childStatementIds.contains(id) && evidence.containsKey(id)) direct.put(id, evidence.get(id));
        }));
        return new NodeSynthesisInput(input.baseline(), input.nodeId(), input.parentId(), context, input.sourceAnchors(),
                List.copyOf(direct.values()), children, input.boundaryEdges(), input.answers(), List.copyOf(decisions.values()),
                input.preservationContract() + GROUP_CONTRACT);
    }

    NodeSynthesisInput aggregate(NodeSynthesisInput input, List<NodeSynthesisResult> parts) {
        // Full child details have already been processed and remain in the resulting document.
        // Only summaries and stable references enter this short-parent composition step.
        var children = new ArrayList<NodeSynthesisResult>();
        for (NodeSynthesisResult part : parts) {
            var statements = new LinkedHashSet<>(part.preservedStatementIds());
            part.statementProposals().forEach(s -> statements.add(s.id()));
            children.add(new NodeSynthesisResult(input.nodeId(), part.summary(), List.of(), List.copyOf(statements),
                    part.questionProposals(), part.preservedQuestionIds(), part.uncoveredSourceRefs(), part.conflictCandidates()));
        }
        var retained = new LinkedHashMap<String, DecisionQuestion>();
        input.openDecisions().forEach(q -> addQuestion(retained, q));
        input.children().forEach(c -> c.questionProposals().forEach(q -> addQuestion(retained, q)));
        String context = input.nodeDescription();
        ObjectNode description = description(context);
        if (description != null && description.path("terminalContributions").isArray()) {
            // Archive and group checkpoints retain the full terminal descriptions.
            var terminalIds = description.putArray("groupedTerminalIds");
            description.path("terminalContributions").forEach(n -> {
                if (n.has("id")) terminalIds.add(n.path("id"));
                else if (n.has("code")) terminalIds.add(n.path("code"));
            });
            description.putArray("terminalContributions");
            context = json.writeValueAsString(description);
        }
        return copy(input, context, children, List.copyOf(retained.values()), AGGREGATE_CONTRACT);
    }

    NodeSynthesisResult combine(NodeSynthesisInput input, List<NodeSynthesisResult> parts, NodeSynthesisResult aggregate) {
        var statements = new LinkedHashMap<String, Statement>();
        var questions = new LinkedHashMap<String, DecisionQuestion>();
        var uncovered = new LinkedHashSet<Statement.SourceSpan>();
        var conflicts = new LinkedHashSet<ValidationReport.Finding>();
        var results = new ArrayList<>(parts); results.add(aggregate);
        for (NodeSynthesisResult result : results) {
            for (Statement statement : result.statementProposals()) {
                var previous = statements.putIfAbsent(statement.id(), statement);
                if (previous != null && !previous.equals(statement)) throw new IllegalArgumentException("Conflicting grouped statement identity");
            }
            result.questionProposals().forEach(q -> addQuestion(questions, q));
            uncovered.addAll(result.uncoveredSourceRefs()); conflicts.addAll(result.conflictCandidates());
        }
        var preservedStatements = new LinkedHashSet<String>();
        var preservedQuestions = new LinkedHashSet<String>();
        input.directContributions().forEach(s -> preservedStatements.add(s.id()));
        input.openDecisions().forEach(q -> preservedQuestions.add(q.id()));
        input.children().forEach(c -> {
            c.statementProposals().forEach(s -> preservedStatements.add(s.id())); preservedStatements.addAll(c.preservedStatementIds());
            c.questionProposals().forEach(q -> preservedQuestions.add(q.id())); preservedQuestions.addAll(c.preservedQuestionIds());
        });
        conflicts.add(new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW, "GROUPED_SYNTHESIS",
                "Parent composed from " + parts.size() + " bounded sibling groups; full detail evidence remains stored and requires semantic review",
                List.of(), List.of()));
        return new NodeSynthesisResult(input.nodeId(), aggregate.summary(), List.copyOf(statements.values()), List.copyOf(preservedStatements),
                List.copyOf(questions.values()), List.copyOf(preservedQuestions), List.copyOf(uncovered), List.copyOf(conflicts));
    }

    private static void addQuestion(Map<String, DecisionQuestion> questions, DecisionQuestion next) {
        var previous = questions.putIfAbsent(next.id(), next);
        if (previous == null || previous.equals(next)) return;
        // Identical semantic output can occur in two groups. Keep all discoveries,
        // but never resolve divergent answers/contracts by last-write-wins.
        var comparable = new DecisionQuestion(next.id(), next.key(), next.wording(), previous.discoveries(), next.affectedStatementIds(),
                next.answerSchema(), next.prerequisites(), next.dependentQuestionIds(), next.consequences(), next.state(),
                next.aliases(), next.origins(), next.sourceResolutions());
        if (!previous.equals(comparable)) throw new IllegalArgumentException("Conflicting grouped question identity");
        var discoveries = new LinkedHashSet<>(previous.discoveries()); discoveries.addAll(next.discoveries());
        questions.put(next.id(), new DecisionQuestion(previous.id(), previous.key(), previous.wording(), List.copyOf(discoveries),
                previous.affectedStatementIds(), previous.answerSchema(), previous.prerequisites(), previous.dependentQuestionIds(),
                previous.consequences(), previous.state(), previous.aliases(), previous.origins(), previous.sourceResolutions()));
    }

    private static NodeSynthesisInput copy(NodeSynthesisInput input, String description, List<NodeSynthesisResult> children,
            List<DecisionQuestion> decisions, String contract) {
        return new NodeSynthesisInput(input.baseline(), input.nodeId(), input.parentId(), description, input.sourceAnchors(),
                input.directContributions(), children, input.boundaryEdges(), input.answers(), decisions, input.preservationContract() + contract);
    }
    private ObjectNode description(String value) {
        try { JsonNode node = json.readTree(value); return node instanceof ObjectNode object ? object : null; }
        catch (RuntimeException notStructured) { return null; }
    }
    private static IllegalStateException tooLarge() { return new IllegalStateException("INPUT_TOO_LARGE_FOR_PROVIDER: bounded grouping cannot preserve this context"); }
    private record Item(NodeSynthesisResult child, JsonNode terminal) {}
}
