package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.command.ArchitectureCommand.SetExchangeProperties;
import com.taxonomy.dsl.command.ArchitectureCommand.SetRequirementPlanning;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertArchitectureView;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertRequirementMapping;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.dsl.requirements.RequirementRealization;

import com.taxonomy.sparx.mapping.AdmbwRequirementField;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.taxonomy.dsl.admbw.AdmbwFffmlvProfile.CATALOGUE_GUID;
import static com.taxonomy.dsl.admbw.AdmbwFffmlvProfile.CATALOGUE_REFERENCE;
import static com.taxonomy.dsl.admbw.AdmbwFffmlvProfile.TYPE;

/** Pure projection of the canonical state that explicit guided ADMBw review would approve. */
public final class AdmbwPreparation {
    static final String VIEW_PROFILE = "x-exchange-admbw-profile";
    static final String VIEW_RELATIONS = "x-exchange-admbw-relation-members";
    private static final String VIEW_PROJECT = "x-exchange-admbw-project-code";
    private static final String NATIVE_PROJECT = "x-exchange-admbw-native-project";
    private static final String NATIVE_ROOT = "x-exchange-admbw-native-root";
    private static final String NATIVE_OWNER = "x-exchange-admbw-native-owner";
    private static final Set<String> NATIVE_IDENTITY_PINS = Set.of(NATIVE_PROJECT, NATIVE_ROOT, NATIVE_OWNER);
    private static final String CAPABILITY = "Capability";
    private static final String SERVICE = "ServiceSpecification";
    private static final String CONSTRAINT = "OperationalConstraint";
    private static final String PLAN_ID = "admbw-r7";
    private static final Set<String> SERVICE_TYPES = Set.of("CoreService", "COIService");
    private static final Set<String> SUBJECT_TYPES = Set.of("OperationalArchitecture", "OperationalRole");
    private static final Set<String> PART_TYPES = Set.of("OperationalRole", "ResourceRole");
    private static final Pattern PROJECT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern FULFILMENT = Pattern.compile(TOKEN.pattern() + " FULFILLS " + TOKEN.pattern());
    private static final Pattern GUID = Pattern.compile("\\{[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String ZERO_GUID = "{00000000-0000-0000-0000-000000000000}";

    public record Reference(String guid, String stereotype, String name, String notes, List<String> packagePath,
                            String rootGuid, String ownerGuid) {
        public Reference { packagePath = List.copyOf(packagePath); }
    }

    public record Basis(String sha256, String projectGuid, List<Reference> references) {
        public Basis { references = List.copyOf(references); }
    }

    public record Selection(String projectCode, String rootPackageGuid, String serviceId, String capabilityGuid,
                            String requirementId, String requirementType, String subjectGuid, String constraintText,
                            String evidenceQuote) {}

    public record Prepared(String dsl, String viewId, String requirementId, int requirementVersion,
                           String requirementContentHash, String basisSha256, List<String> notes,
                           AdmbwFffmlvProfile.Report assessment) {
        public Prepared { notes = List.copyOf(notes); }
    }

    private final com.taxonomy.dsl.model.CatalogueDefinitionResolver catalogues;

    public AdmbwPreparation() { this(com.taxonomy.dsl.model.CatalogueDefinitionResolver.standard()); }
    public AdmbwPreparation(com.taxonomy.dsl.model.CatalogueDefinitionResolver catalogues) {
        this.catalogues = Objects.requireNonNull(catalogues);
    }

    public Prepared prepare(String source, Selection selection, Basis basis) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(basis, "basis");
        String projectCode = require(PROJECT_CODE, selection.projectCode(), "project code");
        String root = guid(selection.rootPackageGuid(), "model root");
        String projectGuid = guid(basis.projectGuid(), "EA project");
        String basisHash = require(SHA256, basis.sha256(), "basis SHA-256");
        String service = require(TOKEN, selection.serviceId(), "service identity");
        String requirementId = require(TOKEN, selection.requirementId(), "requirement identity");
        var inventory = indexReferences(basis);
        Reference capability = reference(inventory, root, selection.capabilityGuid(), Set.of(CAPABILITY));
        Reference subject = reference(inventory, root, selection.subjectGuid(), SUBJECT_TYPES);
        requireReferenceText(capability);
        requireReferenceText(subject);
        String driver = text(selection.constraintText(), "operational constraint");
        String quote = text(selection.evidenceQuote(), "evidence quote");

        var blocks = ArchitectureSemanticPatch.index(source);
        BlockAst requirement = blocks.get("requirement:" + requirementId);
        String requirementText = unique(requirement, "text");
        String versionText = unique(requirement, "x-version");
        String contentHash = unique(requirement, "x-content-hash");
        Integer version = AdmbwFffmlvProfile.version(versionText);
        if (version == null || blank(requirementText) || !isUtf8(requirementText)
                || !RequirementRealization.textHash(requirementText).equals(contentHash)) {
            throw invalid("A canonical requirement with its exact version and text hash is required");
        }
        if (!requirementText.contains(quote)) throw invalid("The evidence quote must occur verbatim in the current requirement");

        List<ArchitectureCommand> changes = new ArrayList<>();
        declareService(blocks.get("element:" + service), service, changes);
        String capabilityId = bind(capability, blocks, basisHash, projectGuid, root, changes);
        String subjectId = bind(subject, blocks, basisHash, projectGuid, root, changes);
        var relation = new RelationKey(service, "FULFILLS", capabilityId);
        reviewFulfilment(blocks.get("relation:" + relation.id()), relation, changes);
        String constraintId = constraint(blocks, requirementId, versionText, contentHash, driver, quote, changes);
        planRequirement(requirement, requirementId, selection.requirementType(), constraintId, subjectId,
                versionText, contentHash, changes);
        if (!blocks.containsKey("mapping:" + requirementId + " -> " + service)) {
            changes.add(new UpsertRequirementMapping(requirementId, service,
                    "Guided ADMBw preparation links the selected requirement to its reviewed service.",
                    Map.of()));
        }

        String viewId = "admbw-delivery-" + projectCode + "-" + bare(root);
        deliveryView(blocks.get("view:" + viewId), viewId, projectCode, projectGuid, root,
                relation, subjectId, constraintId, changes);
        var model = new ArchitectureDslCommands().model(source);
        var commands = new ArchitectureDslCommands(catalogues.forModel(model), code -> catalogues.assignmentTarget(model, code));
        String prepared = commands.applyAll(source, changes).dsl();
        var assessment = new AdmbwFffmlvProfile().assess(prepared, projectCode, viewId);
        var missingPins = verifyDeliveryReferences(inventory, root, projectGuid, assessment, ArchitectureSemanticPatch.index(prepared));
        prepared = commands.applyAll(prepared, missingPins).dsl();
        var notes = new ArrayList<>(List.of(
                "This preview represents the changes after explicit review of service fulfilment and requirement derivation.",
                "The operational constraint is a proposed restatement grounded in the quoted requirement; independent upstream evidence was not supplied.",
                "The selected QEA reference identities apply to the inspected basis; independent architecture review remains required."
        ));
        if (!quote.equals(requirementText)) {
            notes.add("The quote is an excerpt from the requirement. Review whether the operational constraint supports the entire requirement. "
                    + "If it supports a narrower scope, select a separately versioned requirement for that scope.");
        }
        return new Prepared(prepared, viewId, requirementId, version, contentHash, basisHash, notes, assessment);
    }

    private static void declareService(BlockAst block, String service, List<ArchitectureCommand> changes) {
        String type = elementType(block);
        if (type == null || !SERVICE_TYPES.contains(type)) throw invalid("Select an existing CoreService or COIService");
        String previous = unique(block, TYPE);
        if (!blank(previous) && !SERVICE.equals(previous)) throw invalid("The service already has a different ADMBw stereotype");
        if (!SERVICE.equals(previous)) changes.add(new UpdateArchitectureElement(service, type, Map.of(TYPE, SERVICE)));
    }

    private static Map<String, List<Reference>> indexReferences(Basis basis) {
        Map<String, List<Reference>> index = new HashMap<>();
        for (Reference reference : basis.references()) {
            if (reference.guid() != null) index.computeIfAbsent(reference.guid().toUpperCase(Locale.ROOT),
                    ignored -> new ArrayList<>()).add(reference);
        }
        return index;
    }

    private static Reference reference(Map<String, List<Reference>> inventory, String root, String selected, Set<String> stereotypes) {
        String requested = guid(selected, "reference");
        var matches = inventory.getOrDefault(requested, List.of());
        if (matches.size() != 1) throw invalid("Select one unambiguous native QEA reference");
        Reference entry = matches.getFirst();
        if (!root.equals(guid(entry.rootGuid(), "reference root"))) throw invalid("The native reference belongs to a different model root");
        if (entry.stereotype() == null || !stereotypes.contains(entry.stereotype())) {
            throw invalid("Select a native QEA reference with the required ADMBw stereotype");
        }
        if (!blank(entry.ownerGuid())) {
            if (requested.equals(guid(entry.ownerGuid(), "reference owner"))) throw invalid("A native reference cannot own itself");
        } else if (PART_TYPES.contains(entry.stereotype())) {
            throw invalid("The selected native " + entry.stereotype() + " needs its EA Part owner");
        }
        return entry;
    }

    private static void requireReferenceText(Reference entry) {
        text(entry.name(), "native reference name");
        if (entry.notes() != null) checkText(entry.notes(), "native reference notes");
        if (entry.packagePath().isEmpty()) throw invalid("The native reference needs its inspected package path");
        entry.packagePath().forEach(name -> text(name, "native package name"));
    }

    /** A guided selection remains authoritative when the same checkpoint uses the ordinary export route. */
    public static boolean requiresBasisInspection(String source, AdmbwFffmlvProfile.Report assessment) {
        var blocks = ArchitectureSemanticPatch.index(source);
        var view = blocks.get("view:" + assessment.sourceViewId());
        return view != null && view.property(VIEW_PROFILE) != null
                || Stream.concat(assessment.capabilityToService().elements().stream(), assessment.requirementDerivation().elements().stream())
                    .map(element -> blocks.get(element.ref())).filter(Objects::nonNull)
                    .anyMatch(block -> NATIVE_IDENTITY_PINS.stream().anyMatch(key -> block.property(key) != null));
    }

    public static void validateDeliveryBasis(String source, AdmbwFffmlvProfile.Report assessment,
                                             String rootPackageGuid, Basis basis) {
        var blocks = ArchitectureSemanticPatch.index(source);
        String root = guid(rootPackageGuid, "model root"), project = guid(basis.projectGuid(), "EA project");
        var view = blocks.get("view:" + assessment.sourceViewId());
        if (view != null && view.property(VIEW_PROFILE) != null
                && (!AdmbwFffmlvProfile.ID.equals(unique(view, VIEW_PROFILE))
                    || !assessment.projectCode().equals(unique(view, VIEW_PROJECT))
                    || !root.equals(unique(view, NATIVE_ROOT)) || !project.equals(unique(view, NATIVE_PROJECT)))) {
            throw invalid("The delivery project and native model root must match the reviewed guided view");
        }
        verifyDeliveryReferences(indexReferences(basis), root, project, assessment, blocks);
    }

    /** Verifies delivered native identities and returns commands to retain newly observed pins on acceptance. */
    private static List<ArchitectureCommand> verifyDeliveryReferences(Map<String, List<Reference>> inventory,
            String root, String project, AdmbwFffmlvProfile.Report assessment, Map<String, BlockAst> blocks) {
        Map<String, ArchitectureCommand> missingPins = new LinkedHashMap<>();
        Stream.concat(assessment.capabilityToService().elements().stream(), assessment.requirementDerivation().elements().stream())
                .filter(element -> element.catalogueGuid() != null)
                .forEach(element -> {
                    try {
                        var inspected = reference(inventory, root, element.catalogueGuid(), Set.of(element.stereotype()));
                        var recorded = blocks.get(element.ref());
                        if (recorded == null) throw invalid("The canonical element is missing");
                        var observed = Map.of(NATIVE_PROJECT, project, NATIVE_ROOT, root,
                                NATIVE_OWNER, blank(inspected.ownerGuid()) ? "" : guid(inspected.ownerGuid(), "reference owner"));
                        Map<String, String> absent = new LinkedHashMap<>();
                        for (var pin : observed.entrySet()) {
                            String prior = unique(recorded, pin.getKey());
                            if (prior == null) absent.put(pin.getKey(), pin.getValue());
                            else if (!prior.equalsIgnoreCase(pin.getValue())) {
                                throw invalid("The native reference changed its reviewed model or Part owner");
                            }
                        }
                        if (!absent.isEmpty()) missingPins.put(element.ref(),
                                new SetExchangeProperties("element", recorded.getHeaderTokens().getFirst(), absent));
                    } catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException("Delivery reference " + element.ref() + " ("
                                + element.stereotype() + ", " + element.catalogueGuid()
                                + ") is unavailable in the inspected model root: " + failure.getMessage(), failure);
                    }
                });
        return List.copyOf(missingPins.values());
    }

    private static String bind(Reference reference, Map<String, BlockAst> blocks, String basisHash,
                               String projectGuid, String root, List<ArchitectureCommand> changes) {
        String guid = guid(reference.guid(), "reference");
        String stableId = "admbw-native-" + bare(guid);
        var matches = blocks.values().stream().filter(block -> "element".equals(block.getKind()))
                .filter(block -> block.propertyValues(CATALOGUE_GUID).stream().anyMatch(guid::equalsIgnoreCase)).toList();
        if (matches.size() > 1) throw invalid("The native GUID is bound to more than one canonical element");
        BlockAst occupied = blocks.get("element:" + stableId);
        String type = CAPABILITY.equals(reference.stereotype()) ? CAPABILITY : "Component";
        String stereotype = CAPABILITY.equals(type) ? "" : reference.stereotype();
        if (!matches.isEmpty()) {
            BlockAst existing = matches.getFirst();
            String existingType = elementType(existing);
            String existingStereotype = unique(existing, TYPE);
            boolean compatible = CAPABILITY.equals(type)
                    ? CAPABILITY.equals(existingType) && blank(existingStereotype)
                    : existingType != null && !CAPABILITY.equals(existingType) && !SERVICE_TYPES.contains(existingType)
                            && !"CommunicationsService".equals(existingType) && stereotype.equals(existingStereotype);
            if (occupied != null && occupied != existing || !compatible || unique(existing, CATALOGUE_GUID) == null
                    || blank(unique(existing, CATALOGUE_REFERENCE))) {
                throw invalid("The native GUID conflicts with an existing canonical binding");
            }
            return existing.getHeaderTokens().getFirst();
        }
        if (occupied != null) throw invalid("The native reference identity collides with an existing canonical element");

        var properties = new LinkedHashMap<String, String>();
        properties.put("title", reference.name());
        if (!blank(reference.notes())) properties.put("description", reference.notes());
        if (!stereotype.isEmpty()) properties.put(TYPE, stereotype);
        properties.put(CATALOGUE_GUID, guid);
        properties.put(CATALOGUE_REFERENCE, "Inspected QEA basis SHA-256 " + basisHash + "; project " + projectGuid
                + "; model root " + root + "; package " + String.join(" / ", reference.packagePath()));
        changes.add(new CreateArchitectureElement(stableId, type, properties));
        return stableId;
    }

    private static void reviewFulfilment(BlockAst existing, RelationKey relation, List<ArchitectureCommand> changes) {
        if (existing == null) {
            changes.add(new CreateArchitectureRelation(relation, "accepted"));
            return;
        }
        String status = unique(existing, "status");
        if ("proposed".equals(status)) changes.add(new UpdateArchitectureRelation(relation, "accepted"));
        else if (!"accepted".equals(status)) throw invalid("The existing FULFILLS relation cannot be accepted without a separate change");
    }

    private static String constraint(Map<String, BlockAst> blocks, String requirement, String version, String hash,
                                     String driver, String quote, List<ArchitectureCommand> changes) {
        String id = "admbw-constraint-" + RequirementRealization.textHash(requirement + "\n" + version + "\n" + hash);
        String description = driver + "\n\nCanonical requirement " + requirement + ", version " + version
                + ", SHA-256 " + hash + ".\nExact requirement quote:\n" + quote;
        BlockAst existing = blocks.get("element:" + id);
        if (existing == null) {
            changes.add(new CreateArchitectureElement(id, "Component", Map.of(
                    "title", "Operational constraint for " + requirement, "description", description, TYPE, CONSTRAINT)));
        } else if (!"Component".equals(elementType(existing)) || !CONSTRAINT.equals(unique(existing, TYPE))
                || !description.equals(unique(existing, "description"))) {
            throw invalid("The current requirement revision already has a conflicting operational constraint");
        }
        return id;
    }

    private static void planRequirement(BlockAst requirement, String requirementId, String kind, String constraint,
                                        String subject, String version, String hash, List<ArchitectureCommand> changes) {
        if (kind == null) throw invalid("Select a functional or nonfunctional requirement type");
        var values = Map.of(AdmbwRequirementProfile.KIND, kind, AdmbwRequirementProfile.DERIVED_FROM, constraint,
                AdmbwRequirementProfile.SUBJECT, subject, AdmbwRequirementProfile.BOUND_VERSION, version,
                AdmbwRequirementProfile.BOUND_HASH, hash);
        new AdmbwRequirementProfile().validate(values);
        var entries = PlanningInformation.decode(requirement.getExtensions());
        var existing = entries.stream().filter(entry -> AdmbwRequirementProfile.ID.equals(entry.profile())).toList();
        if (existing.size() > 1) throw invalid("The requirement contains ambiguous ADMBw R7 assignments");
        if (existing.isEmpty()) {
            if (entries.stream().anyMatch(entry -> PLAN_ID.equals(entry.id()))) throw invalid("The ADMBw R7 planning identity is already occupied");
            changes.add(new SetRequirementPlanning(requirementId,
                    new PlanningEntry(PLAN_ID, AdmbwRequirementProfile.ID, AdmbwRequirementProfile.VERSION, "MANUAL", values)));
            return;
        }
        PlanningEntry entry = existing.getFirst();
        new AdmbwRequirementProfile().validate(entry.values());
        var previous = new HashMap<>(entry.values());
        boolean imported = "true".equals(previous.remove(AdmbwRequirementProfile.IMPORTED));
        if (!imported) {
            for (var field : AdmbwRequirementField.values()) previous.remove(field.planningKey());
        }
        if (!AdmbwRequirementProfile.VERSION.equals(entry.version()) || !values.equals(previous)) {
            throw invalid("An existing ADMBw R7 assignment conflicts with the selected requirement revision or derivation");
        }
        if (imported) changes.add(new SetRequirementPlanning(requirementId,
                new PlanningEntry(entry.id(), entry.profile(), entry.version(), "MANUAL", values)));
    }

    private static void deliveryView(BlockAst existing, String viewId, String projectCode, String projectGuid, String root,
                                     RelationKey relation, String subject, String constraint, List<ArchitectureCommand> changes) {
        String title = "ADMBw delivery | " + projectCode;
        String description = "Reviewed selection for the supported ADMBw C1–S1 and R7 delivery views.";
        var members = new LinkedHashSet<String>();
        var reviewed = new LinkedHashSet<String>();
        if (existing != null) {
            if (!AdmbwFffmlvProfile.ID.equals(unique(existing, VIEW_PROFILE)) || !projectCode.equals(unique(existing, VIEW_PROJECT))
                    || !root.equals(unique(existing, NATIVE_ROOT)) || !projectGuid.equals(unique(existing, NATIVE_PROJECT))
                    || existing.getProperties().stream().anyMatch(property -> property.key().startsWith("x-analysis-"))) {
                throw invalid("The dedicated delivery view identity belongs to another view");
            }
            String recorded = unique(existing, VIEW_RELATIONS);
            if (recorded == null) throw invalid("The delivery view is missing its reviewed relation selection");
            if (!recorded.isEmpty()) {
                for (String key : recorded.split("\\n", -1)) {
                    if (!FULFILMENT.matcher(key).matches() || !reviewed.add(key)) {
                        throw invalid("The delivery view has ambiguous reviewed relation identities");
                    }
                }
            }
            title = unique(existing, "title");
            description = Objects.toString(unique(existing, "description"), "");
            members.addAll(existing.propertyValues("include"));
        }
        members.addAll(List.of(relation.sourceId(), relation.targetId(), subject, constraint));
        reviewed.add(relation.id());
        changes.add(new UpsertArchitectureView(viewId, title, description, List.copyOf(members),
                Map.of(VIEW_PROFILE, AdmbwFffmlvProfile.ID, VIEW_PROJECT, projectCode, NATIVE_ROOT, root,
                        NATIVE_PROJECT, projectGuid, VIEW_RELATIONS, String.join("\n", reviewed))));
    }

    private static String elementType(BlockAst block) {
        if (block == null || block.getHeaderTokens().size() != 3 || !"type".equals(block.getHeaderTokens().get(1))) return null;
        return block.getHeaderTokens().get(2);
    }

    private static String unique(BlockAst block, String property) {
        if (block == null) return null;
        var values = block.propertyValues(property);
        if (values.size() > 1) throw invalid("Ambiguous canonical property: " + property);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String bare(String guid) {
        return guid.substring(1, guid.length() - 1).toLowerCase(Locale.ROOT);
    }

    private static String guid(String value, String field) {
        String normalized = require(GUID, value, field + " GUID").toUpperCase(Locale.ROOT);
        if (ZERO_GUID.equals(normalized)) throw invalid("Invalid " + field + " GUID");
        return normalized;
    }

    private static String require(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) throw invalid("Invalid " + field);
        return value;
    }

    private static String text(String value, String field) {
        if (blank(value)) throw invalid("Provide a nonblank " + field);
        checkText(value, field);
        return value;
    }

    private static void checkText(String value, String field) {
        if (!isUtf8(value) || value.length() > 8000 || value.chars().anyMatch(ch ->
                Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t' || ch == 0xfffe || ch == 0xffff)) {
            throw invalid("Invalid or oversized " + field);
        }
    }

    private static boolean isUtf8(String value) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
