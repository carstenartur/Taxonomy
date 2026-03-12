package com.nato.taxonomy.dsl.serializer;

import com.nato.taxonomy.dsl.ast.*;

import java.util.*;

/**
 * Deterministic serializer for the TaxDSL v1 language.
 *
 * <p>Produces stable, Git-diff-friendly output:
 * <ul>
 *   <li>Blocks are separated by a single blank line.</li>
 *   <li>Properties are indented with two spaces.</li>
 *   <li>String values are quoted; bare values (numbers, identifiers) are not.</li>
 *   <li>Properties appear in a stable, deterministic order.</li>
 *   <li>Extension attributes ({@code x-*}) are serialized after known attributes.</li>
 * </ul>
 */
public class TaxDslSerializer {

    private static final Set<String> BARE_VALUE_KEYS = Set.of(
            "score", "confidence", "status", "layout", "type");

    /**
     * Serialize a {@link DocumentAst} to DSL text.
     */
    public String serialize(DocumentAst document) {
        StringBuilder sb = new StringBuilder();

        if (document.getMeta() != null) {
            serializeMeta(document.getMeta(), sb);
        }

        for (BlockAst block : document.getBlocks()) {
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

    private void serializeMeta(MetaAst meta, StringBuilder sb) {
        sb.append("meta\n");
        if (meta.language() != null) {
            sb.append("  language \"").append(meta.language()).append("\"\n");
        }
        if (meta.version() != null) {
            sb.append("  version \"").append(meta.version()).append("\"\n");
        }
        if (meta.namespace() != null) {
            sb.append("  namespace \"").append(meta.namespace()).append("\"\n");
        }
    }

    private void serializeBlock(BlockAst block, StringBuilder sb) {
        // Header line
        sb.append(block.getKind());
        for (String token : block.getHeaderTokens()) {
            sb.append(' ').append(token);
        }
        sb.append('\n');

        // Properties: known first, then extensions
        List<PropertyAst> known = new ArrayList<>();
        List<PropertyAst> extensions = new ArrayList<>();
        for (PropertyAst prop : block.getProperties()) {
            if (prop.isExtension()) {
                extensions.add(prop);
            } else {
                known.add(prop);
            }
        }

        for (PropertyAst prop : known) {
            serializeProperty(prop, sb);
        }

        if (!extensions.isEmpty() && !known.isEmpty()) {
            // Blank line separates core properties from extensions for clarity in diffs
            sb.append('\n');
        }

        for (PropertyAst prop : extensions) {
            serializeProperty(prop, sb);
        }
    }

    private void serializeProperty(PropertyAst prop, StringBuilder sb) {
        sb.append("  ").append(prop.key()).append(' ');
        if (shouldQuote(prop.key(), prop.value())) {
            sb.append('"').append(prop.value()).append('"');
        } else {
            sb.append(prop.value());
        }
        sb.append('\n');
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
}
