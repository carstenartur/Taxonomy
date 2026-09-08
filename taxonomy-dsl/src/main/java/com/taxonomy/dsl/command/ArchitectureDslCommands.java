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
            case CreateArchitectureElement create -> createElement(source, blocks, create);
            case UpdateArchitectureElement update -> updateElement(source, blocks, update);
            case DeleteArchitectureElement delete -> deleteElement(source, blocks, delete.id());
            case CreateArchitectureRelation create -> createRelation(source, blocks, create.relation(), create.status());
            case UpdateArchitectureRelation update -> updateRelation(source, blocks, update.relation(), update.status());
            case DeleteArchitectureRelation delete -> deleteRelation(source, blocks, delete.relation());
            case MoveOrGroupElement move -> move(source, blocks, move);
        };
        return new Change(next, ArchitectureSemanticPatch.between(source, next));
    }

    /** Rechecks inverse preconditions and the resulting model, never restores a whole document. */
    public Change inverse(String current, String before, String after) {
        List<ArchitectureSemanticPatch.BlockChange> original = ArchitectureSemanticPatch.between(before, after);
        if (original.isEmpty()) throw problem("NOT_UNDOABLE", "targetOperationId", "Target has no semantic changes");
        if (original.stream().anyMatch(change -> !change.id().startsWith("element:")
                && !change.id().startsWith("relation:"))) {
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
        validateContainment(blocks);
        return new Change(next, ArchitectureSemanticPatch.between(current, next));
    }

    private String createElement(String source, Map<String, BlockAst> blocks, CreateArchitectureElement command) {
        token(command.id(), "id");
        requireType(command.type());
        if (blocks.containsKey("element:" + command.id())) throw problem("DUPLICATE_ID", "id", "Element already exists");
        requireProperties(command.properties(), true);
        return ArchitectureSemanticPatch.replace(source, null,
                block("element", List.of(command.id(), "type", command.type()), command.properties(), null));
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
