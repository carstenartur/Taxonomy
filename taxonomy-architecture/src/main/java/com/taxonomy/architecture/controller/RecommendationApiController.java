package com.taxonomy.architecture.controller;

import com.taxonomy.dto.*;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/recommend")
public class RecommendationApiController {
    private final ArchitectureRecommendationService service;
    private final ObligationRequestContext contexts;
    public RecommendationApiController(ArchitectureRecommendationService service, ObligationRequestContext contexts) {
        this.service = service; this.contexts = contexts;
    }
    public record RecommendationRequest(String profileId,
            String profileVersion, EvidenceCompleteness evidenceCompleteness, Map<String,Double> scores, String businessText, int minScore) {}
    @PostMapping
    public ResponseEntity<ArchitectureRecommendation> recommend(@RequestBody RecommendationRequest request,
            @RequestParam String repositoryId, @RequestParam String branch, @RequestParam String commitId, HttpServletRequest http) throws IOException {
        var context = contexts.resolve(repositoryId, branch, commitId, http);
        if (request.evidenceCompleteness() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "evidenceCompleteness must be explicit");
        try {
            return ResponseEntity.ok(service.recommend(context, commitId, request.profileId(), request.profileVersion(),
                    request.evidenceCompleteness(), request.scores(), request.businessText(), request.minScore()));
        } catch (IllegalArgumentException error) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }
}
