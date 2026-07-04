package com.taxonomy.architecture.pipeline;

import com.taxonomy.architecture.service.LayerRepresentativeSelector;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.pipeline.PipelineConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enriches the architecture view with the top-scoring leaf nodes from the
 * analysis, so each taxonomy layer shows concrete named elements (e.g.
 * "CO-1023 Secure Messaging Service") rather than just abstract root codes.
 *
 * <p>For every taxonomy root already represented in the element list, the
 * step examines the full LLM score map and adds the highest-scoring leaf nodes
 * (codes with a dash, e.g. "CO-1063") that are not already included.
 * The number of additions per root is capped at
 * {@link PipelineConstants#MAX_LEAF_ENRICHMENT}.
 */
@Service
public class LeafEnrichmentStep {

    private static final Logger log = LoggerFactory.getLogger(LeafEnrichmentStep.class);

    private static final int MAX_LEAF_ENRICHMENT       = PipelineConstants.MAX_LEAF_ENRICHMENT;
    private static final int LEAF_ENRICHMENT_MIN_SCORE = PipelineConstants.LEAF_ENRICHMENT_MIN_SCORE;
    private static final int ANCHOR_THRESHOLD_HIGH     = PipelineConstants.ANCHOR_THRESHOLD_HIGH;

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyService taxonomyService;

    public LeafEnrichmentStep(TaxonomyNodeRepository nodeRepository,
                               TaxonomyService taxonomyService) {
        this.nodeRepository = nodeRepository;
        this.taxonomyService = taxonomyService;
    }

    public void execute(ArchitectureViewContext ctx) {
        List<RequirementElementView> elements = ctx.getElements();
        Map<String, Integer> scores = ctx.getScores();

        Set<String> includedCodes = elements.stream()
                .map(RequirementElementView::getNodeCode)
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> representedRoots = elements.stream()
                .map(RequirementElementView::getTaxonomySheet)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Group leaf-level scores by their taxonomy root
        Map<String, List<Map.Entry<String, Integer>>> leafScoresByRoot = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            String code = entry.getKey();
            int score = entry.getValue();
            if (score < LEAF_ENRICHMENT_MIN_SCORE) continue;
            if (includedCodes.contains(code)) continue;
            if (!code.contains("-")) continue;

            String rootPrefix = code.substring(0, code.indexOf('-'));
            if (!representedRoots.contains(rootPrefix)) continue;

            leafScoresByRoot.computeIfAbsent(rootPrefix, k -> new ArrayList<>()).add(entry);
        }

        // Pre-compute which root layers have deep-leaf candidates (depth > 1)
        Map<String, Boolean> rootHasDeepLeaf = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map.Entry<String, Integer>>> rootEntry : leafScoresByRoot.entrySet()) {
            String root = rootEntry.getKey();
            boolean hasDeep = rootEntry.getValue().stream()
                    .anyMatch(e -> {
                        Optional<TaxonomyNode> opt = nodeRepository.findByCode(e.getKey());
                        return opt.isPresent() && opt.get().getLevel() > 1;
                    });
            rootHasDeepLeaf.put(root, hasDeep);
        }

        LayerRepresentativeSelector layerSelector = new LayerRepresentativeSelector(nodeRepository);

        for (Map.Entry<String, List<Map.Entry<String, Integer>>> rootEntry : leafScoresByRoot.entrySet()) {
            String root = rootEntry.getKey();
            List<Map.Entry<String, Integer>> candidates = rootEntry.getValue();
            candidates.sort(
                    Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue)
                              .reversed());

            int rootScore = scores.getOrDefault(root, 0);
            boolean hasDeepLeaf = rootHasDeepLeaf.getOrDefault(root, false);

            int added = 0;
            for (Map.Entry<String, Integer> candidate : candidates) {
                if (added >= MAX_LEAF_ENRICHMENT) break;
                String leafCode = candidate.getKey();
                int leafScore = candidate.getValue();

                RequirementElementView element = new RequirementElementView();
                element.setNodeCode(leafCode);
                element.setRelevance(leafScore / 100.0);
                boolean isAnchor = leafScore >= ANCHOR_THRESHOLD_HIGH;
                element.setHopDistance(isAnchor ? 0 : 1);
                element.setAnchor(isAnchor);
                element.setIncludedBecause("leaf-enrichment: top-scoring in " + root);
                element.setOrigin(NodeOrigin.ENRICHED_LEAF);
                element.setDirectLlmScore(leafScore);

                Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(leafCode);
                if (nodeOpt.isPresent()) {
                    TaxonomyNode node = nodeOpt.get();
                    if (!layerSelector.shouldInclude(node, rootScore, hasDeepLeaf)) {
                        continue;
                    }
                    element.setTitle(node.getNameEn());
                    element.setTaxonomySheet(node.getTaxonomyRoot());
                    element.setTaxonomyDepth(node.getLevel());
                } else {
                    element.setTaxonomySheet(root);
                }

                element.setHierarchyPath(ctx.buildHierarchyPath(leafCode, taxonomyService));

                elements.add(element);
                includedCodes.add(leafCode);
                added++;
            }

            if (added > 0) {
                log.debug("Enriched layer {} with {} leaf node(s)", root, added);
            }
        }

        // Re-sort: anchors first, then by relevance descending
        elements.sort(Comparator
                .comparing(RequirementElementView::isAnchor).reversed()
                .thenComparing(Comparator.comparingDouble(RequirementElementView::getRelevance).reversed()));
    }
}
