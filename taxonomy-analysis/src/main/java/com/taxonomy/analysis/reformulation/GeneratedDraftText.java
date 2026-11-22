package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.DecisionAnswer;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.Statement;
import com.taxonomy.reformulation.ValidationReport;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Editable generated text. Sections and source references carry the separate synthesis evidence. */
final class GeneratedDraftText {
    private static final Set<String> ABBREVIATED_TITLES = Set.of("dr", "prof", "dipl", "priv", "mr", "mrs", "ms", "jr", "sr", "lieut", "capt");

    private GeneratedDraftText() {}

    static String render(ReformulationBaseline baseline, Collection<Statement> statements, Collection<DecisionQuestion> questions,
                         List<DecisionAnswer> answers, List<ValidationReport.Finding> findings) {
        String original = baseline.originalText();
        boolean sourceRejected = originalRejected(original, statements);
        var provenance = baseline.frozenContext().containsKey("adoptedLineage")
                ? Statement.Provenance.ADOPTED_SOURCE : Statement.Provenance.ORIGINAL;
        boolean german = baseline.language().equals("de");
        var decisionWordings = new LinkedHashMap<String, String>();
        questions.forEach(q -> q.referenceIds().forEach(id -> decisionWordings.put(id, q.wording())));
        var parts = new ArrayList<String>();
        if (!sourceRejected && !original.isEmpty()) parts.add(paragraphs(baseline));
        for (var statement : statements) {
            if (rejected(statement)) continue;
            boolean automatic = statement.editingOrigin() != Statement.EditingOrigin.HUMAN && unqualified(statement);
            if (sourceRejected && automatic && statement.wording().equals(original)) continue;
            if (!sourceRejected && automatic && statement.provenance() == provenance
                    && ("UNREVIEWED".equals(statement.reviewState()) || statement.wording().equals(original))
                    && exactQuote(statement, original)) continue;
            var text = new StringBuilder("[").append(statement.provenance()).append("] ").append(statement.wording());
            if (statement.conditionalValidity() != null)
                text.append(german ? "\nBedingung: " : "\nCondition: ").append(statement.conditionalValidity());
            for (String id : statement.questionDependencies())
                text.append(german ? "\nAbhängig von: " : "\nDepends on: ").append(decisionWordings.getOrDefault(id, id));
            parts.add(text.toString());
        }
        var activeAnswers = DecisionAnswer.active(answers);
        for (var question : questions) {
            var text = new StringBuilder("[").append(question.state()).append("] ").append(question.wording());
            if (question.state() == DecisionQuestion.State.OPEN && !question.answerSchema().options().isEmpty())
                text.append(" — ").append(String.join(" / ", question.answerSchema().options()));
            for (var resolution : question.sourceResolutions())
                text.append(" — Original: ").append(String.join(", ", resolution.values())).append(" (").append(resolution.rationale()).append(")");
            activeAnswers.stream().filter(a -> question.referenceIds().contains(a.questionId()))
                    .filter(a -> !a.values().isEmpty() || a.otherText() != null).forEach(answer -> {
                text.append(" — Human decision: ").append(String.join(", ", answer.values()));
                if (answer.otherText() != null) text.append(" — ").append(answer.otherText());
            });
            parts.add(text.toString());
        }
        findings.stream().filter(f -> f.kind() == ValidationReport.Kind.CONFLICT).distinct()
                .forEach(f -> parts.add("[CONFLICT] " + f.message()));
        return String.join("\n\n", parts);
    }

    static boolean originalRejected(String original, Collection<Statement> statements) {
        return statements.stream().anyMatch(s -> s.provenance().isSource() && unqualified(s)
                && rejected(s) && original.equals(s.wording()));
    }

    static boolean hasParagraphSource(ReformulationBaseline baseline, String body, Collection<Statement> statements) {
        String projection = paragraphs(baseline);
        return !projection.equals(baseline.originalText()) && !originalRejected(baseline.originalText(), statements)
                && (body.equals(projection) || body.startsWith(projection + "\n\n"));
    }

    private static boolean rejected(Statement statement) { return "REJECTED".equals(statement.reviewState()); }

    private static boolean unqualified(Statement statement) {
        return statement.conditionalValidity() == null && statement.questionDependencies().isEmpty();
    }

    private static boolean exactQuote(Statement statement, String source) {
        if (statement.sourceSpans().isEmpty()) return false;
        int start = statement.sourceSpans().getFirst().start();
        int end = start;
        for (var span : statement.sourceSpans()) {
            if (span.start() != end || !span.matches(source)) return false;
            end = span.end();
        }
        return statement.wording().equals(source.substring(start, end));
    }

    /** Only exchange one plain inline space at a complete sentence boundary for a paragraph break. */
    private static String paragraphs(ReformulationBaseline baseline) {
        String source = baseline.originalText();
        // An adopted requirement is already authored version evidence, including its layout.
        if (baseline.frozenContext().containsKey("adoptedLineage")) return source;
        if (source.isEmpty() || !Character.isLetter(source.codePointAt(0)) || source.chars().anyMatch(c ->
                c == '\n' || c == '\r' || c == '\t' || "|`<>=[]{}".indexOf(c) >= 0)) return source;
        var boundaries = BreakIterator.getSentenceInstance(Locale.forLanguageTag(baseline.language()));
        boundaries.setText(source);
        var text = new StringBuilder();
        int start = 0;
        for (int end = boundaries.next(); end != BreakIterator.DONE && end < source.length(); end = boundaries.next()) {
            if (end >= 2 && source.charAt(end - 1) == ' ' && ".!?".indexOf(source.charAt(end - 2)) >= 0
                    && Character.isUpperCase(source.codePointAt(end)) && !abbreviatedTitleOrInitial(source, end - 2)) {
                text.append(source, start, end - 1).append("\n\n");
                start = end;
            }
        }
        return text.append(source, start, source.length()).toString();
    }

    private static boolean abbreviatedTitleOrInitial(String source, int punctuation) {
        if (source.charAt(punctuation) != '.') return false;
        int start = punctuation;
        while (start > 0 && Character.isLetter(source.charAt(start - 1))) start--;
        if (start == punctuation) return false;
        // Sentence segmentation can treat Dr., Mrs. or an initial as a sentence end.
        return punctuation - start <= 3 && Character.isUpperCase(source.codePointAt(start))
                || ABBREVIATED_TITLES.contains(source.substring(start, punctuation).toLowerCase(Locale.ROOT));
    }
}
