package com.taxonomy.architecture.pipeline;

import com.taxonomy.architecture.service.ImpactEndpointSelector;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates concrete impact relations between leaf nodes from different taxonomy
 * categories, and emits derived impact relations as {@link RelationHypothesisDto}
 * entries so they flow through the Accept/Reject UI.
 *
 * <p>For each trace (root-to-root) relation, this step finds the most qualified
 * leaf nodes in each endpoint's category and creates a derived impact relation
 * between them. This ensures the final architecture view prioritises meaningful
 * cross-category connections over abstract root-level propagation relations.
 *
 * <p>After generating impact relations, the full relationship list is ranked so
 * that cross-category leaf-to-leaf relations appear before root-level ones.
 */
@Service
public class ImpactRelationStep {

    private static final Logger log = LoggerFactory.getLogger(ImpactRelationStep.class);

    private final TaxonomyNodeRepository nodeRepository;

    public ImpactRelationStep(TaxonomyNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    public void execute(ArchitectureViewContext ctx) {
        generateImpactRelations(ctx.getElements(), ctx.getRelationships());
        emitImpactHypotheses(ctx.getRelationships(), ctx.getProvisionalRelations());
    }

    private void generateImpactRelations(List<RequirementElementView> elements,
                                          List<RequirementRelationshipView> relationships) {
        // Group leaf elements by their taxonomy root
        Map<String, List<RequirementElementView>> leafByRoot = new java.util.LinkedHashMap<>();
        for (RequirementElementView el : elements) {
            String code = el.getNodeCode();
            if (!code.contains("-")) continue;
            String root = code.substring(0, code.indexOf('-'));
            leafByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(el);
        }

        List<RequirementRelationshipView> impactRelations = new ArrayList<>();
        Set<String> impactSignatures = new LinkedHashSet<>();
        ImpactEndpointSelector endpointSelector = new ImpactEndpointSelector();

        for (RequirementRelationshipView trace : relationships) {
            String srcRoot = rootOf(trace.getSourceCode());
            String tgtRoot = rootOf(trace.getTargetCode());
            if (srcRoot == null || tgtRoot == null) continue;
            if (srcRoot.equals(tgtRoot)) continue;

            List<RequirementElementView> srcLeaves = leafByRoot.getOrDefault(srcRoot, List.of());
            List<RequirementElementView> tgtLeaves = leafByRoot.getOrDefault(tgtRoot, List.of());
            if (srcLeaves.isEmpty() || tgtLeaves.isEmpty()) continue;

            List<RequirementElementView> srcEndpoints = endpointSelector.selectEndpoints(srcLeaves);
            List<RequirementElementView> tgtEndpoints = endpointSelector.selectEndpoints(tgtLeaves);

            for (RequirementElementView src : srcEndpoints) {
                for (RequirementElementView tgt : tgtEndpoints) {
                    String sig = src.getNodeCode() + "->" + tgt.getNodeCode()
                            + ":" + trace.getRelationType();
                    if (!impactSignatures.add(sig)) continue;

                    RequirementRelationshipView impact = new RequirementRelationshipView();
                    impact.setSourceCode(src.getNodeCode());
                    impact.setTargetCode(tgt.getNodeCode());
                    impact.setRelationType(trace.getRelationType());
                    impact.setPropagatedRelevance(
                            Math.min(src.getRelevance(), tgt.getRelevance()));
                    impact.setHopDistance(0);
                    impact.setIncludedBecause("impact: " + src.getNodeCode() + " → "
                            + tgt.getNodeCode() + " (derived from " + trace.getSourceCode()
                            + " → " + trace.getTargetCode() + ")");
                    impact.setOrigin(RelationOrigin.IMPACT_DERIVED);
                    impact.setConfidence(Math.min(src.getRelevance(), tgt.getRelevance()));
                    impact.setDerivationReason("Cross-category leaf-to-leaf: "
                            + src.getNodeCode() + " → " + tgt.getNodeCode());
                    impactRelations.add(impact);
                }
            }
        }

        if (!impactRelations.isEmpty()) {
            relationships.addAll(impactRelations);
            log.info("Generated {} impact relation(s) from {} trace relation(s)",
                    impactRelations.size(), relationships.size() - impactRelations.size());
        }

        rankRelationships(relationships);
    }

    /**
     * Emits derived impact relations as {@link RelationHypothesisDto} entries into
     * the provisional relations list, so they flow through the existing Accept/Reject
     * UI and can be evaluated by the LLM.
     *
     * <p>Only impact-category relations that don't already exist as confirmed or
     * provisional relations are emitted.
     */
    private void emitImpactHypotheses(List<RequirementRelationshipView> relationships,
                                       List<RelationHypothesisDto> provisionalRelations) {
        if (provisionalRelations == null) return;

        Set<String> existingSignatures = new LinkedHashSet<>();
        for (RelationHypothesisDto h : provisionalRelations) {
            existingSignatures.add(h.getSourceCode() + "->" + h.getTargetCode()
                    + ":" + h.getRelationType());
        }

        for (RequirementRelationshipView rel : relationships) {
            if (!RequirementRelationshipView.CATEGORY_IMPACT.equals(rel.getRelationCategory())) {
                continue;
            }
            String sig = rel.getSourceCode() + "->" + rel.getTargetCode()
                    + ":" + rel.getRelationType();
            if (!existingSignatures.add(sig)) continue;

            String srcTitle = nodeRepository.findByCode(rel.getSourceCode())
                    .map(TaxonomyNode::getNameEn).orElse(rel.getSourceCode());
            String tgtTitle = nodeRepository.findByCode(rel.getTargetCode())
                    .map(TaxonomyNode::getNameEn).orElse(rel.getTargetCode());

            RelationHypothesisDto hyp = new RelationHypothesisDto(
                    rel.getSourceCode(), srcTitle,
                    rel.getTargetCode(), tgtTitle,
                    rel.getRelationType(),
                    rel.getPropagatedRelevance(),
                    rel.getIncludedBecause());
            provisionalRelations.add(hyp);
        }
    }

    /**
     * Sorts relationships by priority:
     * <ol>
     *   <li>Cross-category leaf-to-leaf impact relations</li>
     *   <li>Same-category leaf-to-leaf relations</li>
     *   <li>Relations involving at least one leaf node</li>
     *   <li>Root-level propagation relations (non-seed)</li>
     *   <li>Seed-origin root-to-root relations (structural context)</li>
     * </ol>
     * Within each tier, relations are sorted by confidence/relevance descending.
     */
    private static void rankRelationships(List<RequirementRelationshipView> relationships) {
        relationships.sort(Comparator
                .comparingInt(ImpactRelationStep::relationPriority)
                .thenComparing(Comparator.comparingDouble(RequirementRelationshipView::getConfidence).reversed())
                .thenComparing(Comparator.comparingDouble(RequirementRelationshipView::getPropagatedRelevance).reversed()));
    }

    private static int relationPriority(RequirementRelationshipView rel) {
        boolean srcIsLeaf = rel.getSourceCode() != null && rel.getSourceCode().contains("-");
        boolean tgtIsLeaf = rel.getTargetCode() != null && rel.getTargetCode().contains("-");
        boolean isCrossCategory = !Objects.equals(rootOf(rel.getSourceCode()), rootOf(rel.getTargetCode()));
        boolean isSeed = RequirementRelationshipView.CATEGORY_SEED.equals(rel.getRelationCategory());

        if (srcIsLeaf && tgtIsLeaf && isCrossCategory) return 1;
        if (srcIsLeaf && tgtIsLeaf) return 2;
        if (srcIsLeaf || tgtIsLeaf) return 3;
        if (isSeed) return 5;
        return 4;
    }

    /**
     * Returns the taxonomy root code for a node code.
     * For leaf codes like "CP-1023" returns "CP"; for root codes like "CP" returns "CP".
     */
    static String rootOf(String code) {
        if (code == null) return null;
        int dash = code.indexOf('-');
        return dash >= 0 ? code.substring(0, dash) : code;
    }
}
