package com.taxonomy.dsl.ast;

import java.util.List;
import java.util.Set;

/**
 * Semantic identity shared by source patches and three-way merges.
 * Named blocks use kind plus the first header token; mutable header attributes
 * (including element type) are values. Relations and association/version blocks
 * have no separate ID, so their complete composite header identifies them.
 * Unknown kinds follow the named-block convention and retain the rest of their header.
 */
public final class BlockIdentity {
    private static final Set<String> COMPOSITE_KINDS =
            Set.of("relation", "mapping", "projectRequirement", "requirementVersion",
                    "solutionTaxonomyCoverage", "productTaxonomyCoverage", "projectSolutionDecision",
                    "requirementSolutionDecision", "solutionProductDecision", "reformulationEvidence");

    private BlockIdentity() {}

    public static String key(BlockAst block) {
        List<String> tokens = block.getHeaderTokens();
        if (tokens.isEmpty()) throw new IllegalArgumentException("Block identity is missing");
        return block.getKind() + ":" + (COMPOSITE_KINDS.contains(block.getKind())
                ? String.join(" ", tokens) : tokens.getFirst());
    }
}
