package com.nato.taxonomy.dsl.parser;

import com.nato.taxonomy.dsl.ast.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-oriented parser for the TaxDSL v1 language.
 *
 * <p>The parser is intentionally simple and tolerant:
 * <ul>
 *   <li>Blocks start with an unindented keyword line.</li>
 *   <li>Properties are indented lines within a block.</li>
 *   <li>Unknown block types and unknown properties are preserved.</li>
 *   <li>Quoted string values are unquoted; unquoted values are kept as-is.</li>
 * </ul>
 */
public class TaxDslParser {

    private static final Set<String> KNOWN_BLOCK_TYPES = Set.of(
            "meta", "element", "relation", "requirement", "mapping", "view", "evidence");

    /** Pattern to extract a quoted string value. */
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]*)\"");

    /**
     * Parse DSL text into a {@link DocumentAst}.
     *
     * @param text     the DSL source text
     * @param fileName optional file name for source locations (may be {@code null})
     * @return the parsed document AST
     */
    public DocumentAst parse(String text, String fileName) {
        if (text == null || text.isBlank()) {
            return new DocumentAst(null, List.of());
        }

        List<String> lines = text.lines().toList();
        MetaAst meta = null;
        List<BlockAst> blocks = new ArrayList<>();
        int i = 0;

        while (i < lines.size()) {
            String line = lines.get(i);

            // Skip blank lines and comments
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                i++;
                continue;
            }

            // Non-indented line starts a new block
            if (!isIndented(line)) {
                String trimmed = line.strip();
                String keyword = firstToken(trimmed);
                List<String> headerTokens = parseHeaderTokens(trimmed);

                // Collect indented body lines
                int bodyStart = i + 1;
                int bodyEnd = bodyStart;
                while (bodyEnd < lines.size() && (isIndented(lines.get(bodyEnd)) || lines.get(bodyEnd).isBlank())) {
                    bodyEnd++;
                }
                // Trim trailing blank lines from body
                while (bodyEnd > bodyStart && lines.get(bodyEnd - 1).isBlank()) {
                    bodyEnd--;
                }

                List<PropertyAst> properties = new ArrayList<>();
                Map<String, String> extensions = new LinkedHashMap<>();
                for (int j = bodyStart; j < bodyEnd; j++) {
                    String bodyLine = lines.get(j).strip();
                    if (bodyLine.isBlank() || bodyLine.startsWith("#")) continue;
                    PropertyAst prop = parseProperty(bodyLine, fileName, j + 1);
                    if (prop != null) {
                        properties.add(prop);
                        if (prop.isExtension()) {
                            extensions.put(prop.key(), prop.value());
                        }
                    }
                }

                SourceLocation loc = new SourceLocation(fileName, i + 1, 1);

                if ("meta".equals(keyword)) {
                    String language = findProperty(properties, "language");
                    String version = findProperty(properties, "version");
                    String namespace = findProperty(properties, "namespace");
                    meta = new MetaAst(language, version, namespace, loc);
                } else {
                    BlockAst block = new BlockAst(keyword, headerTokens, properties,
                            List.of(), extensions, loc);
                    blocks.add(block);
                }

                i = bodyEnd;
            } else {
                i++;
            }
        }

        return new DocumentAst(meta, blocks);
    }

    /** Convenience overload without file name. */
    public DocumentAst parse(String text) {
        return parse(text, null);
    }

    private boolean isIndented(String line) {
        return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
    }

    private String firstToken(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    /**
     * Parse header tokens from the block header line (excluding the keyword itself).
     * Tokens are split by whitespace.
     */
    private List<String> parseHeaderTokens(String headerLine) {
        String[] parts = headerLine.split("\\s+");
        if (parts.length <= 1) return List.of();
        return List.of(Arrays.copyOfRange(parts, 1, parts.length));
    }

    /**
     * Parse a single property line like {@code title "Some Title"} or {@code score 0.92}.
     */
    private PropertyAst parseProperty(String line, String fileName, int lineNumber) {
        String key;
        String value;

        // Check for quoted value
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            // Key-only property (rare, but support it)
            key = line;
            value = "";
        } else {
            key = line.substring(0, firstSpace);
            String rest = line.substring(firstSpace).strip();
            Matcher m = QUOTED_VALUE.matcher(rest);
            if (m.find()) {
                value = m.group(1);
            } else {
                value = rest;
            }
        }
        return new PropertyAst(key, value, new SourceLocation(fileName, lineNumber, 1));
    }

    private String findProperty(List<PropertyAst> properties, String key) {
        return properties.stream()
                .filter(p -> p.key().equals(key))
                .map(PropertyAst::value)
                .findFirst()
                .orElse(null);
    }
}
