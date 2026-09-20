package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.validation.DslValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict commands layered over the tolerant import parser. No persistence or renderer dependencies. */
public final class ArchitectureDslCommands {
    public static final Set<String> ELEMENT_PROPERTIES = Set.of("title", "description",
            "x-editor-status", "x-owner", "x-responsible-organization", "x-product-reference", "x-system-reference");

    public record Change(String dsl, List<ArchitectureSemanticPatch.BlockChange> changes) {
        public Change { changes = List.copyOf(changes); }
        public List<String> changedIds() { return changes.stream().map(ArchitectureSemanticPatch.BlockChange::id).toList(); }
        public List<String> getChangedIds() { return changedIds(); }
    }

    public static final class CommandProblem extends IllegalArgumentException {
        private final String code;
        private final String field;
        private final List<String> dependencies;
        public CommandProblem(String code, String field, String message, List<String> dependencies) {
            super(message);
            this.code = code;
            this.field = field;
            this.dependencies = List.copyOf(dependencies);
        }
        public String code() { return code; }
        public String field() { return field; }
        public List<String> dependencies() { return dependencies; }
    }

    public CanonicalArchitectureModel model(String source) {
        return new AstToModelMapper().map(new TaxDslParser().parse(source));
    }

    public Change apply(String source, ArchitectureCommand command) {
        Objects.requireNonNull(source, "source");
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(source);
        String next = switch (command) {
            case CreateArchitecturePackage create -> createPackage(source, blocks, create);
            case UpdateArchitecturePackage update -> updatePackage(source, blocks, update);
            case SetArchitecturePackagePlacements placements -> placePackages(source, blocks, placements);
            case DeleteArchitecturePackage delete -> deletePackage(source, blocks, delete.id());
            case UpsertRequirementMapping mapping -> upsertMapping(source, blocks, mapping);
            case DeleteRequirementMapping mapping -> deleteMapping(source, blocks, mapping);
            case CreateArchitectureElement create -> createElement(source, blocks, create);
            case UpdateArchitectureElement update -> updateElement(source, blocks, update);
            case DeleteArchitectureElement delete -> deleteElement(source, blocks, delete.id());
            case CreateArchitectureRelation create -> createRelation(source, blocks, create.relation(), create.status());
            case UpdateArchitectureRelation update -> updateRelation(source, blocks, update.relation(), update.status());
            case DeleteArchitectureRelation delete -> deleteRelation(source, blocks, delete.relation());
            case MoveOrGroupElement move -> move(source, blocks, move);
            case SetExchangeProperties exchange -> exchangeProperties(source, blocks, exchange);
            case ClearArchitectureElementProperties clear -> clearElementProperties(source, blocks, clear);
            case UpsertArchitectureView view -> exchangeView(source, blocks, view);
            case DeleteArchitectureView view -> ArchitectureSemanticPatch.replace(source, require(blocks, "view:" + view.id()), null);
            case StoreExchangeEvidence evidence -> exchangeEvidence(source, blocks, evidence);
        };
        return new Change(next, ArchitectureSemanticPatch.between(source, next));
    }

    /** Rechecks inverse preconditions and the resulting model, never restores a whole document. */
    public Change inverse(String current, String before, String after) {
        List<ArchitectureSemanticPatch.BlockChange> original = ArchitectureSemanticPatch.between(before, after);
        if (original.isEmpty()) throw problem("NOT_UNDOABLE", "targetOperationId", "Target has no semantic changes");
        if (original.stream().anyMatch(change -> !change.id().startsWith("element:")
                && !change.id().startsWith("relation:") && !change.id().startsWith("package:")
                && !change.id().startsWith("mapping:"))) {
            throw problem("NOT_UNDOABLE", "targetOperationId", "Target contains unsupported semantic objects");
        }
        String next = ArchitectureSemanticPatch.inverse(current, before, original);
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(next);
        // Deletion inverses must not silently strand mappings, views or evidence added later.
        Map<String, BlockAst> currentBlocks = ArchitectureSemanticPatch.index(current);
        for (var change : ArchitectureSemanticPatch.between(current, next)) {
            if (change.after() == null) {
                BlockAst removed = currentBlocks.get(change.id());
                List<String> dependencies = dependencies(blocks, removed);
                requireNoDependencies(change.id(), dependencies);
            }
        }
        Set<String> changed = new LinkedHashSet<>();
        original.forEach(change -> changed.add(change.id()));
        for (BlockAst block : blocks.values()) {
            if (!"relation".equals(block.getKind())) continue;
            RelationKey key = relationKey(block);
            if (changed.contains(ArchitectureSemanticPatch.key(block)) || changed.contains("element:" + key.sourceId())
                    || changed.contains("element:" + key.targetId())) validateRelation(blocks, key);
        }
        for (BlockAst mapping : blocks.values()) if ("mapping".equals(mapping.getKind()) && changed.contains(ArchitectureSemanticPatch.key(mapping))) {
            List<String> header = mapping.getHeaderTokens();
            if (header.size() != 3 || !"->".equals(header.get(1))) throw problem("INVALID_HEADER", "mapping", "Malformed requirement mapping");
            require(blocks, "requirement:" + header.get(0)); require(blocks, "element:" + header.get(2));
        }
        validateContainment(blocks);
        validatePackages(next);
        return new Change(next, ArchitectureSemanticPatch.between(current, next));
    }

    private void validatePackages(String dsl) {
        com.taxonomy.dsl.model.ArchitecturePackageHierarchy.validate(model(dsl),
                (code, message) -> { throw problem(code, "placements", message); });
    }

    private String createPackage(String source, Map<String, BlockAst> blocks, CreateArchitecturePackage command) {
        token(command.id(), "id");
        if (blocks.containsKey("package:" + command.id())) throw problem("DUPLICATE_ID", "id", "Package already exists");
        packageProperties(command.properties(), true);
        int position = (int) blocks.values().stream().filter(b -> "package".equals(b.getKind()) && "".equals(b.property("parent"))
                || "element".equals(b.getKind()) && "".equals(b.property("package"))).count();
        Map<String, String> values = new LinkedHashMap<>(command.properties());
        values.put("parent", ""); values.put("position", Integer.toString(position));
        String next = ArchitectureSemanticPatch.replace(source, null, block("package", List.of(command.id()), values, null));
        validatePackages(next); return next;
    }

    private String updatePackage(String source, Map<String, BlockAst> blocks, UpdateArchitecturePackage command) {
        packageProperties(command.properties(), false);
        BlockAst existing = require(blocks, "package:" + command.id());
        return ArchitectureSemanticPatch.replace(source, existing, block("package", existing.getHeaderTokens(), command.properties(), existing));
    }

    private static void packageProperties(Map<String, String> values, boolean creating) {
        if (!Set.of("title", "description").containsAll(values.keySet())) throw problem("READ_ONLY_PROPERTY", "properties", "Package placement requires the atomic placement command");
        requireProperties(values, creating);
    }

    private String deletePackage(String source, Map<String, BlockAst> blocks, String id) {
        BlockAst existing = require(blocks, "package:" + id);
        requireNoDependencies(id, dependencies(blocks, existing));
        String parent = existing.property("parent");
        int position = Integer.parseInt(existing.property("position"));
        String next = ArchitectureSemanticPatch.replace(source, existing, null);
        for (BlockAst sibling : ArchitectureSemanticPatch.index(next).values()) {
            String property = "package".equals(sibling.getKind()) ? "parent" : "element".equals(sibling.getKind()) ? "package" : null;
            if (property != null && Objects.equals(parent, sibling.property(property)) && Integer.parseInt(sibling.property("position")) > position)
                next = ArchitectureSemanticPatch.replace(next, require(ArchitectureSemanticPatch.index(next), ArchitectureSemanticPatch.key(sibling)),
                        block(sibling.getKind(), sibling.getHeaderTokens(), Map.of("position", Integer.toString(Integer.parseInt(sibling.property("position")) - 1)), sibling));
        }
        validatePackages(next); return next;
    }

    private String placePackages(String source, Map<String, BlockAst> blocks, SetArchitecturePackagePlacements command) {
        Map<String, PackagePlacement> requested = new LinkedHashMap<>();
        for (String scope : command.completeParentScopes()) if (!scope.isEmpty()) require(blocks, "package:" + scope);
        for (PackagePlacement placement : command.placements()) {
            if (placement.kind() == null) throw problem("INVALID_VALUE", "kind", "Package member kind is required");
            token(placement.memberId(), "memberId");
            String key = placement.kind() == PackageMemberKind.PACKAGE ? "package:" : "element:";
            BlockAst existing = require(blocks, key + placement.memberId());
            if (requested.putIfAbsent(key + placement.memberId(), placement) != null) throw problem("DUPLICATE_ID", "memberId", "Member placed more than once");
            String oldParent = existing.property(placement.kind() == PackageMemberKind.PACKAGE ? "parent" : "package");
            String newParent = placement.parentPackageId();
            if (newParent == null && (placement.kind() != PackageMemberKind.ELEMENT || placement.position() != -1)
                    || newParent != null && placement.position() < 0) throw problem("PACKAGE_POSITION", "position", "Only an element may detach using null parent and position -1");
            if (oldParent != null && !command.completeParentScopes().contains(oldParent)
                    || newParent != null && !command.completeParentScopes().contains(newParent))
                throw problem("INCOMPLETE_PACKAGE_SCOPE", "completeParentScopes", "Both old and new parent scopes must be complete");
        }
        for (var entry : blocks.entrySet()) {
            BlockAst b = entry.getValue();
            String parent = "package".equals(b.getKind()) ? b.property("parent") : "element".equals(b.getKind()) ? b.property("package") : null;
            if (parent != null && command.completeParentScopes().contains(parent) && !requested.containsKey(entry.getKey()))
                throw problem("INCOMPLETE_PACKAGE_SCOPE", "placements", "Retain every sibling or detach it explicitly");
        }
        String next = source;
        for (var entry : requested.entrySet()) {
            PackagePlacement placement = entry.getValue();
            BlockAst existing = require(ArchitectureSemanticPatch.index(next), entry.getKey());
            String parentProperty = placement.kind() == PackageMemberKind.PACKAGE ? "parent" : "package";
            List<PropertyAst> values = new ArrayList<>(existing.getProperties().stream()
                    .filter(p -> !p.key().equals(parentProperty) && !p.key().equals("position")).toList());
            if (placement.parentPackageId() != null) {
                values.add(new PropertyAst(parentProperty, placement.parentPackageId(), null));
                values.add(new PropertyAst("position", Integer.toString(placement.position()), null));
            }
            next = ArchitectureSemanticPatch.replace(next, existing, new BlockAst(existing.getKind(), existing.getHeaderTokens(), values, existing.getChildren(), existing.getExtensions(), null));
        }
        validatePackages(next); return next;
    }

    private static void mappingOwnership(BlockAst existing) {
        if (existing != null && (existing.property("x-project-key") != null || existing.property("x-exchange-id") == null))
            throw problem("REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT", "mapping", "An exchange mapping cannot overwrite an analysis-owned mapping");
    }
    /** Pure forward-plan validation; this never supplies a synthetic requirement to application. */
    public void validatePlannedRequirementMapping(String source, UpsertRequirementMapping command, Set<String> plannedRequirements) {
        validateMapping(ArchitectureSemanticPatch.index(source), command, plannedRequirements);
    }
    private static void validateMapping(Map<String, BlockAst> blocks, UpsertRequirementMapping command, Set<String> plannedRequirements) {
        token(command.requirementIdentity(), "requirementIdentity"); token(command.elementId(), "elementId");
        if (!plannedRequirements.contains(command.requirementIdentity())) require(blocks, "requirement:" + command.requirementIdentity());
        require(blocks, "element:" + command.elementId());
        requireExchangeProperties(command.exchangeProperties());
        if (command.rationale() == null || command.rationale().length() > 8000) throw problem("INVALID_VALUE", "rationale", "A bounded rationale is required");
        mappingOwnership(blocks.get("mapping:" + command.requirementIdentity() + " -> " + command.elementId()));
    }
    private static String upsertMapping(String source, Map<String, BlockAst> blocks, UpsertRequirementMapping command) {
        validateMapping(blocks, command, Set.of());
        BlockAst existing = blocks.get("mapping:" + command.requirementIdentity() + " -> " + command.elementId());
        Map<String, String> properties = new LinkedHashMap<>(command.exchangeProperties());
        properties.put("source", "exchange"); properties.put("x-exchange-rationale", command.rationale());
        if (!properties.containsKey("x-exchange-id")) properties.put("x-exchange-id", command.requirementIdentity() + " -> " + command.elementId());
        return ArchitectureSemanticPatch.replace(source, existing, block("mapping", List.of(command.requirementIdentity(), "->", command.elementId()), properties, existing));
    }
    private static String deleteMapping(String source, Map<String, BlockAst> blocks, DeleteRequirementMapping command) {
        BlockAst existing = require(blocks, "mapping:" + command.requirementIdentity() + " -> " + command.elementId());
        mappingOwnership(existing);
        return ArchitectureSemanticPatch.replace(source, existing, null);
    }

    private String createElement(String source, Map<String, BlockAst> blocks, CreateArchitectureElement command) {
        token(command.id(), "id");
        requireType(command.type());
        if (blocks.containsKey("element:" + command.id())) throw problem("DUPLICATE_ID", "id", "Element already exists");
        requireProperties(command.properties(), true);
        return ArchitectureSemanticPatch.replace(source, null,
                block("element", List.of(command.id(), "type", command.type()), command.properties(), null));
    }

    private static String exchangeProperties(String source, Map<String, BlockAst> blocks, SetExchangeProperties command) {
        if (!Set.of("element", "relation", "view", "package", "mapping").contains(command.objectKind()))
            throw problem("INVALID_EXCHANGE_TARGET", "objectKind", "Exchange metadata requires a semantic target");
        requireExchangeProperties(command.properties());
        BlockAst existing = require(blocks, command.objectKind() + ":" + command.id());
        BlockAst replacement = "view".equals(existing.getKind())
                ? viewWithExchangeProperties(existing, command.properties())
                : block(existing.getKind(), existing.getHeaderTokens(), command.properties(), existing);
        return ArchitectureSemanticPatch.replace(source, existing, replacement);
    }

    private static BlockAst viewWithExchangeProperties(BlockAst existing, Map<String, String> changes) {
        requireUnambiguousViewProperties(existing);
        List<PropertyAst> values = new ArrayList<>();
        for (PropertyAst property : existing.getProperties()) {
            if (!changes.containsKey(property.key())) values.add(property);
        }
        changes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(new PropertyAst(entry.getKey(), entry.getValue(), null)));
        Map<String, String> extensions = new LinkedHashMap<>(existing.getExtensions());
        extensions.putAll(changes);
        return new BlockAst(existing.getKind(), existing.getHeaderTokens(), values,
                existing.getChildren(), extensions, null);
    }

    private static void requireUnambiguousViewProperties(BlockAst view) {
        Set<String> seen = new LinkedHashSet<>();
        for (PropertyAst property : view.getProperties()) {
            if (!"include".equals(property.key()) && !seen.add(property.key())) {
                throw problem("DUPLICATE_PROPERTY", property.key(), "Ambiguous property on edited object");
            }
        }
    }

    private static String exchangeView(String source, Map<String, BlockAst> blocks, UpsertArchitectureView command) {
        token(command.id(), "viewId");
        if (command.title() == null || command.title().isBlank() || command.title().length() > 8000)
            throw problem("INVALID_VALUE", "title", "View title must be bounded and nonempty");
        requireExchangeProperties(command.properties());
        if (command.description() == null || command.description().length() > 8000)
            throw problem("INVALID_VALUE", "description", "View description must be bounded");
        if (command.members().size() > 10000) throw problem("ITEM_LIMIT", "members", "View has too many members");
        command.members().forEach(id -> require(blocks, "element:" + id));
        BlockAst existing = blocks.get("view:" + command.id());
        List<PropertyAst> values = new ArrayList<>();
        if (existing != null) {
            requireUnambiguousViewProperties(existing);
            existing.getProperties().stream()
                    .filter(p -> !Set.of("title", "description", "include").contains(p.key())
                            && !command.properties().containsKey(p.key()))
                    .forEach(values::add);
        }
        values.add(new PropertyAst("title", command.title(), null));
        values.add(new PropertyAst("description", command.description(), null));
        command.members().stream().distinct().forEach(id -> values.add(new PropertyAst("include", id, null)));
        command.properties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> values.add(new PropertyAst(e.getKey(), e.getValue(), null)));
        return ArchitectureSemanticPatch.replace(source, existing, new BlockAst("view", List.of(command.id()), values, List.of(), command.properties(), null));
    }

    private static String clearElementProperties(String source, Map<String, BlockAst> blocks, ClearArchitectureElementProperties command) {
        BlockAst existing = require(blocks, "element:" + command.id());
        if (command.properties().contains("title") || !ELEMENT_PROPERTIES.containsAll(command.properties()))
            throw problem("READ_ONLY_PROPERTY", "properties", "Only optional editable properties can be cleared");
        List<PropertyAst> values = existing.getProperties().stream().filter(p -> !command.properties().contains(p.key())).toList();
        Map<String, String> extensions = new LinkedHashMap<>(existing.getExtensions()); command.properties().forEach(extensions::remove);
        return ArchitectureSemanticPatch.replace(source, existing, new BlockAst(existing.getKind(), existing.getHeaderTokens(), values, existing.getChildren(), extensions, null));
    }

    private static String exchangeEvidence(String source, Map<String, BlockAst> blocks, StoreExchangeEvidence command) {
        token(command.id(), "exchangeId"); token(command.profile(), "profile"); token(command.profileVersion(), "profileVersion");
        if (command.source() == null || command.source().length() > 16 * 1024 * 1024 || command.fingerprint() == null || !command.fingerprint().matches("[a-f0-9]{64}"))
            throw problem("INVALID_EXCHANGE_EVIDENCE", "source", "Exchange evidence must be bounded and fingerprinted");
        BlockAst existing = blocks.get("exchange:" + command.id());
        return ArchitectureSemanticPatch.replace(source, existing, block("exchange", List.of(command.id()),
                Map.of("profile", command.profile(), "profileVersion", command.profileVersion(), "fingerprint", command.fingerprint(), "source", command.source()), existing));
    }

    private static void requireExchangeProperties(Map<String, String> properties) {
        if (properties.size() > 128) throw problem("ITEM_LIMIT", "properties", "Too many exchange properties");
        properties.forEach((key, value) -> {
            if (!key.matches("x-exchange-[A-Za-z0-9_.-]{1,100}") || value.length() > 500000)
                throw problem("INVALID_EXCHANGE_PROPERTY", "properties", "Only bounded exchange extension properties are permitted");
        });
    }

    private String updateElement(String source, Map<String, BlockAst> blocks, UpdateArchitectureElement command) {
        BlockAst existing = require(blocks, "element:" + command.id());
        String type = command.type() == null ? elementType(existing) : command.type();
        requireType(type);
        requireProperties(command.properties(), false);
        BlockAst replacement = block("element", List.of(command.id(), "type", type), command.properties(), existing);
        Map<String, BlockAst> changed = new LinkedHashMap<>(blocks);
        changed.put("element:" + command.id(), replacement);
        for (BlockAst candidate : changed.values()) {
            if ("relation".equals(candidate.getKind())) {
                RelationKey relation = relationKey(candidate);
                if (relation.sourceId().equals(command.id()) || relation.targetId().equals(command.id())) {
                    validateRelation(changed, relation);
                }
            }
        }
        return ArchitectureSemanticPatch.replace(source, existing, replacement);
    }

    private String deleteElement(String source, Map<String, BlockAst> blocks, String id) {
        BlockAst existing = require(blocks, "element:" + id);
        if (existing.property("package") != null) throw new CommandProblem("DEPENDENCIES_EXIST", id,
                "Detach the element from its package before deletion", List.of("package:" + existing.property("package")));
        requireNoDependencies(id, dependencies(blocks, existing));
        return ArchitectureSemanticPatch.replace(source, existing, null);
    }

    private String createRelation(String source, Map<String, BlockAst> blocks, RelationKey relation, String status) {
        String key = "relation:" + relation.id();
        if (blocks.containsKey(key)) throw problem("DUPLICATE_RELATION", "relation", "Relation already exists");
        validateRelation(blocks, relation);
        requireStatus(status);
        BlockAst created = block("relation", List.of(relation.sourceId(), relation.relationType(), relation.targetId()),
                Map.of("status", status == null ? "proposed" : status), null);
        Map<String, BlockAst> changed = new LinkedHashMap<>(blocks);
        changed.put(key, created);
        validateContainment(changed);
        return ArchitectureSemanticPatch.replace(source, null, created);
    }

    private String updateRelation(String source, Map<String, BlockAst> blocks, RelationKey relation, String status) {
        BlockAst existing = require(blocks, "relation:" + relation.id());
        validateRelation(blocks, relation);
        requireStatus(status);
        return ArchitectureSemanticPatch.replace(source, existing,
                block("relation", existing.getHeaderTokens(), status == null ? Map.of() : Map.of("status", status), existing));
    }

    private String deleteRelation(String source, Map<String, BlockAst> blocks, RelationKey relation) {
        BlockAst existing = require(blocks, "relation:" + relation.id());
        requireNoDependencies(relation.id(), dependencies(blocks, existing));
        return ArchitectureSemanticPatch.replace(source, existing, null);
    }

    private String move(String source, Map<String, BlockAst> blocks, MoveOrGroupElement command) {
        require(blocks, "element:" + command.id());
        long existingParents = blocks.values().stream().filter(block -> "relation".equals(block.getKind()))
                .map(ArchitectureDslCommands::relationKey)
                .filter(key -> "CONTAINS".equals(key.relationType()) && key.targetId().equals(command.id())).count();
        if (existingParents > 1) throw problem("MULTIPLE_PARENTS", command.id(),
                "Resolve multiple existing containment parents before moving this element");
        String next = source;
        for (BlockAst candidate : blocks.values()) {
            if ("relation".equals(candidate.getKind())) {
                RelationKey key = relationKey(candidate);
                if ("CONTAINS".equals(key.relationType()) && key.targetId().equals(command.id())) {
                    if (key.sourceId().equals(command.parentId())) return source;
                    next = deleteRelation(next, ArchitectureSemanticPatch.index(next), key);
                }
            }
        }
        return command.parentId() == null ? next : createRelation(next, ArchitectureSemanticPatch.index(next),
                new RelationKey(command.parentId(), "CONTAINS", command.id()), "proposed");
    }

    private static void validateRelation(Map<String, BlockAst> blocks, RelationKey relation) {
        token(relation.sourceId(), "sourceId");
        token(relation.targetId(), "targetId");
        if (relation.sourceId().equals(relation.targetId())) throw problem("SELF_RELATION", "targetId", "Self-relations are not permitted");
        if (!DslValidator.relationTypes().contains(relation.relationType())) throw problem("UNKNOWN_RELATION_TYPE", "relationType", "Unknown relation type");
        String sourceType = elementType(require(blocks, "element:" + relation.sourceId()));
        String targetType = elementType(require(blocks, "element:" + relation.targetId()));
        Map<String, Set<String>> rules = DslValidator.relationTypeRules().get(relation.relationType());
        String sourceRoot = TaxonomyRootTypes.rootFor(sourceType);
        String targetRoot = TaxonomyRootTypes.rootFor(targetType);
        if (rules != null && (sourceRoot == null || targetRoot == null
                || !rules.getOrDefault(sourceRoot, Set.of()).contains(targetRoot))) {
            throw problem("INCOMPATIBLE_RELATION", "relationType", "Relation direction or endpoint types are incompatible");
        }
    }

    private static void validateContainment(Map<String, BlockAst> blocks) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (BlockAst block : blocks.values()) {
            if (!"relation".equals(block.getKind())) continue;
            RelationKey key = relationKey(block);
            if ("CONTAINS".equals(key.relationType()) && parents.putIfAbsent(key.targetId(), key.sourceId()) != null) {
                throw problem("MULTIPLE_PARENTS", key.targetId(), "An element can have at most one containment parent");
            }
        }
        for (String id : parents.keySet()) {
            Set<String> visited = new LinkedHashSet<>();
            for (String cursor = id; cursor != null; cursor = parents.get(cursor)) {
                if (!visited.add(cursor)) throw problem("CONTAINMENT_CYCLE", id, "Containment must be acyclic");
            }
        }
    }

    private static List<String> dependencies(Map<String, BlockAst> blocks, BlockAst removed) {
        List<String> dependencies = new ArrayList<>();
        String id = removed.getHeaderTokens().getFirst();
        for (BlockAst candidate : blocks.values()) {
            if (ArchitectureSemanticPatch.key(candidate).equals(ArchitectureSemanticPatch.key(removed))) continue;
            boolean references = false;
            if ("element".equals(removed.getKind())) {
                references = switch (candidate.getKind()) {
                    case "relation" -> {
                        RelationKey key = relationKey(candidate);
                        yield key.sourceId().equals(id) || key.targetId().equals(id);
                    }
                    case "mapping" -> candidate.getHeaderTokens().contains(id);
                    case "view" -> candidate.propertyValues("include").contains(id);
                    case "element" -> id.equals(candidate.property("x-system-reference")) || id.equals(candidate.property("x-product-reference"));
                    case "evidence" -> referencesElement(candidate.property("for-relation"), id);
                    default -> false;
                };
            } else if ("package".equals(removed.getKind())) {
                references = "package".equals(candidate.getKind()) && id.equals(candidate.property("parent"))
                        || "element".equals(candidate.getKind()) && id.equals(candidate.property("package"));
            } else if ("relation".equals(removed.getKind())) {
                references = relationKey(removed).id().equals(candidate.property("for-relation"));
            }
            if (references) dependencies.add(ArchitectureSemanticPatch.key(candidate));
        }
        return List.copyOf(dependencies);
    }

    private static boolean referencesElement(String relation, String id) {
        return relation != null && List.of(relation.split("\\s+")).contains(id);
    }

    private static void requireNoDependencies(String id, List<String> dependencies) {
        if (!dependencies.isEmpty()) throw new CommandProblem("DEPENDENCIES_EXIST", id,
                "Remove or reassign dependent objects before deletion", dependencies);
    }

    private static BlockAst block(String kind, List<String> header, Map<String, String> changes, BlockAst existing) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (existing != null) {
            for (PropertyAst property : existing.getProperties()) {
                if (properties.putIfAbsent(property.key(), property.value()) != null) {
                    throw problem("DUPLICATE_PROPERTY", property.key(), "Ambiguous property on edited object");
                }
            }
        }
        properties.putAll(changes);
        List<PropertyAst> values = properties.entrySet().stream().map(e -> new PropertyAst(e.getKey(), e.getValue(), null)).toList();
        Map<String, String> extensions = new LinkedHashMap<>();
        properties.forEach((key, value) -> { if (key.startsWith("x-")) extensions.put(key, value); });
        return new BlockAst(kind, header, values, List.of(), extensions, null);
    }

    private static BlockAst require(Map<String, BlockAst> blocks, String id) {
        BlockAst block = blocks.get(id);
        if (block == null) throw problem("NOT_FOUND", id, "Object is not present in this context and commit");
        return block;
    }

    private static String elementType(BlockAst block) {
        List<String> header = block.getHeaderTokens();
        if (header.size() != 3 || !"type".equals(header.get(1))) throw problem("INVALID_HEADER", "type", "Malformed element header");
        return header.get(2);
    }

    private static RelationKey relationKey(BlockAst block) {
        List<String> header = block.getHeaderTokens();
        if (header.size() != 3) throw problem("INVALID_HEADER", "relation", "Malformed relation header");
        return new RelationKey(header.get(0), header.get(1), header.get(2));
    }

    private static void requireType(String type) {
        if (type == null || !TaxonomyRootTypes.TYPE_TO_ROOT.containsKey(type)) throw problem("UNKNOWN_ELEMENT_TYPE", "type", "Choose a metamodel element type");
    }

    private static void requireStatus(String status) {
        if (status != null && !DslValidator.relationStatuses().contains(status)) throw problem("INVALID_STATUS", "status", "Choose a permitted relation status");
    }

    private static void requireProperties(Map<String, String> properties, boolean creating) {
        for (var entry : properties.entrySet()) {
            if (entry.getValue() == null) throw problem("INVALID_VALUE", entry.getKey(), "Property value must be a non-null string");
            if (!ELEMENT_PROPERTIES.contains(entry.getKey())) throw problem("READ_ONLY_PROPERTY", entry.getKey(), "Property is not editable");
            if (entry.getValue().length() > 8000) throw problem("VALUE_TOO_LONG", entry.getKey(), "Property exceeds 8000 characters");
            if (entry.getValue().chars().anyMatch(value -> (value < 32 && value != '\n' && value != '\r' && value != '\t')
                    || value == 0xfffe || value == 0xffff)) throw problem("INVALID_VALUE", entry.getKey(), "Property contains unsupported control characters");
        }
        if ((creating || properties.containsKey("title")) && (properties.get("title") == null || properties.get("title").isBlank())) {
            throw problem("REQUIRED_PROPERTY", "title", "Title is required");
        }
        String status = properties.get("x-editor-status");
        if (status != null && !Set.of("draft", "active", "retired").contains(status)) throw problem("INVALID_STATUS", "x-editor-status", "Choose draft, active or retired");
    }

    private static void token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw problem("INVALID_ID", field, "Identity must be a bounded DSL identifier");
    }

    private static CommandProblem problem(String code, String field, String message) {
        return new CommandProblem(code, field, message, List.of());
    }
}
