package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRequirement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
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
    private static final String ONE_ENTRY = "Keep one R7 planning entry per requirement. Remove any other R7 entries before saving.";
    private static final List<String> KINDS = List.of("FunctionalRequirement", "NonfunctionalRequirement");
    private static final Set<String> FIELDS = Set.of(KIND, DERIVED_FROM, SUBJECT, REALIZER, BOUND_VERSION, BOUND_HASH, IMPORTED);
    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(DERIVED_FROM, "DerivedFrom", List.of("OperationalConstraint"), false),
            new Endpoint(SUBJECT, "Satisfy", List.of("OperationalArchitecture", "OperationalRole"), true),
            new Endpoint(REALIZER, "ToBeRealizedBy", List.of("CapabilityConfiguration", "ResourceRole"), false));

    private record Endpoint(String field, String connector, List<String> stereotypes, boolean catalogueRequired) {
        boolean accepts(ArchitectureElement element, boolean nativeDelivery) {
            String stereotype = AdmbwFffmlvProfile.r7Role(element);
            boolean bindingRequired = catalogueRequired || nativeDelivery && "ResourceRole".equals(stereotype);
            return stereotypes.contains(stereotype) && (!bindingRequired || AdmbwFffmlvProfile.hasCatalogueBinding(element));
        }
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(ID, VERSION, List.of(new Field(KIND, true, KINDS),
                new Field(DERIVED_FROM, true, List.of()), new Field(SUBJECT, true, List.of()),
                new Field(REALIZER, false, List.of())));
    }

    @Override
    public Map<String, List<String>> elementChoices(CanonicalArchitectureModel model) {
        Map<String, List<String>> choices = new LinkedHashMap<>();
        boolean nativeDelivery = model.getAdmbwDelivery() != null;
        for (Endpoint endpoint : ENDPOINTS) {
            choices.put(endpoint.field(), model.getElements().stream().filter(element -> endpoint.accepts(element, nativeDelivery))
                    .map(ArchitectureElement::getId).filter(AdmbwRequirementProfile::elementId).sorted().toList());
        }
        return Map.copyOf(choices);
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
        var blocks = ArchitectureSemanticPatch.index(dsl);
        var block = blocks.get("requirement:" + requirement);
        if (block == null) throw new IllegalArgumentException("Unknown canonical requirement");
        if (PlanningInformation.decode(block.getExtensions()).stream()
                .anyMatch(existing -> ID.equals(existing.profile()) && !entry.equals(existing.id()))) {
            throw new IllegalArgumentException(ONE_ENTRY);
        }
        String version = block.property("x-version");
        String hash = block.property("x-content-hash");
        if (!versioned(version, hash, block.property("text"))) {
            throw new IllegalArgumentException("R7 needs a versioned requirement with its exact text hash");
        }
        if (values.containsKey(BOUND_VERSION)
                && (!version.equals(values.get(BOUND_VERSION)) || !hash.equals(values.get(BOUND_HASH)))) {
            throw new IllegalArgumentException("The R7 assignment belongs to another requirement revision");
        }
        var model = new AstToModelMapper().map(new DocumentAst(null, List.copyOf(blocks.values())));
        validateEndpoints(elementsById(model), values, model.getAdmbwDelivery() != null);
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

    /** Reconciliation must keep the endpoints of locally reviewed assignments on the current revision. */
    public void validateReviewedAssignments(CanonicalArchitectureModel model) {
        Map<String, ArchitectureElement> elements = elementsById(model);
        boolean nativeDelivery = model.getAdmbwDelivery() != null;
        for (var requirement : model.getRequirements()) {
            var reviewed = retainedEntries(requirement.getExtensions()).stream()
                    .filter(entry -> reviewedForRevision(requirement, entry)).toList();
            if (reviewed.size() > 1) throw new IllegalArgumentException(ONE_ENTRY);
            for (PlanningEntry entry : reviewed) {
                validate(entry.values());
                validateEndpoints(elements, entry.values(), nativeDelivery);
            }
        }
    }

    public void validateRetainedEndpoint(CanonicalArchitectureModel before, CanonicalArchitectureModel after, String id) {
        if (before.getAdmbwDelivery() == null) return;
        ArchitectureElement previous = before.findElement(id).orElse(null);
        ArchitectureElement changed = after.findElement(id).orElse(null);
        for (Endpoint endpoint : ENDPOINTS) {
            if (!endpoint.accepts(previous, true) || endpoint.accepts(changed, after.getAdmbwDelivery() != null)) continue;
            // Undo can remove an assignment and change its endpoint in the same patch.
            List<String> dependencies = after.getRequirements().stream()
                    .filter(requirement -> usesReviewedEndpoint(requirement, endpoint.field(), id))
                    .map(requirement -> "requirement:" + requirement.getId()).sorted().toList();
            if (!dependencies.isEmpty()) {
                throw new CommandProblem("ADMBW_R7_ENDPOINT_IN_USE", endpoint.field(),
                        "Reassign or remove the reviewed R7 " + endpoint.connector() + " slot before changing " + id, dependencies);
            }
        }
    }

    private boolean usesReviewedEndpoint(ArchitectureRequirement requirement, String field, String id) {
        List<PlanningEntry> entries = retainedEntries(requirement.getExtensions()).stream()
                .filter(entry -> ID.equals(entry.profile())).toList();
        if (entries.size() != 1) return false;
        PlanningEntry entry = entries.getFirst();
        if (!id.equals(entry.values().get(field)) || !reviewedForRevision(requirement, entry)) return false;
        try {
            validate(entry.values());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean reviewedForRevision(ArchitectureRequirement requirement, PlanningEntry entry) {
        Map<String, String> metadata = requirement.getExtensions();
        String version = metadata.get("x-version");
        String hash = metadata.get("x-content-hash");
        Map<String, String> values = entry.values();
        return ID.equals(entry.profile()) && VERSION.equals(entry.version()) && !"true".equals(values.get(IMPORTED))
                && values.getOrDefault(BOUND_VERSION, "").equals(version)
                && values.getOrDefault(BOUND_HASH, "").equals(hash)
                && versioned(version, hash, requirement.getText());
    }

    private static List<PlanningEntry> retainedEntries(Map<String, String> metadata) {
        try {
            return PlanningInformation.decode(metadata);
        } catch (IllegalArgumentException invalid) {
            // Preserve unreadable planning evidence for inspection. It has no active R7 endpoints.
            return List.of();
        }
    }

    private static Map<String, ArchitectureElement> elementsById(CanonicalArchitectureModel model) {
        Map<String, ArchitectureElement> elements = new LinkedHashMap<>();
        model.getElements().forEach(element -> elements.put(element.getId(), element));
        return elements;
    }

    private static void validateEndpoints(Map<String, ArchitectureElement> elements, Map<String, String> values,
                                          boolean nativeDelivery) {
        for (Endpoint endpoint : ENDPOINTS) {
            String id = values.get(endpoint.field());
            if (id != null && !endpoint.accepts(elements.get(id), nativeDelivery)) {
                String types = nativeDelivery && REALIZER.equals(endpoint.field())
                        ? "CapabilityConfiguration or ResourceRole bound to an existing EA Part"
                        : String.join(" or ", endpoint.stereotypes());
                throw new IllegalArgumentException("Select an existing " + (endpoint.catalogueRequired() ? "catalogue " : "")
                        + types + " for R7 " + endpoint.connector() + " (selected " + id + ")");
            }
        }
    }

    private static boolean versioned(String version, String hash, String text) {
        return AdmbwFffmlvProfile.version(version) != null && text != null && !text.isBlank()
                && RequirementRealization.textHash(text).equals(hash);
    }

    private static boolean elementId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    }
}
