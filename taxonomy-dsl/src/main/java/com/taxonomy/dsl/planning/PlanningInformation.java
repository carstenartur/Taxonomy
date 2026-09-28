package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded namespace on the existing canonical requirement, not an additional authoring store. */
public final class PlanningInformation {
    public static final String PREFIX = "x-planning.";
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_CHARACTERS = 65536;
    private record Key(String id, String version) {}
    public record View(PlanningEntry entry, boolean supported, String problem) {}
    private final Map<Key, PlanningProfile> profiles;

    public PlanningInformation() { this(installedProfiles()); }
    private static List<PlanningProfile> installedProfiles() {
        List<PlanningProfile> result = new ArrayList<>(List.of(new GoLiveProfile(), new StandardReferenceProfile()));
        java.util.ServiceLoader.load(PlanningProfile.class).forEach(result::add);
        return result;
    }
    public PlanningInformation(List<PlanningProfile> extensions) {
        Map<Key, PlanningProfile> registry = new LinkedHashMap<>();
        for (PlanningProfile profile : extensions) {
            var d = profile.descriptor();
            if (registry.putIfAbsent(new Key(d.id(), d.version()), profile) != null)
                throw new IllegalArgumentException("Duplicate planning profile and version");
        }
        profiles = Map.copyOf(registry);
    }
    public List<PlanningProfile.Descriptor> descriptors() {
        return profiles.values().stream().map(PlanningProfile::descriptor)
                .sorted(java.util.Comparator.comparing(PlanningProfile.Descriptor::id).thenComparing(PlanningProfile.Descriptor::version)).toList();
    }
    public boolean supports(String profile, String version) { return profiles.containsKey(new Key(profile, version)); }
    public void validatePortable(PlanningEntry entry) {
        PlanningProfile profile = profiles.get(new Key(entry.profile(), entry.version()));
        if (profile != null) profile.validate(entry.values());
    }

    public List<View> read(String dsl, String requirement) {
        return read(new ArchitectureDslCommands().model(dsl), requirement);
    }
    public List<View> read(CanonicalArchitectureModel model, String requirement) {
        var req = model.getRequirements().stream().filter(r -> requirement.equals(r.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown canonical requirement"));
        List<View> result = new ArrayList<>();
        List<PlanningEntry> decoded;
        try { decoded = decode(req.getExtensions()); }
        catch (IllegalArgumentException invalid) {
            // Keep the original namespace untouched in DSL. Do not fabricate an editable identity.
            return List.of(new View(null, false, "INVALID_PLANNING_NAMESPACE"));
        }
        for (PlanningEntry stored : decoded) {
            PlanningProfile profile = profiles.get(new Key(stored.profile(), stored.version()));
            if (profile == null) { result.add(new View(stored, false, "UNSUPPORTED_PROFILE_VERSION")); continue; }
            try {
                result.add(new View(new PlanningEntry(stored.id(), stored.profile(), stored.version(), stored.origin(),
                        profile.read(model, requirement, stored.values())), true, null));
            } catch (IllegalArgumentException invalid) {
                // Retain malformed evidence but never silently interpret it as a valid planning fact.
                result.add(new View(stored, false, "INVALID_PROFILE_DATA"));
            }
        }
        return List.copyOf(result);
    }
    public String upsert(String dsl, String requirement, PlanningEntry entry, boolean preserveUnknown) {
        BlockAst before = require(dsl, requirement);
        List<PlanningEntry> current = decode(properties(before));
        PlanningProfile profile = profiles.get(new Key(entry.profile(), entry.version()));
        PlanningEntry previous = current.stream().filter(e -> e.id().equals(entry.id())).findFirst().orElse(null);
        if (!preserveUnknown && (profile == null || previous != null && !supports(previous.profile(), previous.version())))
            throw new IllegalArgumentException("Unknown planning profiles are read-only");
        if (previous != null && !previous.profile().equals(entry.profile()))
            throw new IllegalArgumentException("A planning entry cannot change its profile identity; remove it explicitly first");
        // An unknown incoming version is opaque evidence, not authority to remove a known projection.
        if (profile != null && previous != null && !previous.version().equals(entry.version())) {
            PlanningProfile previousProfile = profiles.get(new Key(previous.profile(), previous.version()));
            if (previousProfile != null) dsl = previousProfile.remove(dsl, requirement, entry.id(), previous.values());
        }
        Map<String, String> stored = entry.values();
        if (profile != null) {
            var normalized = profile.store(dsl, requirement, entry.id(), entry.values());
            dsl = normalized.dsl(); stored = normalized.values();
        }
        Map<String, String> values = properties(require(dsl, requirement));
        String prefix = PREFIX + entry.id() + ".";
        values.keySet().removeIf(k -> k.startsWith(prefix));
        values.put(prefix + "profile", entry.profile()); values.put(prefix + "version", entry.version());
        values.put(prefix + "origin", entry.origin());
        stored.forEach((key, value) -> values.put(prefix + "value." + key, value));
        decode(values); // Bounds apply to the complete requirement, not merely the new entry.
        return replaceRequirement(dsl, require(dsl, requirement), values);
    }
    public String remove(String dsl, String requirement, String id) {
        PlanningEntry.token(id, "entry id");
        BlockAst block = require(dsl, requirement);
        PlanningEntry entry = decode(properties(block)).stream().filter(e -> id.equals(e.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown planning entry"));
        PlanningProfile profile = profiles.get(new Key(entry.profile(), entry.version()));
        if (profile != null) dsl = profile.remove(dsl, requirement, id, entry.values());
        Map<String, String> values = properties(require(dsl, requirement));
        values.keySet().removeIf(k -> k.startsWith(PREFIX + id + "."));
        return replaceRequirement(dsl, require(dsl, requirement), values);
    }
    /** Portfolio regeneration owns text and business fields, but not workspace planning annotations. */
    public static BlockAst retain(BlockAst generated, BlockAst existing) {
        if (existing == null) return generated;
        Map<String, String> values = properties(generated);
        properties(existing).forEach((key, value) -> { if (key.startsWith(PREFIX)) values.put(key, value); });
        return copy(generated, values);
    }
    public static List<PlanningEntry> decode(Map<String, String> properties) {
        Map<String, Map<String, String>> groups = new TreeMap<>();
        int characters = 0;
        for (var property : properties.entrySet()) if (property.getKey().startsWith(PREFIX)) {
            String suffix = property.getKey().substring(PREFIX.length());
            int separator = suffix.indexOf('.');
            if (separator <= 0) throw new IllegalArgumentException("Malformed planning namespace");
            String id = suffix.substring(0, separator); PlanningEntry.token(id, "entry id");
            groups.computeIfAbsent(id, ignored -> new TreeMap<>()).put(suffix.substring(separator + 1), property.getValue());
            characters += property.getKey().length() + property.getValue().length();
            if (characters > MAX_CHARACTERS || groups.size() > MAX_ENTRIES) throw new IllegalArgumentException("Planning metadata exceeds the requirement budget");
        }
        List<PlanningEntry> result = new ArrayList<>();
        for (var group : groups.entrySet()) {
            Map<String, String> metadata = group.getValue(), values = new TreeMap<>();
            for (var value : metadata.entrySet()) {
                if (value.getKey().startsWith("value.")) values.put(value.getKey().substring(6), value.getValue());
                else if (!List.of("profile", "version", "origin").contains(value.getKey())) throw new IllegalArgumentException("Unknown planning envelope field");
            }
            result.add(new PlanningEntry(group.getKey(), metadata.get("profile"), metadata.get("version"), metadata.get("origin"), values));
        }
        return List.copyOf(result);
    }
    private static BlockAst require(String dsl, String requirement) {
        BlockAst block = ArchitectureSemanticPatch.index(dsl).get("requirement:" + requirement);
        if (block == null) throw new IllegalArgumentException("Unknown canonical requirement");
        return block;
    }
    private static Map<String, String> properties(BlockAst block) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PropertyAst p : block.getProperties()) {
            if (result.putIfAbsent(p.key(), p.value()) != null && p.key().startsWith(PREFIX))
                throw new IllegalArgumentException("Duplicate planning field");
        }
        return result;
    }
    private static String replaceRequirement(String dsl, BlockAst before, Map<String, String> values) {
        return ArchitectureSemanticPatch.replace(dsl, before, copy(before, values));
    }
    private static BlockAst copy(BlockAst block, Map<String, String> values) {
        Map<String, String> extensions = new LinkedHashMap<>(block.getExtensions());
        extensions.keySet().removeIf(k -> k.startsWith(PREFIX));
        values.forEach((k, v) -> { if (k.startsWith(PREFIX)) extensions.put(k, v); });
        return new BlockAst(block.getKind(), block.getHeaderTokens(), values.entrySet().stream()
                .map(e -> new PropertyAst(e.getKey(), e.getValue(), null)).toList(), block.getChildren(), extensions, null);
    }
    /** Undo may touch requirement planning fields, never restore unrelated requirement text. */
    public static boolean planningOnlyChange(String before, String after, String id) {
        BlockAst a = ArchitectureSemanticPatch.index(before).get(id), b = ArchitectureSemanticPatch.index(after).get(id);
        if (id.startsWith("requirement:") && a != null && b != null) {
            Map<String, String> pa = properties(a), pb = properties(b);
            pa.keySet().removeIf(k -> k.startsWith(PREFIX)); pb.keySet().removeIf(k -> k.startsWith(PREFIX));
            return a.getHeaderTokens().equals(b.getHeaderTokens()) && pa.equals(pb);
        }
        BlockAst any = a == null ? b : a;
        return any != null && (("requirementSourceLink".equals(any.getKind()) && any.property("x-planning-entry") != null)
                || List.of("source", "sourceVersion", "sourceFragment").contains(any.getKind()) && "true".equals(any.property("x-planning-owned")));
    }
    public static void validateReferences(String dsl) {
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(dsl);
        for (var version : model.getSourceVersions()) {
            if (model.getSources().stream().noneMatch(source -> Objects.equals(source.getId(), version.getSourceId())))
                throw new IllegalArgumentException("Undo would strand a source edition");
        }
        for (var fragment : model.getSourceFragments()) {
            if (model.getSourceVersions().stream().noneMatch(version -> Objects.equals(version.getId(), fragment.getSourceVersionId())))
                throw new IllegalArgumentException("Undo would strand a source fragment");
        }
        for (var link : model.getRequirementSourceLinks()) {
            if (model.getRequirements().stream().noneMatch(r -> Objects.equals(r.getId(), link.getRequirementId()))
                    || model.getSources().stream().noneMatch(s -> Objects.equals(s.getId(), link.getSourceId()))
                    || link.getSourceVersionId() != null && model.getSourceVersions().stream().noneMatch(v -> Objects.equals(v.getId(), link.getSourceVersionId()) && Objects.equals(v.getSourceId(), link.getSourceId()))
                    || link.getSourceFragmentId() != null && model.getSourceFragments().stream().noneMatch(f -> Objects.equals(f.getId(), link.getSourceFragmentId()) && Objects.equals(f.getSourceVersionId(), link.getSourceVersionId())))
                throw new IllegalArgumentException("Undo would strand a canonical source reference");
        }
    }
}
