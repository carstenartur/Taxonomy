package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;

import com.taxonomy.dto.*;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/recommend")
public class RecommendationApiController {
    private final ArchitectureRecommendationService service;
    private final ExplicitRepositoryReadSelection contexts;
    public RecommendationApiController(ArchitectureRecommendationService service, ExplicitRepositoryReadSelection contexts) {
        this.service = service; this.contexts = contexts;
    }
    public record RecommendationRequest(String profileId,
            String profileVersion, EvidenceCompleteness evidenceCompleteness, Map<String,Double> scores, String businessText, int minScore) {}
    @Operation(summary = "Recommend architecture elements for a selected revision",
            description = "Evaluates an explicit versioned obligation profile against the authorized repository, workspace, branch and full commit. Requires the caller's evidence-completeness declaration and returns matched catalogue elements, accepted model elements and provisional proposals separately.")
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
