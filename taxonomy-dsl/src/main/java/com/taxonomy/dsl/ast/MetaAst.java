package com.taxonomy.dsl.ast;

/**
 * The {@code meta} block at the top of a DSL document.
 *
 * <p>Carries language identification, version, and namespace so that
 * the parser can detect version incompatibilities early.
 */
public record MetaAst(String language, String version, String namespace, SourceLocation sourceLocation) {

    /** Default language identifier. */
    public static final String LANGUAGE_ID = "taxdsl";
    /** Current DSL version. */
    public static final String CURRENT_VERSION = "2.1";
}
