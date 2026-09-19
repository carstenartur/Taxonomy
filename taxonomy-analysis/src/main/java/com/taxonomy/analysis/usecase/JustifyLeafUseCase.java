package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application use case for explaining one leaf-node match. */
@Service
public class JustifyLeafUseCase {

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;

    public JustifyLeafUseCase(TaxonomyService taxonomyService, LlmService llmService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
    }

    public JustifyLeafResult justify(JustifyLeafCommand command) {
        List<TaxonomyNode> path = taxonomyService.getPathToRoot(command.nodeCode());
        String justification = llmService.generateLeafJustification(
                command.businessText(),
                command.nodeCode(),
                path,
                command.scores(),
                command.reasons());
        return new JustifyLeafResult(command.nodeCode(), justification);
    }
}
