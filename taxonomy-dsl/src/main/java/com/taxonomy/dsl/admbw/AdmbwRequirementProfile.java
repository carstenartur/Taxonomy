package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.planning.PlanningProfile;
import com.taxonomy.dsl.requirements.RequirementRealization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records a reviewed R7 assignment on the exact requirement revision. */
public final class AdmbwRequirementProfile implements PlanningProfile {
    public static final String ID = "admbw-r7";
    public static final String VERSION = "1";
    static final String KIND = "requirementType";
    static final String DERIVED_FROM = "derivedFrom";
    static final String REALIZER = "realizer";
    static final String SUBJECT = "constraintSubject";
    static final String BOUND_VERSION = "boundVersion";
    static final String BOUND_HASH = "boundHash";
    static final String IMPORTED = "imported";
    private static final List<String> KINDS = List.of("FunctionalRequirement", "NonfunctionalRequirement");
    private static final Set<String> FIELDS = Set.of(KIND, DERIVED_FROM, SUBJECT, REALIZER, BOUND_VERSION, BOUND_HASH, IMPORTED);

    @Override
    public Descriptor descriptor() {
        return new Descriptor(ID, VERSION, List.of(new Field(KIND, true, KINDS),
                new Field(DERIVED_FROM, true, List.of()), new Field(SUBJECT, true, List.of()),
                new Field(REALIZER, false, List.of())));
    }

    @Override
    public void validate(Map<String, String> values) {
        if (!FIELDS.containsAll(values.keySet()) || !KINDS.contains(values.getOrDefault(KIND, ""))
                || !elementId(values.get(DERIVED_FROM)) || !elementId(values.get(SUBJECT))
                || values.containsKey(REALIZER) && !elementId(values.get(REALIZER))
                || values.containsKey(IMPORTED) && !"true".equals(values.get(IMPORTED))) {
            throw new IllegalArgumentException("R7 needs a requirement type, one source element and its catalogue subject, with at most one realizer");
        }
        if (values.containsKey(BOUND_VERSION) || values.containsKey(BOUND_HASH)) {
            if (AdmbwFffmlvProfile.version(values.get(BOUND_VERSION)) == null
                    || !values.getOrDefault(BOUND_HASH, "").matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid R7 requirement revision");
            }
        }
    }

    @Override
    public Stored store(String dsl, String requirement, String entry, Map<String, String> values) {
        validate(values);
        var block = ArchitectureSemanticPatch.index(dsl).get("requirement:" + requirement);
        if (block == null) throw new IllegalArgumentException("Unknown canonical requirement");
        String version = block.property("x-version");
        String hash = block.property("x-content-hash");
        String text = block.property("text");
        if (AdmbwFffmlvProfile.version(version) == null || text == null || text.isBlank()
                || !RequirementRealization.textHash(text).equals(hash)) {
            throw new IllegalArgumentException("R7 needs a versioned requirement with its exact text hash");
        }
        if (values.containsKey(BOUND_VERSION)
                && (!version.equals(values.get(BOUND_VERSION)) || !hash.equals(values.get(BOUND_HASH)))) {
            throw new IllegalArgumentException("The R7 assignment belongs to another requirement revision");
        }
        Map<String, String> stored = new LinkedHashMap<>(values);
        stored.remove(IMPORTED);
        stored.put(BOUND_VERSION, version);
        stored.put(BOUND_HASH, hash);
        return new Stored(dsl, stored);
    }

    @Override
    public Stored storeImported(String dsl, String requirement, String entry, Map<String, String> values) {
        validate(values);
        Map<String, String> stored = new LinkedHashMap<>(values);
        stored.put(IMPORTED, "true");
        return new Stored(dsl, stored);
    }

    private static boolean elementId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    }
}
