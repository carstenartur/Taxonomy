package com.taxonomy.dsl.parser;

import com.taxonomy.dsl.ast.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block-structured parser for the TaxDSL v2 language.
 *
 * <p>DSL v2 uses explicit block delimiters ({@code &#123;} / {@code &#125;}) and
 * semicolon-terminated {@code key: value;} properties:
 * <pre>
 * element CP-1023 type Capability {
 *   title: "Secure Communications";
 *   description: "Ability to provide secure communications";
 * }
 * </pre>
 *
 * <p>The parser is intentionally simple and tolerant:
 * <ul>
 *   <li>Blocks start with a keyword line ending in {@code &#123;}.</li>
 *   <li>Properties use {@code key: value;} syntax inside the block.</li>
 *   <li>Blocks end with a line containing {@code &#125;}.</li>
 *   <li>Indentation is ignored — only {@code &#123;} and {@code &#125;} are structural.</li>
 *   <li>Unknown block types and unknown properties are preserved.</li>
 *   <li>Quoted string values are unquoted; unquoted values are kept as-is.</li>
 * </ul>
 */
public class TaxDslParser {

    private static final Set<String> KNOWN_BLOCK_TYPES = Set.of(
            "meta", "element", "relation", "requirement", "mapping", "view", "evidence");

    /**
     * Pattern to extract a quoted string value.
     * Supports escaped quotes ({@code \"}) inside the string.
     * Uses a possessive quantifier ({@code *+}) to prevent catastrophic
     * backtracking (ReDoS) on malformed input.
     */
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*+)\"");

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
            String stripped = lines.get(i).strip();

            // Skip blank lines and comments
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                i++;
                continue;
            }

            // Look for a block opening: keyword ... {
            if (stripped.endsWith("{")) {
                String headerPart = stripped.substring(0, stripped.length() - 1).strip();
                String keyword = firstToken(headerPart);
                List<String> headerTokens = parseHeaderTokens(headerPart);

                // Collect body lines until closing }
                int bodyStart = i + 1;
                int bodyEnd = bodyStart;
                int depth = 1;
                while (bodyEnd < lines.size() && depth > 0) {
                    String bodyStripped = lines.get(bodyEnd).strip();
                    if (bodyStripped.endsWith("{")) depth++;
                    if (bodyStripped.equals("}") || bodyStripped.startsWith("}")) depth--;
                    if (depth > 0) bodyEnd++;
                    else break;
                }

                List<PropertyAst> properties = new ArrayList<>();
                Map<String, String> extensions = new LinkedHashMap<>();
                for (int j = bodyStart; j < bodyEnd; j++) {
                    String bodyLine = lines.get(j).strip();
                    if (bodyLine.isEmpty() || bodyLine.startsWith("#")) continue;
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

                i = bodyEnd + 1; // skip past closing }
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

    private String firstToken(String line) {
        if (line.isEmpty()) return "";
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
     * Parse a single property line in v2 format: {@code key: "value";} or {@code key: 0.92;}.
     *
     * <p>The colon after the key and the trailing semicolon are stripped.
     */
    private PropertyAst parseProperty(String line, String fileName, int lineNumber) {
        // Strip trailing semicolon
        if (line.endsWith(";")) {
            line = line.substring(0, line.length() - 1).stripTrailing();
        }

        // Split on first colon
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) {
            // Fallback: try space-separated (for tolerance)
            return parseSpaceSeparatedProperty(line, fileName, lineNumber);
        }

        String key = line.substring(0, colonIdx).strip();
        String rest = line.substring(colonIdx + 1).strip();

        String value;
        Matcher m = QUOTED_VALUE.matcher(rest);
        if (m.find()) {
            value = unescapeQuotedValue(m.group(1));
        } else {
            value = rest;
        }

        return new PropertyAst(key, value, new SourceLocation(fileName, lineNumber, 1));
    }

    /**
     * Fallback parser for space-separated properties (tolerance for edge cases).
     */
    private PropertyAst parseSpaceSeparatedProperty(String line, String fileName, int lineNumber) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            return new PropertyAst(line, "", new SourceLocation(fileName, lineNumber, 1));
        }
        String key = line.substring(0, firstSpace);
        String rest = line.substring(firstSpace).strip();
        Matcher m = QUOTED_VALUE.matcher(rest);
        String value;
        if (m.find()) {
            value = unescapeQuotedValue(m.group(1));
        } else {
            value = rest;
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

    /**
     * Unescape a quoted string value: {@code \"} → {@code "}, {@code \\} → {@code \}.
     */
    private String unescapeQuotedValue(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
