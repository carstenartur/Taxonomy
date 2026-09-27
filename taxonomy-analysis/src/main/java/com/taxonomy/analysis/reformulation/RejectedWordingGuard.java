package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import java.util.*;

/** Exact rejected wording is retained as evidence, never promoted by a later model result. */
final class RejectedWordingGuard {
    private RejectedWordingGuard() {}

    static Set<String> from(Collection<Statement> statements) {
        var rejected = new LinkedHashSet<String>();
        statements.stream().filter(s -> "REJECTED".equals(s.reviewState()))
                .forEach(s -> rejected.add(s.wording().strip()));
        return rejected;
    }

    static boolean repeats(String candidate, Set<String> rejected) {
        return candidate != null && rejected.stream().anyMatch(wording -> !wording.isEmpty() && candidate.contains(wording));
    }

    static String safeSummary(String summary, Set<String> rejected, List<ValidationReport.Finding> findings) {
        if (!repeats(summary, rejected)) return summary;
        findings.add(conflict(List.of()));
        return "";
    }

    static NodeSynthesisResult review(NodeSynthesisResult result, Collection<Statement> retained) {
        return review(result, retained, Set.of());
    }

    static NodeSynthesisResult review(NodeSynthesisResult result, Collection<Statement> retained, Set<String> historical) {
        var rejected = new LinkedHashSet<>(from(retained));
        rejected.addAll(historical);
        var findings = new ArrayList<>(result.conflictCandidates());
        var additions = new ArrayList<Statement>();
        for (var statement : result.statementProposals()) {
            if (repeats(statement.wording(), rejected)) {
                statement = new Statement(statement.id(), statement.wording(), statement.sourceSpans(), statement.provenance(),
                        statement.architectureLinks(), statement.questionDependencies(), statement.conditionalValidity(),
                        statement.editingOrigin(), "REJECTED");
                findings.add(conflict(List.of(statement.id())));
            }
            additions.add(statement);
        }
        String summary = safeSummary(result.summary(), rejected, findings);
        return new NodeSynthesisResult(result.nodeId(), summary, additions, result.preservedStatementIds(),
                result.questionProposals(), result.preservedQuestionIds(), result.uncoveredSourceRefs(), findings);
    }

    private static ValidationReport.Finding conflict(List<String> ids) {
        return new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "REJECTED_ADDITION_REINTRODUCED",
                "Rejected wording was repeated; candidate withheld from active text for review", ids, List.of());
    }
}
