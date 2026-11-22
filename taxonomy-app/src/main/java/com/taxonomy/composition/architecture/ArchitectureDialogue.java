package com.taxonomy.composition.architecture;

import com.taxonomy.composition.architecture.ArchitectureSourceFocusDtos.ResolvedModel;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.dsl.model.ArchitectureEvidence;
import java.util.*;
import java.util.regex.Pattern;

/** The provider sees a bounded selection of facts from one authorized architecture version. */
final class ArchitectureDialogue {
    record Turn(String question, String answer) {
        Turn { question = bounded(question, 1000); answer = bounded(answer, 3000); }
    }
    record Request(Context context, ArchitectureSourceFocusDtos.Source source, String sourceHash, String question,
                   List<Turn> history, String language) {
        Request {
            if ((context == null) == (source == null)) throw new IllegalArgumentException("Select one architecture source");
            question = bounded(question, 1000);
            history = history == null ? List.of() : List.copyOf(history);
            if (history.size() > 4 || (sourceHash != null && !sourceHash.matches("[0-9a-f]{64}"))
                    || (!history.isEmpty() && sourceHash == null)) throw new IllegalArgumentException("Invalid conversation context");
            if (!Set.of("de", "en").contains(Objects.toString(language, ""))) throw new IllegalArgumentException("Unsupported language");
        }
    }
    record Fact(String source, String text) {}
    record Statement(String text, List<Fact> evidence) {}
    record Answer(Context context, ArchitectureSourceFocusDtos.Source source, String sourceHash, String provider,
                  String outcome, int suppliedFacts, int totalFacts, List<Statement> statements) {}

    private static String bounded(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max || text.codePoints().anyMatch(c ->
                Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw new IllegalArgumentException("Invalid dialogue text");
        return text.strip();
    }

    static List<Fact> facts(ResolvedModel source, Request request) {
        var model = source.model();
        var facts = new ArrayList<Fact>();
        var terms = words(request.question());
        var previous = new LinkedHashSet<String>();
        for (var turn : request.history()) { previous.addAll(words(turn.question())); previous.addAll(words(turn.answer())); }
        var identifiers = new LinkedHashSet<>(terms);
        identifiers.addAll(previous);
        identifiers.removeIf(term -> !IDENTIFIER.matcher(term).matches());
        // Always retain whole-source counts even when the provider sees only selected details.
        facts.add(new Fact("model", "elements=" + model.getElements().size() + ", relations=" + model.getRelations().size()));
        if (source.original() != null && !source.original().isBlank()) chunks(facts, "analyzed requirement", source.original());
        var report = source.report();
        if (report != null) {
            facts.add(new Fact("relation search", "unfinished searches=" + report.result().unfinished().size()
                    + ", search exhausted under configured policy=" + report.isSearchExhausted()));
            for (var edge : report.result().edges()) {
                var decision = edge.evidence();
                String relation = join(edge.sourceId(), edge.type(), edge.targetId());
                chunks(facts, "analysis decision " + relation, join("necessity=" + decision.necessity(),
                        decision.condition(), decision.alternativeGroup(), decision.rationale(), decision.quote()));
            }
            report.result().unfinished().forEach(open -> chunks(facts, "open analysis question " + open.sourceId(),
                    join(open.type(), open.reason(), open.question())));
        }
        model.getRequirements().forEach(r -> chunks(facts, "requirement " + r.getId(), join(r.getTitle(), r.getText())));
        model.getRelations().forEach(r -> facts.add(new Fact("modeled relation", join(r.getSourceId(), r.getRelationType(), r.getTargetId(), r.getStatus()))));
        model.getElements().forEach(e -> chunks(facts, "element " + e.getId(), join(e.getType(), e.getTitle(), e.getDescription(),
                e.getExtensions().get("x-responsible-organization-id"))));
        model.getMappings().forEach(m -> chunks(facts, "requirement mapping", join(m.getRequirementId(), m.getElementId(), m.getSource())));
        model.getEvidence().forEach(e -> addEvidence(facts, e, identifiers));
        model.getOrganizations().forEach(o -> chunks(facts, "organization " + o.id(), join(o.title(), o.parentId())));
        model.getCatalogueResponsibilities().forEach(r -> facts.add(new Fact("catalogue responsibility", join(r.code(), r.organizationId()))));
        model.getViews().forEach(v -> chunks(facts, "view " + v.getId(), join(v.getTitle(), String.join(", ", v.getIncludes()))));
        record Ranked(Fact fact, int score) {}
        var ranked = facts.subList(1, facts.size()).stream().map(f -> {
            var tokens = words(f.source() + " " + f.text());
            int score = (int) tokens.stream().filter(terms::contains).count() * 8 + (int) tokens.stream().filter(previous::contains).count();
            // A named clause or endpoint outranks incidental word overlap elsewhere in a large model.
            for (String token : tokens) {
                if (identifiers.contains(token)) score += terms.contains(token) ? 1000 : 40;
            }
            if (f.source().startsWith("evidence ") && tokens.stream().anyMatch(identifiers::contains)) score += 4;
            return new Ranked(f, score);
        }).sorted(Comparator.comparingInt(Ranked::score).reversed()).map(Ranked::fact).toList();
        var result = new ArrayList<Fact>();
        result.add(facts.getFirst()); result.addAll(ranked);
        return result;
    }
    private static final Pattern IDENTIFIER = Pattern.compile("(?=.*[0-9])[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)+");
    private static void addEvidence(List<Fact> facts, ArchitectureEvidence evidence, Set<String> identifiers) {
        var ext = evidence.getExtensions();
        var fields = new LinkedHashMap<String,String>();
        fields.put("view", ext.get("x-analysis-view-id"));
        fields.put("summary", evidence.getSummary());
        for (String name : List.of("origin", "direction", "outcome", "necessity", "assessed-id", "assessed-contribution",
                "assessed-quote", "assessed-condition", "candidate-id", "candidate-contribution", "candidate-quote",
                "candidate-condition", "alternative-group", "question"))
            fields.put(name, ext.get("x-analysis-" + name));
        fields.values().removeIf(value -> value == null || value.isBlank());
        String relation = String.join(" ", Arrays.stream(new String[] { evidence.getForRelationSource(),
                evidence.getForRelationType(), evidence.getForRelationTarget() }).filter(Objects::nonNull).toList());
        String snapshot = ext.get("x-analysis-snapshot-id");
        String source = join("evidence " + evidence.getId(), relation, snapshot == null ? null : "snapshot " + snapshot);
        if (fields.isEmpty()) {
            if (!relation.isBlank()) facts.add(new Fact(source, relation));
            return;
        }
        String content = String.join("\n", fields.entrySet().stream().map(f -> f.getKey() + ": " + f.getValue()).toList());
        if (content.length() <= 1800) {
            facts.add(new Fact(source, content));
            return;
        }
        // Keep the source identity and the clause references when the full record exceeds a single citation.
        var matched = words(content);
        matched.retainAll(identifiers);
        String scoped = join(source, String.join(", ", matched.stream().sorted().limit(4).toList()));
        fields.forEach((field, value) -> chunks(facts, scoped + " · " + field, value));
    }
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
    private static Set<String> words(String text) {
        var words = new HashSet<String>();
        WORD.matcher(text.toLowerCase(Locale.ROOT)).results().map(m -> m.group()).filter(s -> s.length() > 2).forEach(words::add);
        return words;
    }
    private static String join(String... values) {
        return String.join(" · ", Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList());
    }
    private static void chunks(List<Fact> facts, String source, String text) {
        // Split at a Unicode boundary, retaining every part for question-aware selection.
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 360);
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
            var part = text.substring(start, end);
            if (!part.isBlank()) facts.add(new Fact(source, part));
            start = end;
        }
    }
}
