package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import java.util.*;

/** Frozen-source adoption only. Nothing here reads renderer or review-overlay state. */
final class AnalysisSnapshotAdoption {
    static final String RELATIONS = "x-analysis-relation-members";
    private AnalysisSnapshotAdoption() {}

    static String apply(ArchitectureDslCommands commands, String source, AdoptAnalysisSnapshot adoption) {
        if (adoption.elements().isEmpty() || adoption.elements().size() > 2000 || adoption.relations().size() > 10000)
            throw problem("ITEM_LIMIT", "Snapshot must contain 1–2000 elements and at most 10000 relations");
        if (adoption.viewId() == null || !adoption.viewId().matches("analysis-[A-Za-z0-9.-]{1,100}")
                || adoption.graphFingerprint() == null || !adoption.graphFingerprint().matches("[a-f0-9]{64}"))
            throw problem("INVALID_VALUE", "Snapshot view identity and graph fingerprint are required");
        if (adoption.provenance().size() > 32) throw problem("ITEM_LIMIT", "Too much source metadata");
        adoption.provenance().forEach((key, value) -> {
            if (!key.matches("x-analysis-[a-z-]{1,60}") || value.length() > 8000 || value.chars().anyMatch(Character::isISOControl))
                throw problem("INVALID_VALUE", "Source metadata must be bounded analysis provenance");
        });
        // Validate strings even when an existing edited object will be reused.
        if (adoption.title() == null || adoption.title().isBlank() || adoption.title().length() > 8000
                || adoption.description() == null || adoption.description().length() > 8000)
            throw problem("INVALID_VALUE", "Snapshot title and description must be bounded");
        Map<String, SnapshotElement> elements = new TreeMap<>();
        for (var element : adoption.elements()) {
            if (element.id() == null || element.type() == null || element.title() == null || element.description() == null)
                throw problem("INVALID_VALUE", "Snapshot element identity, type, title and description must be present");
            if (elements.putIfAbsent(element.id(), element) != null)
                throw problem("AMBIGUOUS_SNAPSHOT_IDENTITY", "Duplicate snapshot element identity");
            if (!TaxonomyRootTypes.TYPE_TO_ROOT.containsKey(element.type()))
                throw problem("UNKNOWN_TYPE", "Unsupported snapshot element type");
            commands.apply("", new CreateArchitectureElement(element.id(), element.type(),
                    Map.of("title", element.title(), "description", element.description())));
        }
        Set<String> relations = new TreeSet<>();
        for (var relation : adoption.relations()) {
            if (!elements.containsKey(relation.sourceId()) || !elements.containsKey(relation.targetId()))
                throw problem("MISSING_SNAPSHOT_ENDPOINT", "Snapshot relation references a missing snapshot endpoint");
            if (!relations.add(relation.id())) throw problem("AMBIGUOUS_SNAPSHOT_IDENTITY", "Duplicate snapshot relation identity");
        }
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(source);
        var existingView = blocks.get("view:" + adoption.viewId());
        if (existingView != null) {
            if (!adoption.graphFingerprint().equals(existingView.property("x-analysis-graph-fingerprint"))
                    || adoption.provenance().entrySet().stream().anyMatch(entry -> !entry.getValue().equals(existingView.property(entry.getKey()))))
                throw problem("SNAPSHOT_IDENTITY_CONFLICT", "The exact source identity already has a different frozen graph");
            return source; // Later ordinary edits remain authoritative; reopening never reapplies the graph.
        }
        String next = source;
        for (var element : elements.values()) {
            var existing = blocks.get("element:" + element.id());
            if (existing != null) {
                var header = existing.getHeaderTokens();
                if (header.size() != 3 || !"type".equals(header.get(1)) || !element.type().equals(header.get(2)))
                    throw problem("SNAPSHOT_TYPE_CONFLICT", "Existing element identity has a different type: " + element.id());
                if (existing.getProperties().stream().map(PropertyAst::key).distinct().count() != existing.getProperties().size())
                    throw problem("DUPLICATE_PROPERTY", "Existing element has ambiguous properties");
            } else next = commands.apply(next, new CreateArchitectureElement(element.id(), element.type(),
                    Map.of("title", element.title(), "description", element.description()))).dsl();
        }
        for (var relation : adoption.relations().stream().sorted(Comparator.comparing(RelationKey::id)).toList()) {
            ArchitectureDslCommands.validateRelation(ArchitectureSemanticPatch.index(next), relation);
            if (!blocks.containsKey("relation:" + relation.id()))
                next = commands.apply(next, new CreateArchitectureRelation(relation, "proposed")).dsl();
        }
        Map<String, String> properties = new TreeMap<>(adoption.provenance());
        properties.put("x-analysis-graph-fingerprint", adoption.graphFingerprint());
        properties.put(RELATIONS, String.join("\n", relations));
        List<PropertyAst> values = new ArrayList<>();
        values.add(new PropertyAst("title", adoption.title(), null));
        values.add(new PropertyAst("description", adoption.description(), null));
        elements.keySet().forEach(id -> values.add(new PropertyAst("include", id, null)));
        properties.forEach((key, value) -> values.add(new PropertyAst(key, value, null)));
        return ArchitectureSemanticPatch.replace(next, null,
                new BlockAst("view", List.of(adoption.viewId()), values, List.of(), properties, null));
    }

    static boolean isAdoptionView(ArchitectureSemanticPatch.BlockChange change) {
        if (!change.id().startsWith("view:analysis-")) return false;
        String source = change.after() == null ? change.before() : change.after();
        var view = ArchitectureSemanticPatch.index(source).get(change.id());
        return view != null && view.property("x-analysis-graph-fingerprint") != null;
    }
    static Set<String> relationMembers(BlockAst view) {
        String value = view.property(RELATIONS);
        return value == null || value.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(value.split("\n")));
    }
    private static ArchitectureDslCommands.CommandProblem problem(String code, String message) {
        return new ArchitectureDslCommands.CommandProblem(code, "snapshot", message, List.of());
    }
}
