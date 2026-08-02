package com.taxonomy.dsl.merge;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Three-way semantic merge for Taxonomy DSL documents.
 *
 * <p>Blocks are identified by their kind and complete header rather than by
 * line numbers. Independent requirements, mappings, solutions and products can
 * therefore be added on different branches without textual conflicts.
 * Concurrent edits to the same property remain explicit review conflicts.</p>
 */
public final class TaxDslSemanticMerger {

    private static final SourceLocation GENERATED =
            new SourceLocation("semantic-merge", 1, 1);

    private final TaxDslParser parser;
    private final TaxDslSerializer serializer;

    public TaxDslSemanticMerger() {
        this(new TaxDslParser(), new TaxDslSerializer());
    }

    public TaxDslSemanticMerger(TaxDslParser parser, TaxDslSerializer serializer) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    public TaxDslMergeResult merge(String baseText, String oursText, String theirsText) {
        DocumentAst base = parse(baseText, "merge-base.taxdsl");
        DocumentAst ours = parse(oursText, "ours.taxdsl");
        DocumentAst theirs = parse(theirsText, "theirs.taxdsl");

        List<TaxDslMergeConflict> conflicts = new ArrayList<>();
        MetaAst meta = mergeMeta(base.getMeta(), ours.getMeta(), theirs.getMeta(), conflicts);

        Map<String, BlockAst> baseBlocks = index(base.getBlocks(), "base", conflicts);
        Map<String, BlockAst> ourBlocks = index(ours.getBlocks(), "ours", conflicts);
        Map<String, BlockAst> theirBlocks = index(theirs.getBlocks(), "theirs", conflicts);

        Set<String> identities = new TreeSet<>();
        identities.addAll(baseBlocks.keySet());
        identities.addAll(ourBlocks.keySet());
        identities.addAll(theirBlocks.keySet());

        List<BlockAst> mergedBlocks = new ArrayList<>();
        for (String identity : identities) {
            BlockAst merged = mergeBlock(
                    identity,
                    baseBlocks.get(identity),
                    ourBlocks.get(identity),
                    theirBlocks.get(identity),
                    conflicts);
            if (merged != null) mergedBlocks.add(merged);
        }

        if (!conflicts.isEmpty()) {
            return new TaxDslMergeResult(null, conflicts);
        }
        return new TaxDslMergeResult(
                serializer.serialize(new DocumentAst(meta, mergedBlocks)),
                List.of());
    }

    private DocumentAst parse(String text, String sourceName) {
        if (text == null || text.isBlank()) {
            return new DocumentAst(
                    new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, "default", GENERATED),
                    List.of());
        }
        return parser.parse(text, sourceName);
    }

    private MetaAst mergeMeta(MetaAst base,
                              MetaAst ours,
                              MetaAst theirs,
                              List<TaxDslMergeConflict> conflicts) {
        String language = mergeScalar(
                "meta", "language", value(base, MetaAst::language),
                value(ours, MetaAst::language), value(theirs, MetaAst::language), conflicts);
        String version = mergeScalar(
                "meta", "version", value(base, MetaAst::version),
                value(ours, MetaAst::version), value(theirs, MetaAst::version), conflicts);
        String namespace = mergeScalar(
                "meta", "namespace", value(base, MetaAst::namespace),
                value(ours, MetaAst::namespace), value(theirs, MetaAst::namespace), conflicts);
        return new MetaAst(
                language != null ? language : MetaAst.LANGUAGE_ID,
                version != null ? version : MetaAst.CURRENT_VERSION,
                namespace != null ? namespace : "default",
                GENERATED);
    }

    private static <T> String value(T object, Function<T, String> accessor) {
        return object == null ? null : accessor.apply(object);
    }

    private Map<String, BlockAst> index(Collection<BlockAst> blocks,
                                        String side,
                                        List<TaxDslMergeConflict> conflicts) {
        Map<String, BlockAst> indexed = new LinkedHashMap<>();
        for (BlockAst block : blocks) {
            String identity = identity(block);
            BlockAst previous = indexed.putIfAbsent(identity, block);
            if (previous != null) {
                conflicts.add(new TaxDslMergeConflict(
                        identity, null, "duplicate block on " + side,
                        null,
                        side.equals("ours") ? renderBlock(previous) : null,
                        side.equals("theirs") ? renderBlock(block) : null));
            }
        }
        return indexed;
    }

    /** Stable identity: block kind plus every header token. */
    public String identity(BlockAst block) {
        return block.getKind() + " " + String.join(" ", block.getHeaderTokens());
    }

    private BlockAst mergeBlock(String identity,
                                BlockAst base,
                                BlockAst ours,
                                BlockAst theirs,
                                List<TaxDslMergeConflict> conflicts) {
        if (equivalent(ours, theirs)) return copy(ours);
        if (equivalent(base, ours)) return copy(theirs);
        if (equivalent(base, theirs)) return copy(ours);

        if (base == null) {
            if (ours == null) return copy(theirs);
            if (theirs == null) return copy(ours);
            conflicts.add(blockConflict(identity,
                    "different blocks added with the same identity", null, ours, theirs));
            return null;
        }
        if (ours == null && theirs == null) return null;
        if (ours == null) {
            conflicts.add(blockConflict(identity,
                    "deleted in ours but modified in theirs", base, null, theirs));
            return null;
        }
        if (theirs == null) {
            conflicts.add(blockConflict(identity,
                    "modified in ours but deleted in theirs", base, ours, null));
            return null;
        }

        if (hasDuplicatePropertyKeys(base) || hasDuplicatePropertyKeys(ours)
                || hasDuplicatePropertyKeys(theirs)) {
            conflicts.add(blockConflict(identity,
                    "concurrent edits to a block with repeated property keys require review",
                    base, ours, theirs));
            return null;
        }

        Map<String, PropertyAst> baseProperties = propertyMap(base);
        Map<String, PropertyAst> ourProperties = propertyMap(ours);
        Map<String, PropertyAst> theirProperties = propertyMap(theirs);
        Set<String> keys = new TreeSet<>();
        keys.addAll(baseProperties.keySet());
        keys.addAll(ourProperties.keySet());
        keys.addAll(theirProperties.keySet());

        int conflictsBefore = conflicts.size();
        List<PropertyAst> mergedProperties = new ArrayList<>();
        for (String key : keys) {
            PropertyAst baseProperty = baseProperties.get(key);
            PropertyAst ourProperty = ourProperties.get(key);
            PropertyAst theirProperty = theirProperties.get(key);
            String mergedValue = mergeScalar(
                    identity,
                    key,
                    propertyValue(baseProperty),
                    propertyValue(ourProperty),
                    propertyValue(theirProperty),
                    conflicts);
            if (mergedValue != null) {
                mergedProperties.add(new PropertyAst(
                        key, mergedValue,
                        firstPropertyLocation(ourProperty, theirProperty, baseProperty)));
            }
        }
        if (conflicts.size() > conflictsBefore) return null;

        return new BlockAst(
                ours.getKind(),
                List.copyOf(ours.getHeaderTokens()),
                mergedProperties,
                List.of(),
                Map.of(),
                firstBlockLocation(ours, theirs, base));
    }

    private String mergeScalar(String identity,
                               String property,
                               String base,
                               String ours,
                               String theirs,
                               List<TaxDslMergeConflict> conflicts) {
        if (Objects.equals(ours, theirs)) return ours;
        if (Objects.equals(base, ours)) return theirs;
        if (Objects.equals(base, theirs)) return ours;
        conflicts.add(new TaxDslMergeConflict(
                identity, property, "concurrent property modification", base, ours, theirs));
        return null;
    }

    private static String propertyValue(PropertyAst property) {
        return property == null ? null : property.value();
    }

    private static SourceLocation firstPropertyLocation(PropertyAst... properties) {
        for (PropertyAst property : properties) {
            if (property != null && property.sourceLocation() != null) {
                return property.sourceLocation();
            }
        }
        return GENERATED;
    }

    private static SourceLocation firstBlockLocation(BlockAst... blocks) {
        for (BlockAst block : blocks) {
            if (block != null && block.getSourceLocation() != null) {
                return block.getSourceLocation();
            }
        }
        return GENERATED;
    }

    private static Map<String, PropertyAst> propertyMap(BlockAst block) {
        Map<String, PropertyAst> result = new LinkedHashMap<>();
        for (PropertyAst property : block.getProperties()) {
            result.put(property.key(), property);
        }
        return result;
    }

    private static boolean hasDuplicatePropertyKeys(BlockAst block) {
        Set<String> keys = new LinkedHashSet<>();
        for (PropertyAst property : block.getProperties()) {
            if (!keys.add(property.key())) return true;
        }
        return false;
    }

    private static boolean equivalent(BlockAst left, BlockAst right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(left.getKind(), right.getKind())
                && Objects.equals(left.getHeaderTokens(), right.getHeaderTokens())
                && normalizedProperties(left).equals(normalizedProperties(right));
    }

    private static List<String> normalizedProperties(BlockAst block) {
        return block.getProperties().stream()
                .map(property -> property.key() + "\u0000" + property.value())
                .sorted()
                .toList();
    }

    private static BlockAst copy(BlockAst block) {
        if (block == null) return null;
        List<PropertyAst> properties = block.getProperties().stream()
                .map(property -> new PropertyAst(
                        property.key(), property.value(), property.sourceLocation()))
                .toList();
        return new BlockAst(
                block.getKind(),
                List.copyOf(block.getHeaderTokens()),
                properties,
                List.copyOf(block.getChildren()),
                Map.copyOf(block.getExtensions()),
                block.getSourceLocation());
    }

    private TaxDslMergeConflict blockConflict(String identity,
                                               String reason,
                                               BlockAst base,
                                               BlockAst ours,
                                               BlockAst theirs) {
        return new TaxDslMergeConflict(
                identity, null, reason,
                renderBlock(base), renderBlock(ours), renderBlock(theirs));
    }

    private String renderBlock(BlockAst block) {
        if (block == null) return null;
        DocumentAst document = new DocumentAst(
                new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, "merge", GENERATED),
                List.of(block));
        String text = serializer.serialize(document);
        int start = text.indexOf("\n\n");
        return start >= 0 ? text.substring(start + 2).strip() : text.strip();
    }
}
