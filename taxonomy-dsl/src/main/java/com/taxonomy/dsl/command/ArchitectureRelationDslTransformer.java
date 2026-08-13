package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies one deterministic relation command to an {@code architecture.taxdsl}
 * document before the result is committed to JGit.
 *
 * <p>The transformer is deliberately independent of repositories, transactions,
 * controllers and projections. It establishes the pure semantic boundary needed
 * by Git-authoritative relation commands: exact identity, idempotent upsert,
 * exact removal, duplicate rejection and canonical changed output.</p>
 */
public final class ArchitectureRelationDslTransformer {

    private static final String RELATION_KIND = "relation";
    private static final List<String> MUTABLE_PROPERTY_ORDER = List.of(
            "status", "confidence", "provenance");

    private final TaxDslParser parser;
    private final TaxDslSerializer serializer;

    public ArchitectureRelationDslTransformer() {
        this(new TaxDslParser(), new TaxDslSerializer());
    }

    ArchitectureRelationDslTransformer(
            TaxDslParser parser,
            TaxDslSerializer serializer) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /**
     * Adds the relation when absent or updates only the supplied review fields
     * and extensions when exactly one matching relation exists.
     */
    public ChangeResult upsert(String sourceDsl, RelationDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String original = sourceDsl == null ? "" : sourceDsl;
        DocumentAst document = parser.parse(original, "architecture.taxdsl");
        List<Integer> matches = matchingIndexes(
                document.getBlocks(), definition.identity());
        rejectAmbiguity(definition.identity(), matches.size());

        List<BlockAst> changedBlocks = new ArrayList<>(document.getBlocks());
        if (matches.isEmpty()) {
            changedBlocks.add(newRelationBlock(definition));
            return changed(document, changedBlocks, ChangeKind.ADDED);
        }

        int index = matches.getFirst();
        BlockAst existing = changedBlocks.get(index);
        requireCanonicalHeader(existing, definition.identity());
        BlockAst replacement = merge(existing, definition);
        if (canonicalBlock(existing).equals(canonicalBlock(replacement))) {
            return new ChangeResult(original, ChangeKind.UNCHANGED);
        }
        changedBlocks.set(index, replacement);
        return changed(document, changedBlocks, ChangeKind.UPDATED);
    }

    /** Removes exactly one matching relation and leaves all other blocks intact. */
    public ChangeResult remove(String sourceDsl, RelationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String original = sourceDsl == null ? "" : sourceDsl;
        DocumentAst document = parser.parse(original, "architecture.taxdsl");
        List<Integer> matches = matchingIndexes(document.getBlocks(), identity);
        rejectAmbiguity(identity, matches.size());
        if (matches.isEmpty()) {
            return new ChangeResult(original, ChangeKind.UNCHANGED);
        }

        int index = matches.getFirst();
        requireCanonicalHeader(document.getBlocks().get(index), identity);
        List<BlockAst> changedBlocks = new ArrayList<>(document.getBlocks());
        changedBlocks.remove(index);
        return changed(document, changedBlocks, ChangeKind.REMOVED);
    }

    private ChangeResult changed(
            DocumentAst original,
            List<BlockAst> blocks,
            ChangeKind kind) {
        DocumentAst changed = new DocumentAst(original.getMeta(), blocks);
        return new ChangeResult(serializer.serialize(changed), kind);
    }

    private BlockAst newRelationBlock(RelationDefinition definition) {
        List<PropertyAst> properties = new ArrayList<>();
        addSuppliedProperties(properties, definition);
        return new BlockAst(
                RELATION_KIND,
                definition.identity().headerTokens(),
                properties,
                List.of(),
                extensionMap(properties),
                null);
    }

    private BlockAst merge(
            BlockAst existing,
            RelationDefinition definition) {
        Map<String, String> overrides = suppliedProperties(definition);
        List<PropertyAst> properties = new ArrayList<>();
        Set<String> emittedOverrides = new LinkedHashSet<>();

        for (PropertyAst property : existing.getProperties()) {
            String key = property.key();
            if (!overrides.containsKey(key)) {
                properties.add(property);
                continue;
            }
            if (emittedOverrides.add(key)) {
                properties.add(new PropertyAst(key, overrides.get(key), null));
            }
        }

        MUTABLE_PROPERTY_ORDER.stream()
                .filter(overrides::containsKey)
                .filter(emittedOverrides::add)
                .forEach(key -> properties.add(
                        new PropertyAst(key, overrides.get(key), null)));
        overrides.keySet().stream()
                .filter(key -> key.startsWith("x-"))
                .sorted()
                .filter(emittedOverrides::add)
                .forEach(key -> properties.add(
                        new PropertyAst(key, overrides.get(key), null)));

        return new BlockAst(
                RELATION_KIND,
                definition.identity().headerTokens(),
                properties,
                existing.getChildren(),
                extensionMap(properties),
                existing.getSourceLocation());
    }

    private void addSuppliedProperties(
            List<PropertyAst> properties,
            RelationDefinition definition) {
        Map<String, String> supplied = suppliedProperties(definition);
        MUTABLE_PROPERTY_ORDER.stream()
                .filter(supplied::containsKey)
                .forEach(key -> properties.add(
                        new PropertyAst(key, supplied.get(key), null)));
        supplied.keySet().stream()
                .filter(key -> key.startsWith("x-"))
                .sorted()
                .forEach(key -> properties.add(
                        new PropertyAst(key, supplied.get(key), null)));
    }

    private Map<String, String> suppliedProperties(
            RelationDefinition definition) {
        Map<String, String> supplied = new LinkedHashMap<>();
        if (definition.status() != null) {
            supplied.put("status", definition.status());
        }
        if (definition.confidence() != null) {
            supplied.put("confidence", String.valueOf(definition.confidence()));
        }
        if (definition.provenance() != null) {
            supplied.put("provenance", definition.provenance());
        }
        supplied.putAll(definition.extensions());
        return supplied;
    }

    private Map<String, String> extensionMap(List<PropertyAst> properties) {
        Map<String, String> extensions = new LinkedHashMap<>();
        properties.stream()
                .filter(PropertyAst::isExtension)
                .forEach(property -> extensions.put(
                        property.key(), property.value()));
        return extensions;
    }

    private List<Integer> matchingIndexes(
            List<BlockAst> blocks,
            RelationIdentity identity) {
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            BlockAst block = blocks.get(index);
            if (RELATION_KIND.equals(block.getKind())
                    && headerStartsWith(block, identity)) {
                matches.add(index);
            }
        }
        return matches;
    }

    private boolean headerStartsWith(
            BlockAst block,
            RelationIdentity identity) {
        List<String> tokens = block.getHeaderTokens();
        return tokens.size() >= 3
                && identity.sourceId().equals(tokens.get(0))
                && identity.relationType().equals(tokens.get(1))
                && identity.targetId().equals(tokens.get(2));
    }

    private void requireCanonicalHeader(
            BlockAst block,
            RelationIdentity identity) {
        if (block.getHeaderTokens().size() != 3) {
            throw new InvalidRelationBlockException(
                    "Relation " + identity.asDisplayText()
                            + " has a malformed header with "
                            + block.getHeaderTokens().size() + " tokens");
        }
    }

    private void rejectAmbiguity(RelationIdentity identity, int matches) {
        if (matches > 1) {
            throw new AmbiguousRelationException(
                    "Relation " + identity.asDisplayText()
                            + " occurs " + matches + " times");
        }
    }

    private String canonicalBlock(BlockAst block) {
        return serializer.serialize(new DocumentAst(null, List.of(block)));
    }

    public enum ChangeKind {
        ADDED,
        UPDATED,
        REMOVED,
        UNCHANGED
    }

    public record ChangeResult(String dsl, ChangeKind kind) {
        public ChangeResult {
            dsl = Objects.requireNonNull(dsl, "dsl");
            kind = Objects.requireNonNull(kind, "kind");
        }

        public boolean changed() {
            return kind != ChangeKind.UNCHANGED;
        }
    }

    public record RelationIdentity(
            String sourceId,
            String relationType,
            String targetId) {

        public RelationIdentity {
            sourceId = requireToken(sourceId, "sourceId");
            relationType = requireToken(relationType, "relationType")
                    .toUpperCase(Locale.ROOT);
            targetId = requireToken(targetId, "targetId");
        }

        List<String> headerTokens() {
            return List.of(sourceId, relationType, targetId);
        }

        String asDisplayText() {
            return sourceId + " " + relationType + " " + targetId;
        }
    }

    public record RelationDefinition(
            RelationIdentity identity,
            String status,
            Double confidence,
            String provenance,
            Map<String, String> extensions) {

        public RelationDefinition {
            identity = Objects.requireNonNull(identity, "identity");
            status = normalizeOptionalToken(status, "status");
            provenance = normalizeOptionalToken(provenance, "provenance");
            if (confidence != null
                    && (!Double.isFinite(confidence)
                    || confidence < 0.0
                    || confidence > 1.0)) {
                throw new IllegalArgumentException(
                        "confidence must be finite and between 0.0 and 1.0");
            }
            Map<String, String> normalizedExtensions = new TreeMap<>();
            if (extensions != null) {
                extensions.forEach((key, value) -> {
                    String normalizedKey = requireToken(key, "extension key");
                    if (!normalizedKey.startsWith("x-")) {
                        throw new IllegalArgumentException(
                                "extension key must start with x-: " + normalizedKey);
                    }
                    normalizedExtensions.put(
                            normalizedKey,
                            Objects.requireNonNull(value,
                                    "extension value for " + normalizedKey));
                });
            }
            extensions = Map.copyOf(normalizedExtensions);
        }

        public RelationDefinition(
                RelationIdentity identity,
                String status,
                Double confidence,
                String provenance) {
            this(identity, status, confidence, provenance, Map.of());
        }
    }

    public static final class AmbiguousRelationException
            extends IllegalStateException {
        public AmbiguousRelationException(String message) {
            super(message);
        }
    }

    public static final class InvalidRelationBlockException
            extends IllegalStateException {
        public InvalidRelationBlockException(String message) {
            super(message);
        }
    }

    private static String requireToken(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    field + " must be one DSL token: " + normalized);
        }
        return normalized;
    }

    private static String normalizeOptionalToken(
            String value,
            String field) {
        if (value == null) {
            return null;
        }
        return requireToken(value, field).toLowerCase(Locale.ROOT);
    }
}
