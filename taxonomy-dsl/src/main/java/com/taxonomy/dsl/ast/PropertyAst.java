package com.taxonomy.dsl.ast;

/**
 * A single property inside a DSL block, e.g. {@code title "Secure Communications"}.
 *
 * <p>Known properties are mapped to typed fields in the canonical model;
 * unknown properties (extension attributes starting with {@code x-}) are
 * preserved in the block's extension map so that round-trips are lossless.
 */
public record PropertyAst(String key, String value, SourceLocation sourceLocation) {

    /**
     * Returns {@code true} when this property is an extension attribute
     * (key starts with {@code x-}).
     */
    public boolean isExtension() {
        return key != null && key.startsWith("x-");
    }
}
