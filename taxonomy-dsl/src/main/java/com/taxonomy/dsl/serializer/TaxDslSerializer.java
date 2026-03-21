package com.taxonomy.dsl.serializer;

import com.taxonomy.dsl.ast.*;

import java.util.*;

/**
 * Deterministic serializer for the TaxDSL v2 language.
 *
 * <p>Produces stable, Git-diff-friendly output using explicit block delimiters
 * and semicolon-terminated properties:
 * <ul>
 *   <li>Blocks are grouped by kind and sorted by primary identifier for deterministic ordering.</li>
 *   <li>Blocks are separated by a single blank line.</li>
 *   <li>Blocks use {@code &#123;} and {@code &#125;} delimiters — indentation is not semantically significant.</li>
 *   <li>Properties use {@code key: value;} syntax, indented with two spaces for readability.</li>
 *   <li>Properties within a block follow a canonical order (known properties first, then extensions).</li>
 *   <li>String values are quoted; bare values (numbers, identifiers) are not.</li>
 *   <li>Extension attributes ({@code x-*}) are serialized after known attributes.</li>
 *   <li>Special characters in quoted values are escaped ({@code \"} and {@code \\}).</li>
 * </ul>
 */
public class TaxDslSerializer {

    private static final Set<String> BARE_VALUE_KEYS = Set.of(
            "score", "confidence", "status", "layout", "type");

    /**
     * Canonical ordering of block kinds for deterministic output.
     * Unknown block types sort after all known types.
     */
    private static final List<String> BLOCK_KIND_ORDER = List.of(
            "element", "relation", "requirement", "mapping", "view", "evidence",
            "source", "sourceVersion", "sourceFragment", "requirementSourceLink", "candidate");

    /**
     * Canonical property ordering per block kind.
     * Properties are serialized in this order; any property not listed sorts alphabetically
     * after the listed ones but before extension attributes.
     */
    private static final Map<String, List<String>> PROPERTY_ORDER = Map.ofEntries(
            Map.entry("element", List.of("title", "description", "taxonomy")),
            Map.entry("relation", List.of("status", "confidence", "provenance")),
            Map.entry("requirement", List.of("title", "text")),
            Map.entry("mapping", List.of("score", "source")),
            Map.entry("view", List.of("title", "include", "layout")),
            Map.entry("evidence", List.of("for-relation", "type", "model", "confidence", "summary")),
            Map.entry("source", List.of("type", "title", "canonicalIdentifier", "canonicalUrl", "originSystem", "language")),
            Map.entry("sourceVersion", List.of("source", "versionLabel", "retrievedAt", "effectiveDate", "mimeType", "contentHash")),
            Map.entry("sourceFragment", List.of("sourceVersion", "sectionPath", "paragraphRef", "pageFrom", "pageTo", "text", "fragmentHash", "parentFragment", "chunkLevel")),
            Map.entry("requirementSourceLink", List.of("requirement", "source", "sourceVersion", "sourceFragment", "linkType", "confidence", "note"))
    );

    /**
     * Serialize a {@link DocumentAst} to DSL v2 text.
     * Blocks are sorted by kind (element → relation → requirement → mapping → view → evidence → unknown)
     * and within each kind by primary identifier for deterministic, diff-friendly output.
     */
    public String serialize(DocumentAst document) {
        StringBuilder sb = new StringBuilder();

        if (document.getMeta() != null) {
            serializeMeta(document.getMeta(), sb);
        }

        List<BlockAst> sorted = sortBlocksDeterministically(document.getBlocks());

        for (BlockAst block : sorted) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            serializeBlock(block, sb);
        }

        // Ensure trailing newline
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Sort blocks deterministically: first by kind order, then by primary identifier.
     * Unknown block types sort after all known types, alphabetically by kind then by ID.
     */
    private List<BlockAst> sortBlocksDeterministically(List<BlockAst> blocks) {
        List<BlockAst> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator
                .comparingInt((BlockAst b) -> {
                    int idx = BLOCK_KIND_ORDER.indexOf(b.getKind());
                    return idx >= 0 ? idx : BLOCK_KIND_ORDER.size();
                })
                .thenComparing(BlockAst::getKind)
                .thenComparing(b -> blockSortKey(b)));
        return sorted;
    }

    /**
     * Compute a sort key for a block within its kind group.
     * For relations, the sort key is a composite of source + type + target.
     * For other blocks, it is the first header token (the primary ID).
     */
    private String blockSortKey(BlockAst block) {
        List<String> tokens = block.getHeaderTokens();
        if (tokens.isEmpty()) return "";
        if ("relation".equals(block.getKind()) && tokens.size() >= 3) {
            // Sort relations by source + relation type + target for stable ordering
            return tokens.get(0) + "/" + tokens.get(1) + "/" + tokens.get(2);
        }
        if ("mapping".equals(block.getKind()) && tokens.size() >= 3) {
            // Sort mappings by requirement + element
            return tokens.get(0) + "/" + tokens.get(2);
        }
        return tokens.get(0);
    }

    private void serializeMeta(MetaAst meta, StringBuilder sb) {
        sb.append("meta {\n");
        if (meta.language() != null) {
            sb.append("  language: \"").append(meta.language()).append("\";\n");
        }
        if (meta.version() != null) {
            sb.append("  version: \"").append(meta.version()).append("\";\n");
        }
        if (meta.namespace() != null) {
            sb.append("  namespace: \"").append(meta.namespace()).append("\";\n");
        }
        sb.append("}\n");
    }

    private void serializeBlock(BlockAst block, StringBuilder sb) {
        // Header line with opening brace
        sb.append(block.getKind());
        for (String token : block.getHeaderTokens()) {
            sb.append(' ').append(token);
        }
        sb.append(" {\n");

        // Properties: known first (in canonical order), then extensions (sorted alphabetically)
        List<PropertyAst> known = new ArrayList<>();
        List<PropertyAst> extensions = new ArrayList<>();
        for (PropertyAst prop : block.getProperties()) {
            if (prop.isExtension()) {
                extensions.add(prop);
            } else {
                known.add(prop);
            }
        }

        // Sort known properties by canonical order for this block kind
        List<String> canonicalOrder = PROPERTY_ORDER.getOrDefault(block.getKind(), List.of());
        known.sort(Comparator.comparingInt((PropertyAst p) -> {
            int idx = canonicalOrder.indexOf(p.key());
            return idx >= 0 ? idx : canonicalOrder.size();
        }).thenComparing(PropertyAst::key));

        for (PropertyAst prop : known) {
            serializeProperty(prop, sb);
        }

        // Sort extensions alphabetically for stable output
        extensions.sort(Comparator.comparing(PropertyAst::key));

        for (PropertyAst prop : extensions) {
            serializeProperty(prop, sb);
        }

        // Closing brace
        sb.append("}\n");
    }

    private void serializeProperty(PropertyAst prop, StringBuilder sb) {
        sb.append("  ").append(prop.key()).append(": ");
        if (shouldQuote(prop.key(), prop.value())) {
            sb.append('"').append(escapeForQuoting(prop.value())).append('"');
        } else {
            sb.append(prop.value());
        }
        sb.append(";\n");
    }

    /**
     * Determine whether a property value should be quoted.
     * Bare values are used for numeric values and specific known keys.
     */
    private boolean shouldQuote(String key, String value) {
        if (value == null || value.isEmpty()) return true;
        if (BARE_VALUE_KEYS.contains(key)) return false;
        // Check if value is numeric
        try {
            Double.parseDouble(value);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Escape special characters for a quoted string value:
     * {@code \} → {@code \\}, {@code "} → {@code \"}.
     */
    private String escapeForQuoting(String value) {
        if (value == null) return "";
        if (value.indexOf('\\') < 0 && value.indexOf('"') < 0) return value;
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
