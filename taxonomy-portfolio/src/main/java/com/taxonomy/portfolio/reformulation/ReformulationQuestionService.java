package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.reformulation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Pure proposal decision operations. Authorization, locking and append-only persistence belong to the aggregate. */
public final class ReformulationQuestionService {
    private ReformulationQuestionService() {}

    public static Revision answer(String proposalId, Revision prior, AnswerRequest request, String actor,
            ReformulationBaseline baseline) {
        if (request == null || request.questionId() == null) throw invalid("Question is required");
        rationale(request.rationale());
        var question = prior.questions().stream().filter(q -> q.referenceIds().contains(request.questionId()))
                .findFirst().orElseThrow(() -> invalid("Unknown question"));
        var values = request.values() == null ? List.<String>of() : request.values();
        if(values.stream().anyMatch(Objects::isNull))throw invalid("Answer values cannot be null");
        String action = request.action();
        if (!Set.of("ANSWER", "DEFER", "NOT_APPLICABLE").contains(Objects.toString(action, ""))) throw invalid("Invalid answer action");
        // OPEN is an option meaning, not a reserved word in a free-text answer.
        boolean choice = question.answerSchema().kind() == DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE
                || question.answerSchema().kind() == DecisionQuestion.AnswerSchema.Kind.MULTIPLE_CHOICE;
        boolean open = choice && values.size() == 1
                && meaning(question.answerSchema(), values.getFirst()) == DecisionQuestion.AnswerSchema.OptionMeaning.OPEN;
        var state = "DEFER".equals(action) || open ? DecisionQuestion.State.DEFERRED
                : "NOT_APPLICABLE".equals(action) ? DecisionQuestion.State.NOT_APPLICABLE : DecisionQuestion.State.ANSWERED;
        if (state == DecisionQuestion.State.ANSWERED) {
            if (!applicable(question, prior.questions(), prior.answers())) throw invalid("Question is not applicable to the selected variant");
            validate(question.answerSchema(), values, request.otherText());
        }
        else if (!values.isEmpty() && !open) throw invalid("Deferral and not-applicable must not contain answer values");
        if (open && !question.answerSchema().options().contains(values.getFirst())) throw invalid("Unknown open option");
        var supersedes = DecisionAnswer.active(prior.answers()).stream().filter(a -> question.referenceIds().contains(a.questionId()))
                .map(DecisionAnswer::id).toList();
        var answer = new DecisionAnswer(UUID.randomUUID().toString(), request.questionId(), proposalId, prior.number() + 1,
                state == DecisionQuestion.State.ANSWERED ? values : List.of(), state, actor, Instant.now(), request.rationale(),
                request.otherText(), state == DecisionQuestion.State.DEFERRED ? "DEFER" : action, supersedes);
        var answers = new ArrayList<>(prior.answers()); answers.add(answer);
        var findings = new ArrayList<>(prior.validation().findings());
        boolean sourceConflict = !question.sourceResolutions().isEmpty() && (state == DecisionQuestion.State.NOT_APPLICABLE
                || state == DecisionQuestion.State.ANSWERED && question.sourceResolutions().stream()
                .anyMatch(r -> !comparison(question.answerSchema(), r.values()).equals(comparison(question.answerSchema(), values))));
        if (sourceConflict) {
            state = DecisionQuestion.State.CONFLICT;
            findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "SOURCE_ANSWER_CONFLICT",
                    "Human decision conflicts with frozen source; source and decision are retained for review",
                    question.affectedStatementIds(), question.sourceResolutions().stream().flatMap(r -> r.sourceSpans().stream()).toList()));
        } else if (!question.sourceResolutions().isEmpty()) state = DecisionQuestion.State.ANSWERED;
        var updated = withState(question, state);
        var questions = new ArrayList<>(prior.questions().stream().map(q -> q.id().equals(question.id()) ? updated : q).toList());
        for (int i=0;i<questions.size();i++) {
            var follow = questions.get(i);
            if (follow.answerSchema().applicability().isEmpty() && follow.prerequisites().isEmpty()) continue;
            boolean applicable = applicable(follow, questions, answers);
            var activeDecisions = DecisionAnswer.active(answers).stream().filter(a -> follow.referenceIds().contains(a.questionId())).toList();
            boolean hasAnswer = !follow.sourceResolutions().isEmpty() || activeDecisions.stream().anyMatch(a -> a.state()==DecisionQuestion.State.ANSWERED);
            boolean explicitDecision = !activeDecisions.isEmpty();
            if (!applicable && hasAnswer) {
                questions.set(i, withState(follow, DecisionQuestion.State.CONFLICT));
                findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "STALE_FOLLOW_UP_CONTEXT",
                        "Earlier follow-up answer belongs to an incompatible variant; retained for review",follow.affectedStatementIds(),List.of()));
            } else if (!applicable && !explicitDecision) questions.set(i, withState(follow, DecisionQuestion.State.NOT_APPLICABLE));
            else if (applicable && follow.state()==DecisionQuestion.State.NOT_APPLICABLE && !explicitDecision) questions.set(i, withState(follow,DecisionQuestion.State.OPEN));
        }
        return new Revision(prior.number() + 1, prior.number(), prior.text(), prior.sections(), prior.statements(), questions,
                answers, new ValidationReport(findings), actor, answer.occurredAt(), request.rationale(),
                impact(prior, question, baseline), prior.variantOrigin());
    }

    public static void rationale(String rationale) {
        if (rationale == null || rationale.isBlank() || rationale.length() > 1000) throw invalid("Rationale must contain 1–1000 characters");
    }
    private static void validate(DecisionQuestion.AnswerSchema schema, List<String> values, String other) {
        if (values.isEmpty() || values.stream().anyMatch(v -> v == null || v.isBlank()) || new HashSet<>(values).size() != values.size())
            throw invalid("Answer values must be nonblank and distinct");
        if (schema.kind() != DecisionQuestion.AnswerSchema.Kind.MULTIPLE_CHOICE && values.size() != 1) throw invalid("Exactly one value required");
        switch (schema.kind()) {
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> {
                if (!schema.options().containsAll(values) || values.stream().anyMatch(v -> meaning(schema,v)==DecisionQuestion.AnswerSchema.OptionMeaning.OPEN)) throw invalid("Invalid choice");
                if (values.stream().anyMatch(v -> meaning(schema,v)==DecisionQuestion.AnswerSchema.OptionMeaning.OTHER) && (other == null || other.isBlank())) throw invalid("Other requires free text");
                if (schema.incompatibleOptions().stream().anyMatch(values::containsAll)) throw invalid("Incompatible selections");
                if (values.size() > 1 && values.stream().anyMatch(v -> meaning(schema,v)==DecisionQuestion.AnswerSchema.OptionMeaning.NOT_NEEDED)) throw invalid("Not needed cannot be combined with another option");
            }
            case NUMBER -> {
                try {
                    var n = new BigDecimal(values.getFirst());
                    if (schema.minimum() != null && n.compareTo(BigDecimal.valueOf(schema.minimum())) < 0
                            || schema.maximum() != null && n.compareTo(BigDecimal.valueOf(schema.maximum())) > 0) throw invalid("Number outside range");
                } catch (NumberFormatException failure) { throw invalid("Finite decimal number required"); }
            }
            case BOOLEAN -> { if (!Set.of("true", "false").contains(values.getFirst())) throw invalid("Boolean must be true or false"); }
            case TEXT -> { if (values.getFirst().length() > 20000) throw invalid("Text exceeds 20000 characters"); }
        }
        if (other != null && other.length() > 20000) throw invalid("Other text exceeds 20000 characters");
    }
    private static boolean applicable(DecisionQuestion question, List<DecisionQuestion> questions, List<DecisionAnswer> history) {
        for (String id : question.prerequisites()) {
            var prerequisite=questions.stream().filter(q -> q.referenceIds().contains(id)).findFirst();
            if(prerequisite.isEmpty() || prerequisite.get().state()!=DecisionQuestion.State.ANSWERED) return false;
        }
        for (var condition : question.answerSchema().applicability()) {
            var parent=questions.stream().filter(q -> q.referenceIds().contains(condition.questionId())).findFirst();
            if(parent.isEmpty() || parent.get().state()!=DecisionQuestion.State.ANSWERED) return false;
            var q=parent.get();
            boolean selected=DecisionAnswer.active(history).stream().filter(a -> q.referenceIds().contains(a.questionId()) && a.state()==DecisionQuestion.State.ANSWERED)
                    .anyMatch(a -> !Collections.disjoint(a.values(),condition.anyOf()));
            if(!selected && q.sourceResolutions().stream().noneMatch(r -> !Collections.disjoint(r.values(),condition.anyOf()))) return false;
        }
        return true;
    }
    private static DecisionQuestion.AnswerSchema.OptionMeaning meaning(DecisionQuestion.AnswerSchema schema, String value) {
        var explicit=schema.optionMeanings().get(value);
        if(explicit!=null)return explicit;
        if(isOpen(value))return DecisionQuestion.AnswerSchema.OptionMeaning.OPEN;
        if(isOther(value))return DecisionQuestion.AnswerSchema.OptionMeaning.OTHER;
        if(isNotNeeded(value))return DecisionQuestion.AnswerSchema.OptionMeaning.NOT_NEEDED;
        return DecisionQuestion.AnswerSchema.OptionMeaning.VALUE;
    }
    public static boolean isOpen(String value) { return Set.of("still open", "open", "offen", "noch offen").contains(value.toLowerCase(Locale.ROOT)); }
    public static boolean isOther(String value) { return Set.of("other", "other…", "sonstiges", "andere", "anderes").contains(value.toLowerCase(Locale.ROOT)); }
    private static boolean isNotNeeded(String value) { return Set.of("not needed", "no correction required", "keine korrektur erforderlich", "nicht erforderlich").contains(value.toLowerCase(Locale.ROOT)); }
    private static Set<?> comparison(DecisionQuestion.AnswerSchema schema, List<String> values) {
        if (schema.kind() != DecisionQuestion.AnswerSchema.Kind.NUMBER) return Set.copyOf(values);
        return values.stream().map(raw -> {
            String v = raw.trim().replaceFirst("[dDfF]$", "");
            return v.matches("[+-]?0[xX].*") ? new BigDecimal(Double.parseDouble(v)).stripTrailingZeros() : new BigDecimal(v).stripTrailingZeros();
        }).collect(java.util.stream.Collectors.toSet());
    }
    static DecisionQuestion withState(DecisionQuestion q, DecisionQuestion.State state) {
        if (q.state() == state) return q;
        var origins = new ArrayList<>(q.origins()); if (!origins.contains(q.origin())) origins.add(q.origin());
        return new DecisionQuestion(q.id(), q.key(), q.wording(), q.discoveries(), q.affectedStatementIds(), q.answerSchema(),
                q.prerequisites(), q.dependentQuestionIds(), q.consequences(), state, q.aliases(), origins, q.sourceResolutions());
    }
    public static ReformulationImpact impact(Revision prior, DecisionQuestion question, ReformulationBaseline baseline) {
        var statements = new LinkedHashSet<>(prior.impact().statementIds()); statements.addAll(question.affectedStatementIds());
        var questions = new LinkedHashSet<>(prior.impact().questionIds());
        if(prior.questions().stream().anyMatch(q -> !Collections.disjoint(q.referenceIds(),question.referenceIds())))questions.addAll(question.referenceIds());
        else prior.questions().stream().filter(q -> !Collections.disjoint(q.affectedStatementIds(),question.affectedStatementIds()))
                .forEach(q -> questions.addAll(q.referenceIds()));
        boolean global = prior.impact().global() || Set.of("global", "@document", "*").contains(question.key().scope().toLowerCase(Locale.ROOT));
        boolean changed;
        do {
            int size = questions.size() + statements.size();
            for (var q : prior.questions()) if (global || !Collections.disjoint(q.prerequisites(), questions)
                    || !Collections.disjoint(q.referenceIds(), questions)) {
                questions.addAll(q.referenceIds()); questions.addAll(q.dependentQuestionIds()); statements.addAll(q.affectedStatementIds());
            }
            for (var s : prior.statements()) if (global || !Collections.disjoint(s.questionDependencies(), questions)) statements.add(s.id());
            changed = size != questions.size() + statements.size();
        } while (changed);
        var sections = new LinkedHashSet<String>();
        for (var section : prior.sections()) if (global || !Collections.disjoint(section.statementIds(), statements)
                || !Collections.disjoint(section.questionIds(), questions)) sections.add(section.id());
        var edges = new LinkedHashSet<>(prior.impact().boundaryEdgeIds());
        var directlyAffected = Set.copyOf(sections);
        var json = new tools.jackson.databind.ObjectMapper();
        for (var edge : json.readTree(baseline.frozenContext().getOrDefault("relationMappings", "[]"))) {
            String source = edge.path("sourceCode").asText(), target = edge.path("targetCode").asText();
            if (global || directlyAffected.contains(source) || directlyAffected.contains(target)) {
                edges.add("edge-" + edge.path("id").asText());
                for (var section : prior.sections()) if (section.id().equals(source) || section.id().equals(target)) sections.add(section.id());
            }
        }
        do {
            int size = sections.size();
            for (var section : prior.sections()) if (!Collections.disjoint(section.children(), sections)) sections.add(section.id());
            changed = size != sections.size();
        } while (changed);
        sections.addAll(prior.impact().sectionIds());
        return new ReformulationImpact(List.copyOf(statements), List.copyOf(sections), List.copyOf(questions), List.copyOf(edges), global);
    }
    private static ReformulationAnswerException invalid(String message) { return new ReformulationAnswerException(message); }
}
