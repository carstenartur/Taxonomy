package com.taxonomy.architecture.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementElementView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Selects all nodes that form the hierarchical scoring path for each anchor,
 * so the UI can present a transparent trace from root through intermediates to
 * the directly-scored leaf.
 *
 * <p>For every anchor the full path (root → intermediate → anchor) is
 * reconstructed using {@link TaxonomyService#getPathToRoot(String)}. Each
 * intermediate node is marked as {@link NodeOrigin#TRACE_INTERMEDIATE} and
 * every directly-scored node is marked as {@link NodeOrigin#DIRECT_SCORED}.
 */
@Service
public class ScoringTraceSelector {

    private static final Logger log = LoggerFactory.getLogger(ScoringTraceSelector.class);

    private final TaxonomyService taxonomyService;

    public ScoringTraceSelector(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    /**
     * Builds scoring trace entries for every anchor node.
     *
     * @param allScores map of nodeCode → integer score (0–100)
     * @param anchors   the anchor nodes selected for the architecture view
     * @return list of trace entries with fully-populated origin and scoring path
     */
    public List<RequirementElementView> buildTrace(Map<String, Integer> allScores,
                                                    List<RequirementAnchor> anchors) {
        Map<String, RequirementElementView> traceMap = new LinkedHashMap<>();

        for (RequirementAnchor anchor : anchors) {
            String anchorCode = anchor.getNodeCode();
            List<TaxonomyNode> pathToRoot = taxonomyService.getPathToRoot(anchorCode);

            if (pathToRoot.isEmpty()) {
                // No path found — add anchor node directly
                addOrUpdate(traceMap, anchorCode, allScores, NodeOrigin.DIRECT_SCORED, null);
                continue;
            }

            // pathToRoot is ordered root → leaf; build the scoring path string
            StringBuilder scoringPath = new StringBuilder();
            for (int i = 0; i < pathToRoot.size(); i++) {
                TaxonomyNode pathNode = pathToRoot.get(i);
                String code = pathNode.getCode();
                int score = allScores.getOrDefault(code, 0);

                if (i > 0) scoringPath.append(" > ");
                scoringPath.append(code).append("(").append(score).append("%)");

                boolean isAnchorNode = code.equals(anchorCode);
                NodeOrigin origin = isAnchorNode ? NodeOrigin.DIRECT_SCORED
                                                 : NodeOrigin.TRACE_INTERMEDIATE;

                RequirementElementView entry = addOrUpdate(traceMap, code, allScores, origin, pathNode);
                entry.setScoringPath(scoringPath.toString());
                entry.setTaxonomyDepth(pathNode.getLevel());
            }
        }

        List<RequirementElementView> result = new ArrayList<>(traceMap.values());
        log.debug("Built scoring trace with {} entries for {} anchors", result.size(), anchors.size());
        return result;
    }

    private RequirementElementView addOrUpdate(Map<String, RequirementElementView> traceMap,
                                                String code, Map<String, Integer> allScores,
                                                NodeOrigin origin, TaxonomyNode node) {
        RequirementElementView existing = traceMap.get(code);
        if (existing != null) {
            // Upgrade origin: DIRECT_SCORED takes priority over TRACE_INTERMEDIATE
            if (origin == NodeOrigin.DIRECT_SCORED
                    && existing.getOrigin() == NodeOrigin.TRACE_INTERMEDIATE) {
                existing.setOrigin(NodeOrigin.DIRECT_SCORED);
            }
            return existing;
        }

        int score = allScores.getOrDefault(code, 0);

        RequirementElementView entry = new RequirementElementView();
        entry.setNodeCode(code);
        entry.setOrigin(origin);
        entry.setDirectLlmScore(score);
        entry.setRelevance(score / 100.0);
        entry.setAnchor(origin == NodeOrigin.DIRECT_SCORED);

        if (node != null) {
            entry.setTitle(node.getNameEn());
            entry.setTaxonomySheet(node.getTaxonomyRoot());
            entry.setTaxonomyDepth(node.getLevel());
        }

        traceMap.put(code, entry);
        return entry;
    }
}
