package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.DecisionAnswer;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ReconciliationInput;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.Section;
import com.taxonomy.reformulation.Statement;
import com.taxonomy.reformulation.ValidationReport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Exact round evidence stays frozen while provider-sized views are reviewed independently. */
final class BoundedReconciliation {
    private static final String ENCODING = "bounded-reconciliation-v1";
    private static final int MAX_DETAIL_VIEWS = 16;

    enum Scope { OVERVIEW, DETAIL, ARCHIVE }
    record View(String encoding, Scope scope, ReconciliationInput input) {
        View(Scope scope, ReconciliationInput input) { this(ENCODING, scope, input); }
    }
    record Plan(List<View> views, boolean hasOverview) {
        ValidationReport.Finding coverageFinding() {
            return new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW, "BOUNDED_RECONCILIATION",
                    hasOverview
                            ? "The joint review compared the requirement and current proposals. Taxonomy, discovery and archival evidence were reviewed in separate views, so interactions between those views still need review."
                            : "The joint review exceeded the provider budget. Each view was reviewed separately, so comparisons across views still need review.",
                    List.of(), List.of());
        }
    }

    private enum Kind { SECTION, STATEMENT, QUESTION, EDGE, ANSWER }
    private record Item(Kind kind, String id) {}

    private final ObjectMapper json;

    BoundedReconciliation(ObjectMapper json) { this.json = json; }

    Plan plan(ReconciliationInput input, Predicate<View> fits) {
        var overview = new View(Scope.OVERVIEW, input);
        boolean hasOverview = fits.test(overview);
        var views = new LinkedHashSet<View>();
        if (hasOverview) views.add(overview);

        var items = items(input);
        var selected = new LinkedHashSet<Item>();
        View accepted = null;
        // Preflight the complete plan before sending any of its prompts. An oversized single
        // record fails explicitly rather than dropping its wording or an unresolved decision.
        for (var item : items) {
            selected.add(item);
            var next = view(input, selected);
            if (!fits.test(next)) {
                if (accepted == null) throw tooLarge();
                addDetail(views, accepted, hasOverview);
                selected.clear();
                selected.add(item);
                next = view(input, selected);
                if (!fits.test(next)) throw tooLarge();
            }
            accepted = next;
        }
        if (accepted == null) {
            accepted = view(input, Set.of());
            if (!fits.test(accepted)) throw tooLarge();
        }
        addDetail(views, accepted, hasOverview);
        appendArchiveViews(input, fits, views, hasOverview);
        return new Plan(List.copyOf(views), hasOverview);
    }

    private void appendArchiveViews(ReconciliationInput input, Predicate<View> fits, Set<View> views, boolean hasOverview) {
        String frozen = input.baseline().frozenContext().get("inheritedDecisionContext");
        if (frozen == null) return;
        var entries = json.readTree(frozen);
        if (!entries.isArray()) throw new IllegalArgumentException("Malformed inherited decision context");
        var selected = json.createArrayNode();
        View accepted = null;
        int entryIndex = 0;
        int firstEntryIndex = 0;
        for (var entry : entries) {
            // Keep historical questions, statements, answers and review metadata together.
            selected.add(entry);
            var next = archiveView(input, selected, firstEntryIndex);
            if (!fits.test(next)) {
                if (accepted == null) throw tooLarge();
                addDetail(views, accepted, hasOverview);
                selected = json.createArrayNode().add(entry);
                firstEntryIndex = entryIndex;
                next = archiveView(input, selected, firstEntryIndex);
                if (!fits.test(next)) throw tooLarge();
            }
            accepted = next;
            entryIndex++;
        }
        if (accepted != null) addDetail(views, accepted, hasOverview);
    }

    private View archiveView(ReconciliationInput input, JsonNode entries, int firstEntryIndex) {
        var baseline = input.baseline();
        var context = new TreeMap<>(baseline.frozenContext());
        context.put("inheritedDecisionContext", json.writeValueAsString(entries));
        var archive = new ReformulationBaseline(baseline.scope(), baseline.sourceVersionId(), baseline.originalText(),
                baseline.originalTextHash(), baseline.snapshotId(), baseline.snapshotPayload(), context,
                baseline.language(), baseline.algorithmVersion());
        // Repeated entries at different original positions are separate evidence in the plan.
        return new View(ENCODING + "-archive-" + firstEntryIndex, Scope.ARCHIVE,
                new ReconciliationInput(archive, input.round(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of()));
    }

    private static void addDetail(Set<View> views, View detail, boolean hasOverview) {
        if (views.add(detail) && views.size() - (hasOverview ? 1 : 0) > MAX_DETAIL_VIEWS) throw tooLarge();
    }

    private static Set<Item> items(ReconciliationInput input) {
        var items = new LinkedHashSet<Item>();
        var knownStatements = new LinkedHashSet<String>();
        input.statements().forEach(s -> knownStatements.add(s.id()));
        var aliases = new LinkedHashSet<String>();
        input.questions().forEach(q -> aliases.addAll(q.referenceIds()));
        // Section order is already frozen. Place its evidence next to its first use, then
        // include any records without a section and all directed boundaries.
        for (var section : input.sections()) {
            items.add(new Item(Kind.SECTION, section.id()));
            section.statementIds().stream().filter(knownStatements::contains).forEach(id -> items.add(new Item(Kind.STATEMENT, id)));
            section.questionIds().stream().filter(aliases::contains).forEach(id -> items.add(new Item(Kind.QUESTION, id)));
        }
        input.statements().forEach(s -> items.add(new Item(Kind.STATEMENT, s.id())));
        input.questions().forEach(q -> items.add(new Item(Kind.QUESTION, q.id())));
        input.boundaryEdges().keySet().stream().sorted().forEach(id -> items.add(new Item(Kind.EDGE, id)));
        input.answers().stream().filter(a -> !aliases.contains(a.questionId()))
                .forEach(a -> items.add(new Item(Kind.ANSWER, a.id())));
        return items;
    }

    private View view(ReconciliationInput input, Set<Item> items) {
        var questionIds = new LinkedHashSet<String>();
        var statementIds = new LinkedHashSet<String>();
        var sectionIds = new LinkedHashSet<String>();
        var edgeIds = new LinkedHashSet<String>();
        var questions = input.questions().stream().filter(q -> q.referenceIds().stream()
                .anyMatch(id -> items.contains(new Item(Kind.QUESTION, id)))).toList();
        questions.forEach(q -> {
            questionIds.addAll(q.referenceIds());
            statementIds.addAll(q.affectedStatementIds());
            q.discoveries().forEach(d -> edgeIds.addAll(d.edgeIds()));
        });
        var statements = input.statements().stream().filter(s -> statementIds.contains(s.id())
                || items.contains(new Item(Kind.STATEMENT, s.id()))).toList();
        statements.forEach(s -> edgeIds.addAll(s.architectureLinks()));
        statementIds.clear();
        statements.forEach(s -> statementIds.add(s.id()));
        var edges = new TreeMap<String, String>();
        input.boundaryEdges().forEach((id, evidence) -> {
            if (edgeIds.contains(id) || items.contains(new Item(Kind.EDGE, id))) {
                edges.put(id, evidence);
                var edge = json.readTree(evidence);
                sectionIds.add(edge.path("sourceCode").asText());
                sectionIds.add(edge.path("targetCode").asText());
            }
        });
        List<Section> sections = input.sections().stream().filter(s -> sectionIds.contains(s.id())
                || items.contains(new Item(Kind.SECTION, s.id()))
                || s.questionIds().stream().anyMatch(questionIds::contains)).toList();
        List<DecisionAnswer> answers = input.answers().stream().filter(a -> questionIds.contains(a.questionId())
                || items.contains(new Item(Kind.ANSWER, a.id()))).toList();
        var shared = new TreeMap<String, List<String>>();
        input.sharedInformation().forEach((id, members) -> {
            if (members.stream().anyMatch(statementIds::contains)) shared.put(id, members);
        });
        return new View(Scope.DETAIL, new ReconciliationInput(input.baseline(), input.round(), sections,
                statements, questions, answers, edges, shared, input.globalConstraintIds()));
    }

    private static IllegalStateException tooLarge() {
        return new IllegalStateException("INPUT_TOO_LARGE_FOR_PROVIDER: bounded reconciliation cannot fit complete evidence views");
    }
}
