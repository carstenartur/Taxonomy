package com.taxonomy.dsl.merge;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.BlockIdentity;
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
 * <p>Blocks are identified by their kind-aware stable identity rather than by
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
                mergeScalar("meta", "catalogueRevision", value(base, MetaAst::catalogueRevision),
                        value(ours, MetaAst::catalogueRevision), value(theirs, MetaAst::catalogueRevision), conflicts),
                mergeScalar("meta", "comparisonCatalogueRevision", value(base, MetaAst::comparisonCatalogueRevision),
                        value(ours, MetaAst::comparisonCatalogueRevision), value(theirs, MetaAst::comparisonCatalogueRevision), conflicts),
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

    /** Canonical identity shared with semantic patches. */
    public String identity(BlockAst block) {
        return BlockIdentity.key(block);
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

        int conflictsBefore = conflicts.size();
        List<String> header = mergeValue(identity, "header", base.getHeaderTokens(),
                ours.getHeaderTokens(), theirs.getHeaderTokens(), values -> String.join(" ", values), conflicts);
        Map<String, List<String>> baseProperties = propertyMap(base);
        Map<String, List<String>> ourProperties = propertyMap(ours);
        Map<String, List<String>> theirProperties = propertyMap(theirs);
        Set<String> keys = new TreeSet<>();
        keys.addAll(baseProperties.keySet());
        keys.addAll(ourProperties.keySet());
        keys.addAll(theirProperties.keySet());

        List<PropertyAst> mergedProperties = new ArrayList<>();
        for (String key : keys) {
            List<String> baseValues = baseProperties.getOrDefault(key, List.of());
            List<String> ourValues = ourProperties.getOrDefault(key, List.of());
            List<String> theirValues = theirProperties.getOrDefault(key, List.of());
            List<String> mergedValues = unorderedMembership(ours.getKind(), key)
                    ? mergeMembership(baseValues, ourValues, theirValues)
                    : mergeValue(identity, key, baseValues, ourValues, theirValues,
                            TaxDslSemanticMerger::renderValues, conflicts);
            if (mergedValues != null) {
                for (String value : mergedValues) {
                    mergedProperties.add(new PropertyAst(key, value, firstBlockLocation(ours, theirs, base)));
                }
            }
        }
        if (conflicts.size() > conflictsBefore) return null;

        Map<String, String> extensions = new LinkedHashMap<>();
        mergedProperties.stream().filter(PropertyAst::isExtension)
                .forEach(property -> extensions.put(property.key(), property.value()));
        return new BlockAst(ours.getKind(), header, mergedProperties,
                ours.getChildren(), extensions, firstBlockLocation(ours, theirs, base));
    }

    /** Only declared memberships are unordered; unknown repeated properties retain sequence semantics. */
    private static boolean unorderedMembership(String kind, String key) {
        return "view".equals(kind) && "include".equals(key);
    }

    private static List<String> mergeMembership(List<String> base, List<String> ours, List<String> theirs) {
        Set<String> merged = new TreeSet<>(ours);
        merged.addAll(theirs);
        Set<String> ourSet = new LinkedHashSet<>(ours);
        Set<String> theirSet = new LinkedHashSet<>(theirs);
        // A baseline membership is removed if either branch removed it. Independent new members survive.
        for (String value : base) {
            if (!ourSet.contains(value) || !theirSet.contains(value)) merged.remove(value);
        }
        return List.copyOf(merged);
    }

    private static String renderValues(List<String> values) {
        return values.isEmpty() ? null : String.join("\n", values);
    }

    private <T> T mergeValue(String identity, String property, T base, T ours, T theirs,
                             Function<T, String> render, List<TaxDslMergeConflict> conflicts) {
        if (Objects.equals(ours, theirs)) return ours;
        if (Objects.equals(base, ours)) return theirs;
        if (Objects.equals(base, theirs)) return ours;
        conflicts.add(new TaxDslMergeConflict(identity, property, "concurrent property modification",
                render.apply(base), render.apply(ours), render.apply(theirs)));
        return null;
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

    private static SourceLocation firstBlockLocation(BlockAst... blocks) {
        for (BlockAst block : blocks) {
            if (block != null && block.getSourceLocation() != null) {
                return block.getSourceLocation();
            }
        }
        return GENERATED;
    }

    private static Map<String, List<String>> propertyMap(BlockAst block) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (PropertyAst property : block.getProperties()) {
            result.computeIfAbsent(property.key(), ignored -> new ArrayList<>()).add(property.value());
        }
        result.replaceAll((key, values) -> unorderedMembership(block.getKind(), key)
                ? List.copyOf(new TreeSet<>(values)) : List.copyOf(values));
        return result;
    }

    private static boolean equivalent(BlockAst left, BlockAst right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(left.getKind(), right.getKind())
                && Objects.equals(left.getHeaderTokens(), right.getHeaderTokens())
                && propertyMap(left).equals(propertyMap(right));
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
