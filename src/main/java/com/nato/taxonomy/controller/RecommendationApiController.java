package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.ArchitectureRecommendation;
import com.nato.taxonomy.service.ArchitectureRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Architecture Recommendations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/recommend} — Generate architecture recommendation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recommend")
@Tag(name = "Architecture Recommendation")
public class RecommendationApiController {

    private final ArchitectureRecommendationService recommendationService;

    public RecommendationApiController(ArchitectureRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Request body for architecture recommendation.
     */
    public record RecommendationRequest(
            Map<String, Integer> scores,
            String businessText,
            int minScore) {
    }

    /**
     * Generates architecture recommendations for a business requirement.
     */
    @Operation(summary = "Architecture recommendation",
               description = "Produces architecture recommendations combining matches, gap analysis, and semantic search")
    @PostMapping
    public ResponseEntity<ArchitectureRecommendation> recommend(@RequestBody RecommendationRequest request) {
        ArchitectureRecommendation rec = recommendationService.recommend(
                request.scores() != null ? request.scores() : Map.of(),
                request.businessText(),
                request.minScore());
        return ResponseEntity.ok(rec);
    }
}
