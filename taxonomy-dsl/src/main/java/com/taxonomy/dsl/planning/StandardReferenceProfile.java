package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.model.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reference only: canonical sources remain authoritative; no compliance/obligation assertion. */
public final class StandardReferenceProfile implements PlanningProfile {
    @Override public Descriptor descriptor() {
        return new Descriptor("standard-reference", "1", List.of(new Field("identifier", true, List.of()),
                new Field("edition", false, List.of()), new Field("section", false, List.of())));
    }
    @Override public void validate(Map<String, String> values) {
        if (!Set.of("identifier", "edition", "section").containsAll(values.keySet())
                || values.get("identifier") == null || values.get("identifier").isBlank()
                || values.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("A standard/directive identifier is required; only edition and section are optional");
    }
    @Override public Stored store(String dsl, String requirement, String entry, Map<String, String> values) {
        validate(values);
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(dsl);
        String identifier = values.get("identifier"), edition = values.getOrDefault("edition", ""), section = values.getOrDefault("section", "");
        var matches = model.getSources().stream().filter(s -> identifier.equals(s.getCanonicalIdentifier())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous source identifier; resolve existing sources first");
        String source = matches.isEmpty() ? PlanningBlocks.identity("plan-source-", identifier) : matches.getFirst().getId();
        if (matches.isEmpty()) dsl = PlanningBlocks.add(dsl, PlanningBlocks.block("source", source,
                Map.of("type", "reference", "title", identifier, "canonicalIdentifier", identifier, "x-planning-owned", "true")));
        Map<String, String> link = new LinkedHashMap<>();
        link.put("requirement", requirement); link.put("source", source); link.put("linkType", "references");
        link.put("x-planning-entry", entry);
        if (!edition.isEmpty()) {
            var versions = model.getSourceVersions().stream().filter(v -> source.equals(v.getSourceId()) && edition.equals(v.getVersionLabel())).toList();
            if (versions.size() > 1) throw new IllegalArgumentException("Ambiguous source edition");
            String version = versions.isEmpty() ? PlanningBlocks.identity("plan-version-", source + "\n" + edition) : versions.getFirst().getId();
            if (versions.isEmpty()) dsl = PlanningBlocks.add(dsl, PlanningBlocks.block("sourceVersion", version, Map.of("source", source, "versionLabel", edition, "x-planning-owned", "true")));
            link.put("sourceVersion", version);
            if (!section.isEmpty()) {
                var fragments = model.getSourceFragments().stream().filter(f -> version.equals(f.getSourceVersionId()) && section.equals(f.getSectionPath())).toList();
                if (fragments.size() > 1) throw new IllegalArgumentException("Ambiguous source section");
                String fragment = fragments.isEmpty() ? PlanningBlocks.identity("plan-fragment-", version + "\n" + section) : fragments.getFirst().getId();
                if (fragments.isEmpty()) dsl = PlanningBlocks.add(dsl, PlanningBlocks.block("sourceFragment", fragment, Map.of("sourceVersion", version, "sectionPath", section, "x-planning-owned", "true")));
                link.put("sourceFragment", fragment);
            }
        } else if (!section.isEmpty()) {
            // Keep an unresolved locator on the reference itself; never fabricate a source edition.
            link.put("x-unversioned-section", section);
        }
        String linkId = PlanningBlocks.identity("plan-link-", requirement + "\n" + entry);
        BlockAst before = ArchitectureSemanticPatch.index(dsl).get("requirementSourceLink:" + linkId);
        if (before != null && (!requirement.equals(before.property("requirement")) || !entry.equals(before.property("x-planning-entry"))
                || !"references".equals(before.property("linkType"))))
            throw new IllegalArgumentException("Planning reference identity belongs to another scope");
        BlockAst replacement = PlanningBlocks.block("requirementSourceLink", linkId, link);
        if (before != null) {
            Map<String, String> retained = new LinkedHashMap<>();
            Set<String> managed = Set.of("requirement", "source", "sourceVersion", "sourceFragment", "linkType", "x-planning-entry", "x-unversioned-section");
            before.getProperties().forEach(property -> { if (!managed.contains(property.key())) retained.put(property.key(), property.value()); });
            retained.putAll(link);
            Map<String, String> extensions = new LinkedHashMap<>(before.getExtensions());
            managed.forEach(extensions::remove);
            retained.forEach((key, value) -> { if (key.startsWith("x-")) extensions.put(key, value); });
            replacement = new BlockAst(before.getKind(), before.getHeaderTokens(), retained.entrySet().stream()
                    .map(property -> new com.taxonomy.dsl.ast.PropertyAst(property.getKey(), property.getValue(), null)).toList(),
                    before.getChildren(), extensions, null);
        }
        dsl = ArchitectureSemanticPatch.replace(dsl, before, replacement);
        return new Stored(dsl, Map.of("linkId", linkId));
    }
    @Override public String remove(String dsl, String requirement, String entry, Map<String, String> stored) {
        BlockAst link = ArchitectureSemanticPatch.index(dsl).get("requirementSourceLink:" + stored.get("linkId"));
        if (link == null || !requirement.equals(link.property("requirement")) || !entry.equals(link.property("x-planning-entry")))
            throw new IllegalArgumentException("Missing or foreign planning reference");
        return ArchitectureSemanticPatch.replace(dsl, link, null);
    }
    @Override public Map<String, String> read(CanonicalArchitectureModel model, String requirement, Map<String, String> stored) {
        if (!stored.keySet().equals(Set.of("linkId"))) throw new IllegalArgumentException("Invalid canonical standard reference");
        var link = model.getRequirementSourceLinks().stream().filter(l -> stored.get("linkId").equals(l.getId()) && requirement.equals(l.getRequirementId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing or foreign canonical standard reference"));
        if (!"references".equals(link.getLinkType())) throw new IllegalArgumentException("A standard reference may not be interpreted as compliance");
        var source = model.getSources().stream().filter(s -> Objects.equals(link.getSourceId(), s.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing canonical source"));
        Map<String, String> result = new LinkedHashMap<>(); result.put("identifier", source.getCanonicalIdentifier());
        if (link.getSourceVersionId() != null) {
            var version = model.getSourceVersions().stream().filter(v -> link.getSourceVersionId().equals(v.getId()) && source.getId().equals(v.getSourceId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing or foreign canonical source edition"));
            result.put("edition", version.getVersionLabel());
            if (link.getSourceFragmentId() != null) {
                var fragment = model.getSourceFragments().stream().filter(f -> link.getSourceFragmentId().equals(f.getId()) && version.getId().equals(f.getSourceVersionId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing or foreign source section"));
                result.put("section", fragment.getSectionPath());
            }
        } else {
            if (link.getSourceFragmentId() != null) throw new IllegalArgumentException("A source fragment requires its exact edition");
            if (link.getExtensions().containsKey("x-unversioned-section")) result.put("section", link.getExtensions().get("x-unversioned-section"));
        }
        validate(result); return Map.copyOf(result);
    }
}
