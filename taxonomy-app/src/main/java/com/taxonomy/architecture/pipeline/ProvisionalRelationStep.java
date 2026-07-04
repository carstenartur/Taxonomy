package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.RelationOrigin;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Injects provisional (AI-suggested) relation hypotheses as virtual edges
 * when no confirmed relations exist.
 *
 * <p>When the relationship list is empty but provisional relations are available,
 * this step adds the hypothesis endpoints to the element list (if not already present)
 * and creates corresponding {@link RequirementRelationshipView} entries marked as
 * {@code provisional (AI-suggested, not yet confirmed)}.
 *
 * <p>This ensures the architecture view is immediately useful even before any
 * confirmed taxonomy relations have been saved.
 */
@Service
public class ProvisionalRelationStep {

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyService taxonomyService;

    public ProvisionalRelationStep(TaxonomyNodeRepository nodeRepository,
                                   TaxonomyService taxonomyService) {
        this.nodeRepository = nodeRepository;
        this.taxonomyService = taxonomyService;
    }

    public void execute(ArchitectureViewContext ctx) {
        List<RequirementRelationshipView> relationships = ctx.getRelationships();
        List<RelationHypothesisDto> provisionalRelations = ctx.getProvisionalRelations();

        if (!relationships.isEmpty()
                || provisionalRelations == null
                || provisionalRelations.isEmpty()) {
            return;
        }

        List<RequirementElementView> elements = ctx.getElements();
        Set<String> includedCodes = elements.stream()
                .map(RequirementElementView::getNodeCode)
                .collect(Collectors.toSet());

        for (RelationHypothesisDto hyp : provisionalRelations) {
            ensureElement(elements, includedCodes, hyp.getSourceCode(), hyp.getSourceName(),
                    ctx.getScores(), ctx);
            ensureElement(elements, includedCodes, hyp.getTargetCode(), hyp.getTargetName(),
                    ctx.getScores(), ctx);

            RequirementRelationshipView rv = new RequirementRelationshipView();
            rv.setSourceCode(hyp.getSourceCode());
            rv.setTargetCode(hyp.getTargetCode());
            rv.setRelationType(hyp.getRelationType());
            rv.setPropagatedRelevance(hyp.getConfidence());
            rv.setHopDistance(0);
            rv.setIncludedBecause("provisional (AI-suggested, not yet confirmed)");
            relationships.add(rv);
        }

        ctx.setUsedProvisional(true);
    }

    private void ensureElement(List<RequirementElementView> elements,
                                Set<String> includedCodes,
                                String nodeCode, String nodeName,
                                java.util.Map<String, Integer> scores,
                                ArchitectureViewContext ctx) {
        if (includedCodes.contains(nodeCode)) {
            return;
        }
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(nodeCode);
        element.setRelevance(scores.getOrDefault(nodeCode, 0) / 100.0);
        element.setHopDistance(0);
        element.setAnchor(false);
        element.setIncludedBecause("provisional relation endpoint");
        element.setOrigin(NodeOrigin.SEED_CONTEXT);
        element.setDirectLlmScore(scores.getOrDefault(nodeCode, 0));
        element.setTitle(nodeName);

        Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
        if (nodeOpt.isPresent()) {
            TaxonomyNode node = nodeOpt.get();
            if (element.getTitle() == null) {
                element.setTitle(node.getNameEn());
            }
            element.setTaxonomySheet(node.getTaxonomyRoot());
        }

        element.setHierarchyPath(ctx.buildHierarchyPath(nodeCode, taxonomyService));

        elements.add(element);
        includedCodes.add(nodeCode);
    }
}
