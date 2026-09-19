package com.taxonomy.architecture.service;

import com.taxonomy.architecture.pipeline.ArchitectureViewContext;
import com.taxonomy.architecture.pipeline.ArchitectureViewPipeline;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementArchitectureView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link RequirementArchitectureView} from analysis scores and
 * persisted taxonomy relations.
 *
 * <p>This class is the stable public facade for the architecture-view pipeline.
 * All construction logic is delegated to {@link ArchitectureViewPipeline} and
 * the step classes in {@code com.taxonomy.architecture.pipeline}.
 */
@Service
public class RequirementArchitectureViewService {

    private final ArchitectureViewPipeline pipeline;

    public RequirementArchitectureViewService(ArchitectureViewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Builds the architecture view from analysis scores (without provisional relations).
     *
     * @param scores               map of nodeCode → integer score (0–100) from the LLM analysis
     * @param businessText         the original business requirement text (for notes)
     * @param maxArchitectureNodes maximum number of elements to include (0 = no limit)
     * @return the architecture view, or an empty view with a note if no anchors are found
     */
    @Transactional(readOnly = true)
    public RequirementArchitectureView build(Map<String, Integer> scores, String businessText,
                                             int maxArchitectureNodes) {
        return build(scores, businessText, maxArchitectureNodes, null);
    }

    /**
     * Builds the architecture view from analysis scores, optionally using
     * provisional relation hypotheses as virtual edges for relevance propagation
     * when no confirmed relations exist.
     *
     * @param scores               map of nodeCode → integer score (0–100) from the LLM analysis
     * @param businessText         the original business requirement text (for notes)
     * @param maxArchitectureNodes maximum number of elements to include (0 = no limit)
     * @param provisionalRelations optional list of AI-suggested relation hypotheses
     * @return the architecture view, or an empty view with a note if no anchors are found
     */
    @Transactional(readOnly = true)
    public RequirementArchitectureView build(Map<String, Integer> scores, String businessText,
                                             int maxArchitectureNodes,
                                             List<RelationHypothesisDto> provisionalRelations) {
        if (scores == null || scores.isEmpty()) {
            RequirementArchitectureView view = new RequirementArchitectureView();
            view.getNotes().add("No scores available; architecture view cannot be built.");
            return view;
        }

        ArchitectureViewContext ctx = new ArchitectureViewContext(
                scores, businessText, maxArchitectureNodes, provisionalRelations);
        return pipeline.execute(ctx);
    }
}
