package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
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
            commands.requireType(element.type());
            commands.apply("", new CreateArchitectureElement(element.id(), element.type(),
                    Map.of("title", element.title(), "description", element.description())));
        }
        Set<String> relations = new TreeSet<>();
        for (var relation : adoption.relations()) {
            if (!elements.containsKey(relation.sourceId()) || !elements.containsKey(relation.targetId()))
                throw problem("MISSING_SNAPSHOT_ENDPOINT", "Snapshot relation references a missing snapshot endpoint");
            if (!relations.add(relation.id())) throw problem("AMBIGUOUS_SNAPSHOT_IDENTITY", "Duplicate snapshot relation identity");
        }
        Set<RelationKey> directed = Set.copyOf(adoption.relations());
        Map<RelationKey, Map<String, String>> recorded = new HashMap<>();
        for (var observation : adoption.evidence()) {
            if (!directed.contains(observation.relation()))
                throw SnapshotEvidence.invalid("Observation does not belong to a directed snapshot relation");
            recorded.put(observation.relation(), observation.relationProperties());
        }
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(source);
        var existingView = blocks.get("view:" + adoption.viewId());
        if (existingView != null) {
            String priorEvidence = existingView.property(SnapshotEvidence.FINGERPRINT);
            if (!adoption.graphFingerprint().equals(existingView.property("x-analysis-graph-fingerprint"))
                    || adoption.provenance().entrySet().stream()
                            .filter(entry -> !entry.getKey().equals(SnapshotEvidence.FINGERPRINT))
                            .anyMatch(entry -> !entry.getValue().equals(existingView.property(entry.getKey())))
                    || priorEvidence != null && !priorEvidence.equals(adoption.provenance().get(SnapshotEvidence.FINGERPRINT)))
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
            } else {
                // The element inputs were validated above. Mark only the generated value we actually install.
                var properties = new LinkedHashMap<String, String>();
                properties.put("title", element.title());
                properties.put("description", element.description());
                if (!element.description().isBlank())
                    properties.put(SnapshotEvidence.GENERATED_DESCRIPTION_HASH, SnapshotEvidence.digest(element.description()));
                next = ArchitectureSemanticPatch.replace(next, null,
                        ArchitectureDslCommands.block("element", List.of(element.id(), "type", element.type()), properties, null));
            }
            if (element.responsibility() != null) {
                // Earlier inserts can move source offsets. Use the current block for the exact replacement.
                var current = ArchitectureSemanticPatch.index(next).get("element:" + element.id());
                next = commands.retainSnapshotResponsibility(next, current, element.responsibility());
            }
        }
        for (var relation : adoption.relations().stream().sorted(Comparator.comparing(RelationKey::id)).toList()) {
            var current = ArchitectureSemanticPatch.index(next);
            if (blocks.containsKey("relation:" + relation.id())) commands.validateRelation(current, relation);
            else next = commands.createRelation(next, current, relation, "proposed", recorded.getOrDefault(relation, Map.of()));
        }
        // Content-addressed observations from this source are additive even on an existing reviewed relation.
        var observations = new TreeMap<String, Map<String, String>>();
        for (var observation : adoption.evidence()) {
            var fields = observation.properties(adoption.viewId(), adoption.provenance().get(SnapshotEvidence.SNAPSHOT));
            String id = SnapshotEvidence.id(fields);
            if (blocks.containsKey("evidence:" + id))
                throw problem("SNAPSHOT_IDENTITY_CONFLICT", "A source evidence identity already exists without its view");
            observations.put(id, fields);
        }
        if (!observations.isEmpty()) {
            var appended = new StringBuilder(next);
            if (!next.isEmpty() && !next.endsWith("\n")) appended.append('\n');
            observations.forEach((id, fields) -> appended.append(ArchitectureSemanticPatch.replace("", null,
                    ArchitectureDslCommands.block("evidence", List.of(id), fields, null))));
            next = appended.toString();
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
    /** Only an entire content-addressed evidence set created or removed together with its owning view is invertible. */
    static Set<String> ownedEvidence(List<ArchitectureSemanticPatch.BlockChange> changes) {
        Map<String, ArchitectureSemanticPatch.BlockChange> views = new HashMap<>();
        for (var change : changes) if (isAdoptionView(change)) views.put(change.id(), change);
        Map<String, SortedSet<String>> byView = new HashMap<>();
        for (var change : changes) {
            if (!change.id().startsWith("evidence:analysis-evidence-") || change.before() != null && change.after() != null) continue;
            String source = change.after() == null ? change.before() : change.after();
            var block = ArchitectureSemanticPatch.index(source).get(change.id());
            String owner = "view:" + block.property(SnapshotEvidence.VIEW);
            var viewChange = views.get(owner);
            if (viewChange == null || (viewChange.before() == null) != (change.before() == null)
                    || (viewChange.after() == null) != (change.after() == null)) continue;
            var fields = new TreeMap<String, String>();
            for (var property : block.getProperties()) fields.put(property.key(), property.value());
            String id = SnapshotEvidence.id(fields);
            if (fields.size() != block.getProperties().size() || !change.id().equals("evidence:" + id)) continue;
            byView.computeIfAbsent(owner, ignored -> new TreeSet<>()).add(id);
        }
        Set<String> owned = new HashSet<>();
        byView.forEach((owner, ids) -> {
            var change = views.get(owner);
            var view = ArchitectureSemanticPatch.index(change.after() == null ? change.before() : change.after()).get(owner);
            if (SnapshotEvidence.fingerprint(ids).equals(view.property(SnapshotEvidence.FINGERPRINT)))
                ids.forEach(id -> owned.add("evidence:" + id));
        });
        return owned;
    }

    static Set<String> relationMembers(BlockAst view) {
        String value = view.property(RELATIONS);
        return value == null || value.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(value.split("\n")));
    }
    private static ArchitectureDslCommands.CommandProblem problem(String code, String message) {
        return new ArchitectureDslCommands.CommandProblem(code, "snapshot", message, List.of());
    }
}
