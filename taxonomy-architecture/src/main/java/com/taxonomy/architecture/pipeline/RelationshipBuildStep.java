package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.SeedType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the list of {@link RequirementRelationshipView} instances from the
 * relevance-propagation traversal result.
 *
 * <p>Also contains the utility methods {@link #isSeedOriginRelation} and
 * {@link #parseSeedType}, which classify and tag each relation.
 *
 * <p>This step is a pure function with no Spring or repository dependencies.
 *
 * <p><b>Core invariant</b> — this step must run after element-build and before
 * provisional-relation. Do not disable it.
 */
@Service
public class RelationshipBuildStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "relationship-build";

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 500; }

    @Override
    public ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), true);
    }

    /**
     * Builds relationships from the propagation result and stores them in the context.
     */
    @Override
    public void apply(ArchitectureViewContext ctx) {
        ctx.setRelationships(build(ctx.getPropagation()));
    }

    /**
     * Builds the relationship list from a propagation result.
     * Package-private for unit testing.
     */
    List<RequirementRelationshipView> build(PropagationResult propagation) {
        List<RequirementRelationshipView> relationships = new ArrayList<>();
        Set<String> includedNodeCodes = propagation.getRelevanceMap().keySet();

        for (PropagationResult.TraversedRelation tr : propagation.getTraversedRelations()) {
            TaxonomyRelationDto rel = tr.getRelation();

            if (!includedNodeCodes.contains(rel.getSourceCode()) ||
                    !includedNodeCodes.contains(rel.getTargetCode())) {
                continue;
            }

            RequirementRelationshipView rv = new RequirementRelationshipView();
            rv.setRelationId(rel.getId());
            rv.setSourceCode(rel.getSourceCode());
            rv.setTargetCode(rel.getTargetCode());
            rv.setRelationType(rel.getRelationType());
            rv.setPropagatedRelevance(tr.getPropagatedRelevance());
            rv.setHopDistance(tr.getHopDistance());
            rv.setIncludedBecause(tr.getReason());
            rv.setConfidence(tr.getPropagatedRelevance());

            boolean isSeedRelation = isSeedOriginRelation(rel);
            if (isSeedRelation) {
                rv.setOrigin(RelationOrigin.TAXONOMY_SEED);
                rv.setSeedType(parseSeedType(rel.getProvenance()));
                rv.setDerivationReason("Seed relation: " + rel.getSourceCode()
                        + " → " + rel.getTargetCode());
            } else {
                rv.setOrigin(RelationOrigin.PROPAGATED_TRACE);
                rv.setDerivationReason("BFS propagation hop " + tr.getHopDistance());
            }
            relationships.add(rv);
        }

        // Deduplicate by relationId, keeping the entry with highest propagatedRelevance
        Map<Long, RequirementRelationshipView> deduped = new LinkedHashMap<>();
        for (RequirementRelationshipView rv : relationships) {
            Long key = rv.getRelationId();
            RequirementRelationshipView existing = deduped.get(key);
            if (existing == null || rv.getPropagatedRelevance() > existing.getPropagatedRelevance()) {
                deduped.put(key, rv);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    /**
     * Returns {@code true} if the underlying relation is a seed-origin relation.
     *
     * <p>Seed relations are characterised by both endpoints being root taxonomy codes
     * (two-letter codes without a hyphen, e.g. CP&nbsp;→&nbsp;CR).
     */
    public static boolean isSeedOriginRelation(TaxonomyRelationDto rel) {
        String src = rel.getSourceCode();
        String tgt = rel.getTargetCode();
        return src != null && tgt != null
                && !src.contains("-") && !tgt.contains("-");
    }

    /**
     * Parses a {@link SeedType} from the relation provenance string.
     * Falls back to {@link SeedType#TYPE_DEFAULT} when no specific type is recorded.
     */
    public static SeedType parseSeedType(String provenance) {
        if (provenance == null) return SeedType.TYPE_DEFAULT;
        String upper = provenance.toUpperCase(Locale.ROOT);
        if (upper.contains("FRAMEWORK")) return SeedType.FRAMEWORK_SEED;
        if (upper.contains("SOURCE_DERIVED") || upper.contains("DERIVED")) return SeedType.SOURCE_DERIVED;
        return SeedType.TYPE_DEFAULT;
    }
}
