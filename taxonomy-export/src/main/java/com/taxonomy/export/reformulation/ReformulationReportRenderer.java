package com.taxonomy.export.reformulation;

import com.taxonomy.reformulation.DecisionAnswer;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.Section;
import com.taxonomy.reformulation.Statement;
import com.taxonomy.reformulation.ValidationReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Framework-free rendering of saved data. External strings are literal text, never markup. */
public final class ReformulationReportRenderer {
    private ReformulationReportRenderer() {}

    public record Input(String language, boolean adoptionReceipt, Map<String, String> metadata,
            String originalText, String reviewedText, List<Section> sections, List<Statement> statements,
            List<DecisionQuestion> questions, List<DecisionAnswer> answers, ValidationReport validation,
            String evidenceJson) {
        public Input {
            if (!List.of("de", "en").contains(language)) throw new IllegalArgumentException("Report language must be de or en");
            metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
            Objects.requireNonNull(originalText); Objects.requireNonNull(reviewedText); Objects.requireNonNull(evidenceJson);
            sections = List.copyOf(sections); statements = List.copyOf(statements); questions = List.copyOf(questions);
            answers = List.copyOf(answers); Objects.requireNonNull(validation);
        }
    }
    private record Block(int level, String text) {}

    public static String markdown(Input input) {
        StringBuilder output = new StringBuilder();
        for (Block block : blocks(input)) {
            if (block.level() > 0) output.append("#".repeat(block.level())).append(' ').append(heading(block.text())).append("\n\n");
            else output.append(fenced(block.text())).append("\n\n");
        }
        return output.toString();
    }

    public static String html(Input input) {
        StringBuilder output = new StringBuilder("<!doctype html>\n<html lang=\"")
                .append(input.language()).append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">")
                .append("<title>").append(escape(title(input))).append("</title>")
                .append("<style>body{max-width:72rem;margin:2rem auto;padding:0 1rem;font:1rem/1.55 system-ui,sans-serif}")
                .append("pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}h2,h3{break-after:avoid;overflow-wrap:anywhere}")
                .append("h1{font-size:1.8rem}h2{border-top:1px solid;padding-top:1rem}")
                .append("@media print{body{margin:0}pre{orphans:3;widows:3}}</style></head><body><main>\n");
        for (Block block : blocks(input)) {
            String tag = block.level() == 0 ? "pre" : "h" + block.level();
            output.append('<').append(tag).append('>').append(escape(block.text()))
                    .append("</").append(tag).append(">\n");
        }
        return output.append("</main></body></html>\n").toString();
    }

    private static List<Block> blocks(Input in) {
        var out = new ArrayList<Block>();
        out.add(new Block(1, title(in)));
        out.add(new Block(0, in.adoptionReceipt()
                ? t(in, "Dokumentierte Übernahme zum angegebenen Zeitpunkt. Keine Aussage über die heute aktive Version oder eine fachliche Freigabe.",
                        "Documented adoption at the stated time. Not a claim about the currently active version or expert approval.")
                : t(in, "Gespeicherte Vorschlagsrevision – keine Übernahmebestätigung. Spätere Entscheidungen sind nicht Teil dieses historischen Stands.",
                        "Saved proposal revision — not an adoption receipt. Later decisions are not part of this historical record.")));
        in.metadata().forEach((key, value) -> out.add(new Block(0, key + ": " + value)));
        section(out, t(in, "Originalanforderung", "Original requirement"), in.originalText());
        section(out, t(in, "Vollständiger Text dieses Stands", "Complete text of this record"), in.reviewedText());
        out.add(new Block(2, t(in, "Taxonomienahe Gliederung", "Taxonomy-aligned structure")));
        for (Section section : in.sections()) {
            out.add(new Block(3, section.id() + " — " + section.title()));
            out.add(new Block(0, section.summary()));
            out.add(new Block(0, t(in, "Taxonomie: ", "Taxonomy: ") + section.taxonomyCode()
                    + "\n" + t(in, "Unterabschnitte: ", "Children: ") + section.children()
                    + "\n" + t(in, "Aussagen: ", "Statements: ") + section.statementIds()
                    + "\n" + t(in, "Fragen: ", "Questions: ") + section.questionIds()));
        }
        out.add(new Block(2, t(in, "Aussagen, Bedingungen und Herkunft", "Statements, conditions and provenance")));
        for (Statement statement : in.statements()) {
            out.add(new Block(3, statement.id() + " — " + statement.provenance()));
            out.add(new Block(0, statement.wording()));
            out.add(new Block(0, t(in, "Prüfstatus: ", "Review: ") + statement.reviewState()
                    + "\n" + t(in, "Bearbeitungsherkunft: ", "Editing origin: ") + statement.editingOrigin()
                    + "\n" + t(in, "Bedingung: ", "Condition: ") + Objects.toString(statement.conditionalValidity(), "")
                    + "\n" + t(in, "Architekturbezüge: ", "Architecture references: ") + statement.architectureLinks()
                    + "\n" + t(in, "Fragen: ", "Questions: ") + statement.questionDependencies()));
            spans(out, in, statement.sourceSpans());
        }
        out.add(new Block(2, t(in, "Entscheidungsfragen", "Decision questions")));
        if (in.questions().isEmpty()) out.add(new Block(0, t(in, "Keine Fragen gespeichert. Dies ist keine fachliche Freigabe.", "No questions recorded. This does not mean expert approval.")));
        for (DecisionQuestion q : in.questions()) {
            out.add(new Block(3, q.id() + " — " + q.wording()));
            out.add(new Block(0, "Status: " + q.state() + "\n" + t(in, "Geltungsbereich: ", "Scope: ") + q.key().scope()
                    + "\n" + t(in, "Antwortmöglichkeiten: ", "Answer options: ") + q.answerSchema().options()
                    + "\n" + t(in, "Antworttyp: ", "Answer type: ") + q.answerSchema().kind()
                    + "\n" + t(in, "Einheit / Minimum / Maximum: ", "Unit / minimum / maximum: ")
                    + Objects.toString(q.answerSchema().unit(), "—") + " / " + q.answerSchema().minimum() + " / " + q.answerSchema().maximum()
                    + "\n" + t(in, "Auswirkungen: ", "Consequences: ") + Objects.toString(q.consequences(), "")
                    + "\n" + t(in, "Vorausgesetzte Fragen: ", "Prerequisite questions: ") + q.prerequisites()
                    + "\n" + t(in, "Abhängige Fragen: ", "Dependent questions: ") + q.dependentQuestionIds()
                    + "\n" + t(in, "Aussagen: ", "Statements: ") + q.affectedStatementIds() + "\nAliases: " + q.aliases()));
            for (DecisionQuestion.Discovery discovery : q.discoveries()) discovery(out, in, discovery);
            for (DecisionQuestion.Origin origin : q.origins()) {
                out.add(new Block(0, t(in, "Ursprüngliche Frage: ", "Original question: ") + origin.id() + " — " + origin.wording()));
                for (DecisionQuestion.Discovery discovery : origin.discoveries()) discovery(out, in, discovery);
            }
            for (DecisionQuestion.SourceResolution resolution : q.sourceResolutions()) {
                out.add(new Block(0, t(in, "Aus dem Original abgeleitete Antwort: ", "Answer derived from original: ")
                        + resolution.values() + "\n" + resolution.rationale()));
                spans(out, in, resolution.sourceSpans());
            }
        }
        out.add(new Block(2, t(in, "Gespeicherte Antworten und Entscheidungsverlauf", "Recorded answers and decision history")));
        var active = DecisionAnswer.active(in.answers());
        for (DecisionAnswer answer : in.answers()) {
            out.add(new Block(3, answer.questionId() + " — " + (active.contains(answer)
                    ? t(in, "in diesem Stand wirksam", "active in this record") : t(in, "ersetzt", "superseded"))));
            out.add(new Block(0, "ID: " + answer.id() + "\nStatus: " + answer.state()
                    + "\n" + t(in, "Werte: ", "Values: ") + answer.values()
                    + "\n" + t(in, "Andere Antwort: ", "Other: ") + Objects.toString(answer.otherText(), "")
                    + "\n" + t(in, "Autor: ", "Author: ") + answer.author()
                    + "\n" + t(in, "Zeitpunkt: ", "Time: ") + answer.occurredAt()
                    + "\n" + t(in, "Begründung: ", "Rationale: ") + answer.rationale()
                    + "\nDisposition: " + answer.disposition() + "\n" + t(in, "Ersetzt: ", "Supersedes: ") + answer.supersedes()));
        }
        out.add(new Block(2, t(in, "Prüfhinweise", "Validation findings")));
        in.validation().findings().forEach(f -> {
            out.add(new Block(0, f.kind() + " / " + f.code() + "\n" + f.message()
                    + "\n" + t(in, "Aussagen: ", "Statements: ") + f.statementIds()));
            spans(out, in, f.sourceSpans());
        });
        section(out, t(in, "Vollständige strukturierte Evidenz", "Complete structured evidence"), in.evidenceJson());
        return List.copyOf(out);
    }

    private static void discovery(List<Block> out, Input in, DecisionQuestion.Discovery discovery) {
        out.add(new Block(0, t(in, "Entdeckt bei: ", "Discovered at: ") + discovery.location()
                + "\n" + t(in, "Kontext: ", "Context: ") + discovery.context()
                + "\n" + t(in, "Begründung: ", "Rationale: ") + discovery.rationale()
                + "\n" + t(in, "Knoten: ", "Nodes: ") + discovery.nodeIds()
                + "\n" + t(in, "Verbindungen: ", "Edges: ") + discovery.edgeIds()));
        spans(out, in, discovery.sourceSpans());
    }
    private static void spans(List<Block> out, Input in, List<Statement.SourceSpan> spans) {
        for (var span : spans) out.add(new Block(0, t(in, "Originalstelle (UTF-16): ", "Source span (UTF-16): ")
                + span.start() + "–" + span.end() + "\n" + span.exactText()));
    }
    private static void section(List<Block> out, String title, String text) { out.add(new Block(2, title)); out.add(new Block(0, text)); }
    private static String title(Input in) {
        return in.adoptionReceipt() ? t(in, "Neuformulierung — Übernahmebeleg", "Reformulation — adoption receipt")
                : t(in, "Neuformulierungsangebot — gespeicherte Revision", "Reformulation offer — saved revision");
    }
    private static String t(Input in, String de, String en) { return in.language().equals("de") ? de : en; }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String heading(String text) {
        return escape(text).replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")
                .replace("[", "\\[").replace("]", "\\]").replace("#", "\\#").replace("\r", " ").replace("\n", " ");
    }
    /** User-provided backticks must never terminate a literal field block. */
    private static String fenced(String text) {
        int longest = 0, run = 0;
        for (int i = 0; i < text.length(); i++) { run = text.charAt(i) == '`' ? run + 1 : 0; longest = Math.max(longest, run); }
        String delimiter = "`".repeat(Math.max(3, longest + 1));
        return delimiter + "text\n" + text + "\n" + delimiter;
    }
}
