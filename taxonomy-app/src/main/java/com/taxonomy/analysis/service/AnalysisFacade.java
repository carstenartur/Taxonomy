package com.taxonomy.analysis.service;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.SavedAnalysis;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Facade that aggregates the analysis domain services into a single high-level API.
 *
 * <p>Controllers should prefer this facade over calling individual services directly,
 * keeping transaction boundaries and orchestration logic in one place.
 */
@Service
public class AnalysisFacade {

    private final LlmService llmService;
    private final SavedAnalysisService savedAnalysisService;
    private final AnalysisRelationGenerator analysisRelationGenerator;

    public AnalysisFacade(@Lazy LlmService llmService,
                          SavedAnalysisService savedAnalysisService,
                          AnalysisRelationGenerator analysisRelationGenerator) {
        this.llmService = llmService;
        this.savedAnalysisService = savedAnalysisService;
        this.analysisRelationGenerator = analysisRelationGenerator;
    }

    /**
     * Builds a {@link SavedAnalysis} export from the given analysis data.
     *
     * @param requirement the business requirement text
     * @param scores      node code → score map
     * @param reasons     node code → reason text (may be null or sparse)
     * @param provider    LLM provider name
     * @return populated {@link SavedAnalysis} ready for serialization
     */
    public SavedAnalysis buildAnalysisExport(String requirement,
                                             Map<String, Integer> scores,
                                             Map<String, String> reasons,
                                             String provider) {
        return savedAnalysisService.buildExport(requirement, scores, reasons, provider);
    }

    /**
     * Generates provisional relation hypotheses from analysis scores.
     *
     * @param scores map of nodeCode → integer score (0–100)
     * @return list of relation hypotheses sorted by confidence descending
     */
    @Transactional(readOnly = true)
    public List<RelationHypothesisDto> generateRelationsFromScores(Map<String, Integer> scores) {
        return analysisRelationGenerator.generate(scores);
    }
}
