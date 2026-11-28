package com.taxonomy.dsl.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** A named organizational unit in one versioned architecture directory. */
public record ArchitectureOrganization(String id, String title, String parentId, Map<String, String> extensions) {
    public static final String RESPONSIBLE_ID = "x-responsible-organization-id";
    public static final String CAPTURED_UNIT = "x-captured-responsibility-unit";
    public static final String CAPTURED_NAME = "x-captured-responsibility-name";
    public static final String CAPTURED_SOURCE = "x-captured-responsibility-source";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    public ArchitectureOrganization(String id, String title, String parentId) { this(id, title, parentId, Map.of()); }

    public ArchitectureOrganization {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        if ("".equals(parentId)) parentId = null;
    }

    /** The same checks guard source import, commands, reconciliation and undo. */
    public static void validate(CanonicalArchitectureModel model, BiConsumer<String, String> error) {
        Map<String, ArchitectureOrganization> units = new LinkedHashMap<>();
        for (var unit : model.getOrganizations()) {
            if (unit.id() == null || !IDENTIFIER.matcher(unit.id()).matches()) {
                error.accept("INVALID_ORGANIZATION_ID", "An organization requires a stable DSL identifier");
                continue;
            }
            if (units.putIfAbsent(unit.id(), unit) != null)
                error.accept("DUPLICATE_ORGANIZATION", "Duplicate organization: " + unit.id());
            if (unit.title() == null || unit.title().isBlank() || unit.title().length() > 8000
                    || unit.title().chars().anyMatch(c -> (c < 32 && c != '\t' && c != '\n' && c != '\r')
                            || c == 0xfffe || c == 0xffff))
                error.accept("INVALID_ORGANIZATION_TITLE", "Organization " + unit.id() + " needs a valid title");
        }
        for (var unit : units.values()) {
            if (unit.parentId() != null && !units.containsKey(unit.parentId()))
                error.accept("UNKNOWN_ORGANIZATION", "Unknown parent " + unit.parentId() + " on " + unit.id());
        }
        Set<String> checked = new HashSet<>();
        for (var unit : units.values()) {
            if (checked.contains(unit.id())) continue;
            Set<String> path = new HashSet<>();
            var current = unit;
            while (current != null && !checked.contains(current.id())) {
                if (!path.add(current.id())) {
                    error.accept("ORGANIZATION_CYCLE", "Organization hierarchy contains a cycle at " + current.id());
                    break;
                }
                current = units.get(current.parentId());
            }
            checked.addAll(path);
        }
        for (var element : model.getElements()) {
            String id = element.getExtensions().get(RESPONSIBLE_ID);
            if (id != null && !id.isEmpty() && !units.containsKey(id))
                error.accept("UNKNOWN_ORGANIZATION", "Element " + element.getId() + " references unknown organization " + id);
        }
        Set<String> assignedCodes = new HashSet<>();
        String revision = model.responsibilityCatalogueRevision();
        for (var assignment : model.getCatalogueResponsibilities()) {
            String code = assignment.code();
            if (code == null || !IDENTIFIER.matcher(code).matches())
                error.accept("INVALID_CATALOGUE_CODE", "A catalogue responsibility requires one catalogue code");
            else if (!assignedCodes.add(code))
                error.accept("DUPLICATE_CATALOGUE_RESPONSIBILITY", "Duplicate catalogue responsibility: " + code);
            if (!units.containsKey(assignment.organizationId()))
                error.accept("UNKNOWN_ORGANIZATION", "Catalogue code " + code + " references unknown organization " + assignment.organizationId());
            if (assignment.catalogueRevision() != null && (!assignment.catalogueRevision().equals(revision)
                    || revision.isBlank() || revision.length() > 128))
                error.accept("INVALID_CATALOGUE_REVISION", "Catalogue assignments must refer to one retained revision");
        }
    }
}
