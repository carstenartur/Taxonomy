package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application use case for interactive, one-level taxonomy analysis. */
@Service
public class AnalyzeNodeChildrenUseCase {

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;

    public AnalyzeNodeChildrenUseCase(TaxonomyService taxonomyService, LlmService llmService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
    }

    public AnalyzeNodeChildrenResult analyze(AnalyzeNodeChildrenCommand command) {
        List<TaxonomyNode> children = taxonomyService.getChildrenOf(command.parentCode());
        if (children.isEmpty()) {
            return AnalyzeNodeChildrenResult.empty();
        }
        LlmCallDetail detail = llmService.analyzeSingleBatchDetailed(
                command.businessText(), children, command.parentScore());
        return new AnalyzeNodeChildrenResult(
                detail.getScores(),
                detail.getReasons(),
                detail.getPrompt(),
                detail.getRawResponse(),
                detail.getProvider(),
                detail.getDurationMs(),
                detail.getError());
    }
}
