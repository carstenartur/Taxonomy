package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.AnalysisRequest;
import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.dto.AiStatusResponse;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.LlmService;
import com.nato.taxonomy.service.SearchService;
import com.nato.taxonomy.service.TaxonomyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;
    private final SearchService searchService;

    public ApiController(TaxonomyService taxonomyService, LlmService llmService, SearchService searchService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.searchService = searchService;
    }

    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @GetMapping("/ai-status")
    public ResponseEntity<AiStatusResponse> aiStatus() {
        boolean available = llmService.isAvailable();
        String provider = available ? llmService.getActiveProviderName() : null;
        return ResponseEntity.ok(new AiStatusResponse(available, provider));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        if (request.getBusinessText() == null || request.getBusinessText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AnalysisResult result = llmService.analyzeWithBudget(request.getBusinessText());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int maxResults) {
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchService.search(q, maxResults));
    }
}
