package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.RequirementDecisionSection;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Human questions recorded by a completed analysis. Interrupted provider work is not a domain question. */
record SnapshotClarifications(List<DecisionQuestion> questions, List<Section> sections) {
    private static final String CLARIFICATION = "CLARIFICATION:";
    private static final DecisionQuestion.AnswerSchema TEXT =
            new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null);

    static SnapshotClarifications from(AnalysisResult analysis, String snapshot, PortfolioJsonCodec json) {
        var questions = new LinkedHashMap<String, DecisionQuestion>();
        if (analysis == null) return new SnapshotClarifications(List.of(), List.of());
        if (analysis.getAnalysisCoverage() != null) {
            analysis.getAnalysisCoverage().nodes().forEach((code, assessment) -> {
                var reason = assessment.reason();
                if (reason != null && reason.startsWith(CLARIFICATION)) {
                    // Historical snapshots retained the reason and question together. Keep their exact wording.
                    add(questions, snapshot, "ROOT", List.of(code), reason.substring(CLARIFICATION.length()),
                            "", "", json);
                }
            });
        }
        var report = analysis.getRelationSearchReport();
        if (report == null && analysis.getArchitectureView() != null) report = analysis.getArchitectureView().getRelationSearchReport();
        if (report != null) {
            for (var source : report.sources()) {
                add(questions, snapshot, "SOURCE", List.of(source.node().id()), source.question(),
                        source.rationale(), "", json);
            }
            for (var work : report.result().unfinished()) {
                if (!"UNRESOLVED".equals(work.reason()) && !"ENDPOINT_REFINEMENT".equals(work.reason())) continue;
                var nodes = Stream.concat(Stream.of(work.sourceId()), work.targets().stream())
                        .filter(Objects::nonNull).distinct().toList();
                if (nodes.isEmpty()) continue;
                add(questions, snapshot, work.reason(), nodes, work.question(),
                        String.join(" · ", Objects.toString(work.type(), ""), Objects.toString(work.direction(), "")),
                        Objects.toString(work.contributionKey(), ""), json);
            }
        }
        var byNode = new LinkedHashMap<String, List<String>>();
        questions.values().forEach(question -> question.discoveries().getFirst().nodeIds().forEach(node ->
                byNode.computeIfAbsent(node, unused -> new ArrayList<>()).add(question.id())));
        var roots = new LinkedHashMap<String, String>();
        if (!byNode.isEmpty() && analysis.getTree() != null)
            analysis.getTree().forEach(root -> collectRoots(root, root.getCode(), roots));
        var sections = byNode.entrySet().stream().map(entry -> new Section(entry.getKey(),
                roots.getOrDefault(entry.getKey(), ""), entry.getKey(), "", List.of(), List.of(), entry.getValue())).toList();
        return new SnapshotClarifications(List.copyOf(questions.values()), sections);
    }

    SnapshotClarifications withSourceQuestions(String original, String snapshot, PortfolioJsonCodec json) {
        var authored = RequirementDecisionSection.questions(original);
        if (authored.isEmpty()) return this;
        var combined = new ArrayList<>(questions);
        var combinedSections = new ArrayList<>(sections);
        for (var entry : authored) {
            // A source decision has no inferred catalogue owner. Refine the exact passage in its own section.
            String id = "source-question-" + StableIdentityHash.sha256(json.write(List.of(
                    entry.heading(), entry.question(), entry.paragraph())));
            var discovery = new DecisionQuestion.Discovery(snapshot, entry.heading(), "",
                    entry.paragraph().equals(entry.question()) ? List.of(entry.question()) : List.of(entry.paragraph(), entry.question()),
                    List.of(), List.of());
            combined.add(new DecisionQuestion(id, new DecisionQuestion.Key(id, "SOURCE_DECISION", id), entry.question().exactText(),
                    List.of(discovery), List.of(), TEXT, List.of(), List.of(), "", DecisionQuestion.State.OPEN));
            combinedSections.add(new Section(id, "", entry.heading(), entry.paragraph().exactText(), List.of(), List.of(), List.of(id)));
        }
        return new SnapshotClarifications(List.copyOf(combined), List.copyOf(combinedSections));
    }

    private static void collectRoots(TaxonomyNodeDto node, String root, Map<String, String> roots) {
        roots.put(node.getCode(), root);
        if (node.getChildren() != null) node.getChildren().forEach(child -> collectRoots(child, root, roots));
    }

    private static void add(Map<String, DecisionQuestion> questions, String snapshot, String kind, List<String> nodes,
                            String wording, String context, String contribution, PortfolioJsonCodec json) {
        if (wording == null || wording.isBlank()) return;
        String recorded = wording.strip();
        String id = "analysis-" + StableIdentityHash.sha256(json.write(List.of(snapshot, kind, nodes, recorded,
                Objects.toString(context, ""), contribution)));
        var discovery = new DecisionQuestion.Discovery(snapshot, Objects.toString(context, ""), "", List.of(), nodes, List.of());
        questions.putIfAbsent(id, new DecisionQuestion(id, new DecisionQuestion.Key(nodes.getFirst(), kind + "-" + id, nodes.getFirst()),
                recorded, List.of(discovery), List.of(), TEXT, List.of(), List.of(), "", DecisionQuestion.State.OPEN));
    }
}
