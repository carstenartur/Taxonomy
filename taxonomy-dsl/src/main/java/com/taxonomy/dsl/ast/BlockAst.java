package com.taxonomy.dsl.ast;

import java.util.*;

/**
 * A generic block in the DSL AST.
 *
 * <p>Every top-level construct ({@code element}, {@code relation}, {@code requirement},
 * {@code mapping}, {@code view}, {@code evidence}, and future block types) is first
 * parsed into a {@code BlockAst}. The {@link #kind} field carries the keyword
 * (e.g. {@code "element"}), while {@link #headerTokens} contains the remaining
 * tokens on the header line (e.g. {@code ["CP-1023", "type", "Capability"]}).
 *
 * <p>Unknown block types and unknown properties are preserved so that
 * round-trips through parse → serialize are lossless.
 */
public final class BlockAst {

    private final String kind;
    private final List<String> headerTokens;
    private final List<PropertyAst> properties;
    private final List<BlockAst> children;
    private final Map<String, String> extensions;
    private final SourceLocation sourceLocation;

    public BlockAst(String kind,
                    List<String> headerTokens,
                    List<PropertyAst> properties,
                    List<BlockAst> children,
                    Map<String, String> extensions,
                    SourceLocation sourceLocation) {
        this.kind = Objects.requireNonNull(kind);
        this.headerTokens = List.copyOf(headerTokens);
        this.properties = List.copyOf(properties);
        this.children = List.copyOf(children);
        this.extensions = Map.copyOf(extensions);
        this.sourceLocation = sourceLocation;
    }

    public String getKind() { return kind; }
    public List<String> getHeaderTokens() { return headerTokens; }
    public List<PropertyAst> getProperties() { return properties; }
    public List<BlockAst> getChildren() { return children; }
    public Map<String, String> getExtensions() { return extensions; }
    public SourceLocation getSourceLocation() { return sourceLocation; }

    /** Convenience: look up a property value by key, or {@code null}. */
    public String property(String key) {
        return properties.stream()
                .filter(p -> p.key().equals(key))
                .map(PropertyAst::value)
                .findFirst()
                .orElse(null);
    }

    /** Convenience: look up all property values for a given key (for repeated keys like {@code include}). */
    public List<String> propertyValues(String key) {
        return properties.stream()
                .filter(p -> p.key().equals(key))
                .map(PropertyAst::value)
                .toList();
    }
}
