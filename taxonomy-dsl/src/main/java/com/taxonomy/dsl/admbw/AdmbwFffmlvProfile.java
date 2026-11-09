package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.ArchitectureRequirement;
import com.taxonomy.dsl.model.ArchitectureView;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.dsl.requirements.RequirementRealization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Structural checks and authoring data for two views of the public FFFmLV V2.0 guide. */
public final class AdmbwFffmlvProfile {

    public static final String ID = "admbw-2026.05-fffmlv-2.0-c1-s1-r7";
    public static final String TYPE = "x-admbw-type";
    public static final String CATALOGUE_GUID = "x-admbw-catalogue-guid";
    public static final String CATALOGUE_REFERENCE = "x-admbw-catalogue-reference";

    private static final String CAPABILITY = "Capability";
    private static final String SERVICE = "ServiceSpecification";
    private static final String CONSTRAINT = "OperationalConstraint";
    private static final Set<String> SERVICE_TYPES = Set.of("CoreService", "COIService");
    private static final Set<String> REALIZERS = Set.of("CapabilityConfiguration", "ResourceRole");
    private static final Set<String> CONSTRAINT_SUBJECTS = Set.of("OperationalArchitecture", "OperationalRole");
    public static final List<String> ELEMENT_STEREOTYPES = List.of(SERVICE, CONSTRAINT, "CapabilityConfiguration",
            "ResourceRole", "OperationalArchitecture", "OperationalRole");
    private static final Pattern PROJECT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final Pattern EA_GUID = Pattern.compile(
            "\\{[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}}");
    private static final String ZERO_GUID = "{00000000-0000-0000-0000-000000000000}";

    public record Report(String profile, String projectCode, String sourceViewId,
                         View capabilityToService, View requirementDerivation, AdmbwDeliveryProfile delivery) { }

    public record View(String viewpoint, String name, List<String> packagePath, String status,
                       List<Element> elements, List<Connector> connectors, List<Issue> issues,
                       List<String> requiredReviews) { }

    public record Element(String ref, String stereotype, String name, String notes,
                          String catalogueGuid, String catalogueReference,
                          Integer requirementVersion, String requirementContentHash) { }

    public record Connector(String stereotype, String client, String supplier) { }

    public record Issue(String code, String object, String detail) { }

    public Report assess(String dsl, String projectCode, String viewId) {
        if (projectCode == null || !PROJECT_CODE.matcher(projectCode).matches()) {
            throw new IllegalArgumentException("Project code must have 1–32 letters, digits, hyphens or underscores");
        }
        validateDelimiters(dsl);
        var source = new TaxDslParser().parse(dsl);
        Set<String> identities = new HashSet<>();
        for (BlockAst block : source.getBlocks()) validateSourceBlock(block, identities);
        CanonicalArchitectureModel model = new AstToModelMapper().map(source);
        Map<String, ArchitectureElement> elements = new TreeMap<>();
        for (ArchitectureElement element : model.getElements()) {
            if (missing(element.getId()) || elements.putIfAbsent(element.getId(), element) != null) {
                throw new IllegalArgumentException("Each architecture element must have a unique identity");
            }
        }
        var delivery = model.getAdmbwDelivery();
        if (delivery != null) {
            delivery.requireRequest(projectCode, viewId);
            delivery.members(model);
        }
        Map<String, String> catalogueIdentities = new HashMap<>();
        return new Report(ID, projectCode, viewId,
                capabilityToService(model, elements, projectCode, viewId, catalogueIdentities),
                requirementDerivation(model, elements, projectCode, catalogueIdentities), delivery);
    }

    private View capabilityToService(CanonicalArchitectureModel model, Map<String, ArchitectureElement> elements,
                                     String projectCode, String viewId, Map<String, String> catalogueIdentities) {
        ArchitectureView selected = selectedView(model, viewId);
        Set<String> members = selected == null ? elements.keySet() : new HashSet<>(selected.getIncludes());
        // The retained snapshot provides membership evidence. An explicitly saved delivery
        // view includes newly authored, accepted connections between those members.
        String recordedRelations = selected == null || model.getAdmbwDelivery() != null
                ? null : selected.getExtensions().get("x-analysis-relation-members");
        Set<String> allowedRelations = recordedRelations == null ? Set.of()
                : new HashSet<>(Arrays.asList(recordedRelations.split("\n")));
        var view = new ViewBuilder();
        if (model.getAdmbwDelivery() != null && model.getAdmbwDelivery().catalogueFingerprint() == null)
            view.issue("NC3T_PROVENANCE_REQUIRED", "C1-S1", "Select a retained analysis view with a recorded C3 catalogue fingerprint.");
        for (String id : members.stream().sorted().toList()) {
            ArchitectureElement element = elements.get(id);
            if (element == null) {
                view.issue("UNKNOWN_VIEW_MEMBER", id, "The selected view references an element missing from this checkpoint.");
                continue;
            }
            if (CAPABILITY.equals(element.getType())) {
                if (!missing(element.getExtensions().get(TYPE))) {
                    view.issue("INCOMPATIBLE_ADMBW_ROLE", ref(id), "A catalogue capability cannot also carry another ADMBw stereotype.");
                }
                CatalogueBinding binding = checkCatalogue(view, element, catalogueIdentities, "CAPABILITY");
                view.add(element, CAPABILITY, binding.guid(), binding.reference());
            } else if (isService(element)) {
                if (!SERVICE.equals(element.getExtensions().get(TYPE))) {
                    view.issue("SERVICE_SPECIFICATION_REQUIRED", id, "Review and declare the mapping to ServiceSpecification.");
                }
                view.add(element, SERVICE, null, null);
            } else if (SERVICE.equals(element.getExtensions().get(TYPE))) {
                view.issue("UNSUPPORTED_SERVICE_MAPPING", ref(id), "C1-S1 supports ServiceSpecification assignments on Core and COI Services.");
            }
        }

        for (ArchitectureRelation relation : model.getRelations()) {
            String source = relation.getSourceId();
            String target = relation.getTargetId();
            if (source == null || target == null || !"accepted".equals(relation.getStatus())
                    || !view.elements.containsKey(ref(source))
                    || !view.elements.containsKey(ref(target))
                    || recordedRelations != null && !allowedRelations.contains(relationId(relation))) {
                continue;
            }
            var connector = c1S1Connector(elements.get(source), relation.getRelationType(), elements.get(target));
            if (connector != null) view.connect(connector.stereotype(), connector.client(), connector.supplier());
        }

        Set<String> linked = new HashSet<>();
        view.connectors.forEach(connector -> { linked.add(connector.client()); linked.add(connector.supplier()); });
        for (Element element : view.elements.values()) {
            if (linked.contains(element.ref())) continue;
            if (CAPABILITY.equals(element.stereotype())) {
                view.issue("CAPABILITY_EXHIBITS_REQUIRED", element.ref(), "Add an accepted mapping from a service to this capability.");
            } else {
                view.issue("SERVICE_EXHIBITS_REQUIRED", element.ref(), "Add an accepted mapping to an official capability.");
            }
        }
        if (view.elements.isEmpty()) {
            view.issue("C1_S1_ELEMENTS_REQUIRED", "C1-S1", "Select a model containing the capabilities and services to be delivered.");
        }
        return view.build("C1-S1", "C1-S1 : " + projectCode + " : Capability to Service Mapping",
                List.of("Service Specifications", "C1-S1", "Einordnung Fähigkeitslage"),
                List.of("Verify catalogue identities against the supplied catalogue. Record unresolved capabilities as Issues.",
                        "Check whether an Environment and EnvironmentalCondition are needed.",
                        "Check the actual ADMBw diagram subtype, diagram notes and native delivery in Enterprise Architect."));
    }

    private View requirementDerivation(CanonicalArchitectureModel model, Map<String, ArchitectureElement> elements,
                                       String projectCode, Map<String, String> catalogueIdentities) {
        var view = new ViewBuilder();
        if (model.getRequirements().isEmpty()) {
            view.issue("R7_REQUIREMENTS_REQUIRED", "R7", "Add the versioned requirements to the architecture checkpoint.");
        }
        Set<String> identities = new HashSet<>();
        for (ArchitectureRequirement requirement : model.getRequirements()) {
            if (missing(requirement.getId()) || !identities.add(requirement.getId())) {
                throw new IllegalArgumentException("Each architecture requirement must have a unique identity");
            }
            String id = "requirement:" + requirement.getId();
            var metadata = requirement.getExtensions();
            String hash = metadata.get("x-content-hash");
            String recordedVersion = metadata.get("x-version");
            Integer version = version(recordedVersion);
            if (version == null || missing(requirement.getText())
                    || !RequirementRealization.textHash(requirement.getText()).equals(hash)) {
                view.issue("REQUIREMENT_VERSION_REQUIRED", id, "Retain a positive requirement version and the SHA-256 of its exact text.");
                continue;
            }
            Map<String, String> plan = r7Plan(view, id, metadata);
            if (plan == null) continue;
            if ("true".equals(plan.get(AdmbwRequirementProfile.IMPORTED))) {
                view.issue("R7_IMPORTED_REVIEW_REQUIRED", id, "Verify the referenced elements and save the R7 assignment in this architecture.");
                continue;
            }
            if (!recordedVersion.equals(plan.get(AdmbwRequirementProfile.BOUND_VERSION))
                    || !hash.equals(plan.get(AdmbwRequirementProfile.BOUND_HASH))) {
                view.issue("STALE_R7_PLANNING", id, "Review and save the R7 assignment for the exact requirement revision.");
                continue;
            }
            view.add(new Element(id, plan.get(AdmbwRequirementProfile.KIND), requirement.getTitle(),
                    requirement.getText(), null, null, version, hash));
            ArchitectureElement source = lookup(elements, plan.get(AdmbwRequirementProfile.DERIVED_FROM));
            if (!CONSTRAINT.equals(r7Role(source))) {
                view.issue("OPERATIONAL_CONSTRAINT_REQUIRED", id, "Select exactly one modeled OperationalConstraint as the derivation source.");
            } else {
                view.add(source, CONSTRAINT, null, null);
                view.connect("DerivedFrom", id, ref(source.getId()));
                ArchitectureElement subject = lookup(elements, plan.get(AdmbwRequirementProfile.SUBJECT));
                String subjectType = r7Role(subject);
                if (!CONSTRAINT_SUBJECTS.contains(subjectType)) {
                    view.issue("R7_SUBJECT_REQUIRED", id, "Select a catalogue OperationalArchitecture or OperationalRole for R7-MK08.");
                } else {
                    CatalogueBinding binding = checkCatalogue(view, subject, catalogueIdentities, "SUBJECT");
                    view.add(subject, subjectType, binding.guid(), binding.reference());
                    view.connect("Satisfy", ref(subject.getId()), ref(source.getId()));
                }
            }
            String realizerId = plan.get(AdmbwRequirementProfile.REALIZER);
            if (realizerId != null) {
                ArchitectureElement realizer = lookup(elements, realizerId);
                String realizerType = r7Role(realizer);
                if (!REALIZERS.contains(realizerType)) {
                    view.issue("R7_REALIZER_TYPE", id, "Use a declared CapabilityConfiguration or ResourceRole for the FFFmLV subject.");
                } else {
                    var binding = new CatalogueBinding(null, null);
                    if ("ResourceRole".equals(realizerType) && (realizer.getExtensions().containsKey(CATALOGUE_GUID)
                            || realizer.getExtensions().containsKey(CATALOGUE_REFERENCE))) {
                        binding = checkCatalogue(view, realizer, catalogueIdentities, "REALIZER");
                    }
                    view.add(realizer, realizerType, binding.guid(), binding.reference());
                    view.connect("ToBeRealizedBy", id, ref(realizer.getId()));
                }
            }
        }
        return view.build("R7", "R7 : " + projectCode + " : Requirements Derivation",
                List.of("Requirements", "R7", "Requirement Derivation"),
                List.of("Check the SAMIT requirement sentence, Require.7 tagged fields and agreement of the Subjekt with the planned realizer.",
                        "Verify the catalogue SubjectOfOperationalConstraint and the native Satisfy roles (R7-MK08).",
                        "Check additional allowed realizer types and view grouping against the applicable project guide.",
                        "Check the actual ADMBw diagram subtype, diagram notes and native delivery in Enterprise Architect."));
    }

    private static Map<String, String> r7Plan(ViewBuilder view, String id, Map<String, String> metadata) {
        try {
            List<PlanningEntry> entries = PlanningInformation.decode(metadata).stream()
                    .filter(entry -> AdmbwRequirementProfile.ID.equals(entry.profile())).toList();
            if (entries.size() != 1 || !AdmbwRequirementProfile.VERSION.equals(entries.getFirst().version())) {
                view.issue("R7_PLANNING_REQUIRED", id, "Record one ADMBw R7 planning entry using profile version 1.");
                return null;
            }
            Map<String, String> values = entries.getFirst().values();
            new AdmbwRequirementProfile().validate(values);
            return values;
        } catch (IllegalArgumentException invalid) {
            view.issue("INVALID_R7_PLANNING", id, "Correct the R7 planning entry before delivery.");
            return null;
        }
    }

    private record CatalogueBinding(String guid, String reference) { }

    private static CatalogueBinding checkCatalogue(ViewBuilder view, ArchitectureElement element,
                                                     Map<String, String> identities, String role) {
        String id = ref(element.getId());
        String guid = element.getExtensions().get(CATALOGUE_GUID);
        String reference = element.getExtensions().get(CATALOGUE_REFERENCE);
        if (!validCatalogueGuid(guid)) {
            view.issue(role + "_CATALOGUE_GUID_REQUIRED", id, "Record the GUID of the existing catalogue element.");
            guid = null;
        } else {
            guid = guid.toUpperCase(Locale.ROOT);
            String previous = identities.putIfAbsent(guid, id);
            if (previous != null && !previous.equals(id)) {
                view.issue("DUPLICATE_CATALOGUE_GUID", id, "The same catalogue element is also bound to " + previous + ".");
            }
        }
        if (missing(reference)) {
            view.issue(role + "_CATALOGUE_REFERENCE_REQUIRED", id, "Record the authoritative catalogue and its version.");
        }
        return new CatalogueBinding(guid, reference);
    }

    private static void validateDelimiters(String dsl) {
        int depth = 0;
        for (String line : dsl.lines().toList()) {
            depth += TaxDslParser.blockDelimiter(line);
            if (depth < 0 || depth > 1) {
                throw new IllegalArgumentException("Invalid architecture DSL block delimiters");
            }
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced architecture DSL block delimiters");
    }

    private static void validateSourceBlock(BlockAst block, Set<String> identities) {
        int headerLength = switch (block.getKind()) {
            case "element", "relation" -> 3;
            case "requirement", "view" -> 1;
            default -> 0;
        };
        if (headerLength == 0) return;
        String id = ArchitectureSemanticPatch.key(block);
        if (!identities.add(id)) {
            throw new CommandProblem("INVALID_ADMBW_SOURCE", id, "Ambiguous duplicate block identity", List.of(id));
        }
        if (block.getHeaderTokens().size() != headerLength
                || "element".equals(block.getKind()) && !"type".equals(block.getHeaderTokens().get(1))) {
            throw new CommandProblem("INVALID_ADMBW_SOURCE", id, "Invalid block header for ADMBw assessment", List.of(id));
        }
        Set<String> names = new HashSet<>();
        for (var property : block.getProperties()) {
            String name = property.key();
            if (!("view".equals(block.getKind()) && "include".equals(name)) && !names.add(name)) {
                throw new CommandProblem("INVALID_ADMBW_SOURCE", id, "Ambiguous property: " + name, List.of(id));
            }
        }
    }

    /** Projects the directed canonical connection onto the ADMBw 2026.05 C1–S1 rule. */
    public static Connector c1S1Connector(ArchitectureElement source, String type, ArchitectureElement target) {
        if (source == null || target == null || source.getId() == null || target.getId() == null
                || source.getId().equals(target.getId())) return null;
        if ("REALIZES".equals(type) && capabilityEndpoint(source) && "CoreService".equals(target.getType()) && serviceEndpoint(target))
            return new Connector("Exhibits", ref(target.getId()), ref(source.getId()));
        if ("FULFILLS".equals(type) && serviceEndpoint(source) && capabilityEndpoint(target))
            return new Connector("Exhibits", ref(source.getId()), ref(target.getId()));
        return null;
    }

    private static boolean capabilityEndpoint(ArchitectureElement element) {
        return CAPABILITY.equals(element.getType()) && missing(element.getExtensions().get(TYPE)) && hasCatalogueBinding(element);
    }

    private static boolean serviceEndpoint(ArchitectureElement element) {
        return isService(element) && SERVICE.equals(element.getExtensions().get(TYPE));
    }

    static boolean hasCatalogueBinding(ArchitectureElement element) {
        if (element == null) return false;
        return validCatalogueGuid(element.getExtensions().get(CATALOGUE_GUID))
                && !missing(element.getExtensions().get(CATALOGUE_REFERENCE));
    }

    private static boolean validCatalogueGuid(String guid) {
        return guid != null && EA_GUID.matcher(guid).matches() && !ZERO_GUID.equals(guid);
    }

    static String r7Role(ArchitectureElement element) {
        if (element == null || CAPABILITY.equals(element.getType()) || isService(element)
                || "CommunicationsService".equals(element.getType())) return "";
        return Objects.toString(element.getExtensions().get(TYPE), "");
    }

    private static ArchitectureView selectedView(CanonicalArchitectureModel model, String viewId) {
        if (viewId == null) return null;
        List<ArchitectureView> views = model.getViews().stream().filter(view -> viewId.equals(view.getId())).toList();
        if (views.size() != 1) throw new IllegalArgumentException("Select one existing canonical view for C1-S1");
        return views.getFirst();
    }

    private static ArchitectureElement lookup(Map<String, ArchitectureElement> elements, String id) {
        return id == null ? null : elements.get(id);
    }

    private static boolean isService(ArchitectureElement element) {
        return element != null && element.getType() != null && SERVICE_TYPES.contains(element.getType());
    }

    private static String ref(String id) { return "element:" + id; }

    private static String relationId(ArchitectureRelation relation) {
        return relation.getSourceId() + " " + relation.getRelationType() + " " + relation.getTargetId();
    }

    private static boolean missing(String value) { return value == null || value.isBlank(); }

    static Integer version(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static final class ViewBuilder {
        private final Map<String, Element> elements = new TreeMap<>();
        private final List<Connector> connectors = new ArrayList<>();
        private final List<Issue> issues = new ArrayList<>();

        void add(ArchitectureElement element, String kind, String guid, String reference) {
            add(new Element(ref(element.getId()), kind, element.getTitle(), element.getDescription(), guid, reference, null, null));
        }

        void add(Element element) {
            if (elements.putIfAbsent(element.ref(), element) != null) return;
            if (missing(element.name()) || missing(element.notes())) {
                issue("NAME_AND_NOTES_REQUIRED", element.ref(), "Record the model element name and Notes.");
            }
        }

        void connect(String stereotype, String client, String supplier) {
            connectors.add(new Connector(stereotype, client, supplier));
        }

        void issue(String code, String object, String detail) {
            issues.add(new Issue(code, object, detail));
        }

        View build(String viewpoint, String name, List<String> path, List<String> requiredReviews) {
            return new View(viewpoint, name, path, issues.isEmpty() ? "REVIEW_REQUIRED" : "CHANGES_REQUIRED",
                    List.copyOf(elements.values()),
                    connectors.stream().distinct().sorted(Comparator.comparing(Connector::client)
                            .thenComparing(Connector::stereotype).thenComparing(Connector::supplier)).toList(),
                    issues.stream().distinct().sorted(Comparator.comparing(Issue::object).thenComparing(Issue::code)).toList(),
                    requiredReviews);
        }
    }
}
