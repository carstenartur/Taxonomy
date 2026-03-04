package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.AnalysisRequest;
import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.GeminiService;
import com.nato.taxonomy.service.TaxonomyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final TaxonomyService taxonomyService;
    private final GeminiService geminiService;

    public ApiController(TaxonomyService taxonomyService, GeminiService geminiService) {
        this.taxonomyService = taxonomyService;
        this.geminiService = geminiService;
    }

    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        if (request.getBusinessText() == null || request.getBusinessText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Integer> scores = geminiService.analyzeRecursive(request.getBusinessText());

        List<TaxonomyNodeDto> rawTree = taxonomyService.getFullTree();
        List<TaxonomyNodeDto> annotatedTree = new ArrayList<>();
        for (TaxonomyNodeDto root : rawTree) {
            annotatedTree.add(taxonomyService.applyScores(root, scores));
        }

        return ResponseEntity.ok(new AnalysisResult(scores, annotatedTree));
    }
}
