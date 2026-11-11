package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.validation.RelationMetamodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The commission is an authored canonical source in the selected architecture's version history. */
public final class ArchitectureBrief {
    public static final String ID = "architecture-brief";
    private static final String ALTERNATE_ID = PlanningBlocks.identity("architecture-brief-", "canonical-architecture-brief");
    public static final String VERSION = "1";
    public static final String RULE = "exclude-relation-v1";
    private static final String PREFIX = "x-brief.";
    private static final String FIELD = PREFIX + "field.";
    private static final String REF = PREFIX + "ref.";
    public static final List<String> FIELDS = List.of("objective", "decision", "stakeholders", "boundary", "stage",
            "referenceArchitecture", "views", "deliverables", "context");
    private static final Set<String> LOCATOR = Set.of("identifier", "edition", "section");
    private static final Set<String> APPLICATION = Set.of("role", "scope", "appliesTo", "purpose", "derivation", "rule", "relation");
    private ArchitectureBrief() {}

    public record Reference(String id, Map<String, String> values, String sourceId, String sourceVersionId, String sourceFragmentId) {
        public Reference { values = Map.copyOf(values); }
    }

    public record Snapshot(boolean present, String version, Map<String, String> fields, List<Reference> references, String problem) {
        public Snapshot { fields = Map.copyOf(fields); references = List.copyOf(references); }

        /** Only explicit rules with a binding source and whole-architecture scope affect generation. */
        public Set<String> excludedRelationTypes() {
            if (problem != null) throw new IllegalArgumentException(problem);
            var excluded = new TreeSet<String>();
            for (var reference : references) {
                var values = reference.values();
                if (RULE.equals(values.get("rule"))) excluded.add(values.get("relation"));
            }
            return Set.copyOf(excluded);
        }
    }

    public static Snapshot read(CanonicalArchitectureModel model) {
        var sources = model.getSources().stream().filter(s -> reservedId(s.getId()) && ID.equals(s.getSourceType())).toList();
        if (sources.isEmpty()) return new Snapshot(false, null, Map.of(), List.of(), null);
        if (sources.size() != 1) return new Snapshot(true, null, Map.of(), List.of(), "AMBIGUOUS_ARCHITECTURE_BRIEF");
        var properties = sources.getFirst().getExtensions();
        var version = properties.get(PREFIX + "version");
        if (!VERSION.equals(version)) return new Snapshot(true, version, Map.of(), List.of(), "UNSUPPORTED_ARCHITECTURE_BRIEF");
        try {
            return decode(model, properties);
        } catch (IllegalArgumentException invalid) {
            return new Snapshot(true, version, Map.of(), List.of(), "INVALID_ARCHITECTURE_BRIEF");
        }
    }

    private static Snapshot decode(CanonicalArchitectureModel model, Map<String, String> properties) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Map<String, String>> groups = new TreeMap<>();
        for (var entry : properties.entrySet()) {
            var key = entry.getKey();
            if (key.startsWith(FIELD)) fields.put(key.substring(FIELD.length()), entry.getValue());
            if (key.startsWith(REF)) {
                var path = key.substring(REF.length()).split("\\.", 2);
                if (path.length != 2) throw new IllegalArgumentException("Malformed architecture reference");
                PlanningEntry.token(path[0], "reference id");
                groups.computeIfAbsent(path[0], id -> new TreeMap<>()).put(path[1], entry.getValue());
            }
        }
        validateFields(fields);
        if (groups.size() > PlanningInformation.MAX_ENTRIES) throw new IllegalArgumentException("Too many architecture references");
        List<Reference> references = new ArrayList<>();
        for (var group : groups.entrySet()) {
            var stored = group.getValue();
            var values = new TreeMap<>(StandardReferenceProfile.describe(model, stored.get("source"),
                    stored.get("sourceVersion"), stored.get("sourceFragment"), stored.get("x-unversioned-section")));
            for (String key : APPLICATION) if (stored.containsKey(key)) values.put(key, stored.get(key));
            validateReference(values);
            references.add(new Reference(group.getKey(), values, stored.get("source"), stored.get("sourceVersion"), stored.get("sourceFragment")));
        }
        return new Snapshot(true, VERSION, fields, references, null);
    }

    public static String store(String dsl, Map<String, String> fields) {
        validateFields(fields);
        var previous = read(new ArchitectureDslCommands().model(dsl));
        if (previous.problem() != null) throw new IllegalArgumentException(previous.problem());
        Map<String, String> updated = new LinkedHashMap<>();
        fields.forEach((key, value) -> updated.put(FIELD + key, value));
        return replace(dsl, FIELD, updated, true);
    }

    public static String putReference(String dsl, String id, Map<String, String> values) {
        PlanningEntry.token(id, "reference id");
        validateReference(values);
        var current = read(new ArchitectureDslCommands().model(dsl));
        if (!current.present() || current.problem() != null) throw new IllegalArgumentException("A supported architecture brief is required");
        if (current.references().size() == PlanningInformation.MAX_ENTRIES
                && current.references().stream().noneMatch(r -> r.id().equals(id)))
            throw new IllegalArgumentException("Too many architecture references");
        Map<String, String> locator = new LinkedHashMap<>(values);
        locator.keySet().retainAll(LOCATOR);
        var canonical = StandardReferenceProfile.locate(dsl, locator);
        if (reservedId(canonical.values().get("source")) && ID.equals(ArchitectureSemanticPatch.index(canonical.dsl())
                .get("source:" + canonical.values().get("source")).property("type")))
            throw new IllegalArgumentException("A brief cannot cite itself");
        var prefix = REF + id + ".";
        var updated = new LinkedHashMap<String, String>();
        canonical.values().forEach((key, value) -> updated.put(prefix + key, value));
        for (String key : APPLICATION) if (values.containsKey(key)) updated.put(prefix + key, values.get(key));
        return replace(canonical.dsl(), prefix, updated, false);
    }

    public static String removeReference(String dsl, String id) {
        PlanningEntry.token(id, "reference id");
        if (read(new ArchitectureDslCommands().model(dsl)).references().stream().noneMatch(r -> r.id().equals(id)))
            throw new IllegalArgumentException("Unknown architecture reference");
        return replace(dsl, REF + id + ".", Map.of(), false);
    }

    private static String replace(String dsl, String prefix, Map<String, String> updated, boolean create) {
        var blocks = ArchitectureSemanticPatch.index(dsl);
        var preferred = blocks.get("source:" + ID);
        var alternate = blocks.get("source:" + ALTERNATE_ID);
        var id = alternate != null && ID.equals(alternate.property("type"))
                || preferred != null && !ID.equals(preferred.property("type")) ? ALTERNATE_ID : ID;
        var before = blocks.get("source:" + id);
        Map<String, String> properties = new LinkedHashMap<>();
        if (before == null) {
            if (!create) throw new IllegalArgumentException("Architecture brief missing");
            properties.put("type", ID);
            properties.put("title", "Architecture brief");
            properties.put(PREFIX + "version", VERSION);
        } else {
            if (!ID.equals(before.property("type")) || !VERSION.equals(before.property(PREFIX + "version")))
                throw new IllegalArgumentException("Foreign source or unsupported architecture brief");
            for (PropertyAst p : before.getProperties()) {
                if (properties.putIfAbsent(p.key(), p.value()) != null)
                    throw new IllegalArgumentException("Duplicate architecture brief property");
            }
        }
        properties.keySet().removeIf(key -> key.startsWith(prefix));
        properties.putAll(updated);
        long size = properties.entrySet().stream().filter(entry -> entry.getKey().startsWith(PREFIX))
                .mapToLong(entry -> entry.getKey().length() + entry.getValue().length()).sum();
        if (size > PlanningInformation.MAX_CHARACTERS) throw new IllegalArgumentException("Architecture brief exceeds the metadata budget");
        var after = PlanningBlocks.block("source", id, properties);
        return ArchitectureSemanticPatch.replace(dsl, before, after);
    }

    private static boolean reservedId(String id) { return ID.equals(id) || ALTERNATE_ID.equals(id); }

    public static boolean presentIn(Map<String, BlockAst> blocks) {
        return isBrief(blocks.get("source:" + ID)) || isBrief(blocks.get("source:" + ALTERNATE_ID));
    }

    private static boolean isBrief(BlockAst block) { return block != null && ID.equals(block.property("type")); }

    public static boolean ownsChange(String before, String after, String blockId) {
        if (!blockId.equals("source:" + ID) && !blockId.equals("source:" + ALTERNATE_ID)) return false;
        var old = ArchitectureSemanticPatch.index(before).get(blockId);
        var updated = ArchitectureSemanticPatch.index(after).get(blockId);
        return (old == null || isBrief(old)) && (updated == null || isBrief(updated));
    }

    private static void validateFields(Map<String, String> fields) {
        if (!FIELDS.containsAll(fields.keySet()) || fields.getOrDefault("objective", "").isBlank())
            throw new IllegalArgumentException("An architecture objective and supported fields are required");
        fields.forEach((k, v) -> PlanningEntry.text(v, 4096, k));
        if (fields.containsKey("context") && !Set.of("public-services", "federal", "european", "admbw", "custom").contains(fields.get("context")))
            throw new IllegalArgumentException("Unknown architecture reference context");
        if (fields.containsKey("stage") && !Set.of("current", "target", "transition").contains(fields.get("stage")))
            throw new IllegalArgumentException("Unknown architecture stage");
    }

    private static void validateReference(Map<String, String> values) {
        values.forEach((key, value) -> {
            if (!LOCATOR.contains(key) && !APPLICATION.contains(key)) throw new IllegalArgumentException("Unknown reference field");
            PlanningEntry.text(value, 4096, key);
        });
        if (values.getOrDefault("identifier", "").isBlank()
                || !Set.of("binding", "guidance", "open").contains(values.getOrDefault("role", ""))
                || !Set.of("architecture", "deliverable", "background").contains(values.getOrDefault("scope", "")))
            throw new IllegalArgumentException("Reference identifier, role and scope are required");
        if ("deliverable".equals(values.get("scope")) && values.getOrDefault("appliesTo", "").isBlank())
            throw new IllegalArgumentException("A deliverable reference needs its named scope");
        if (values.containsKey("rule") || values.containsKey("relation")) {
            if (!RULE.equals(values.get("rule")) || !RelationMetamodel.relationTypes().contains(values.getOrDefault("relation", ""))
                    || !"binding".equals(values.get("role")) || !"architecture".equals(values.get("scope"))
                    || values.getOrDefault("edition", "").isBlank() || values.getOrDefault("section", "").isBlank())
                throw new IllegalArgumentException("A typed rule requires a binding architecture-wide source, edition and section");
        }
    }
}
