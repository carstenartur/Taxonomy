package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.ChangeImpactView;
import com.nato.taxonomy.dto.GraphNeighborhoodView;
import com.nato.taxonomy.dto.RequirementImpactView;
import com.nato.taxonomy.service.ArchitectureGraphQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for graph-based architecture queries.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/graph/impact} — Requirement impact analysis</li>
 *   <li>{@code GET  /api/graph/node/{code}/upstream} — Upstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/downstream} — Downstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/failure-impact} — Failure/change impact</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/graph")
public class GraphQueryApiController {

    private final ArchitectureGraphQueryService graphQueryService;

    public GraphQueryApiController(ArchitectureGraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /**
     * Requirement impact: which elements are affected by a business requirement?
     *
     * <p>Request body must contain {@code scores} (map of nodeCode → score 0–100),
     * {@code businessText}, and optionally {@code maxHops} (default 2).
     */
    @PostMapping("/impact")
    public ResponseEntity<RequirementImpactView> findImpact(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawScores = (Map<String, Object>) body.get("scores");
        String businessText = (String) body.get("businessText");
        int maxHops = body.containsKey("maxHops")
                ? ((Number) body.get("maxHops")).intValue()
                : 2;

        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        if (rawScores != null) {
            for (Map.Entry<String, Object> entry : rawScores.entrySet()) {
                scores.put(entry.getKey(), ((Number) entry.getValue()).intValue());
            }
        }

        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                scores, businessText, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Upstream neighborhood: what feeds into this element?
     */
    @GetMapping("/node/{code}/upstream")
    public ResponseEntity<GraphNeighborhoodView> findUpstream(
            @PathVariable String code,
            @RequestParam(defaultValue = "2") int maxHops) {
        GraphNeighborhoodView view = graphQueryService.findUpstream(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Downstream neighborhood: what depends on this element?
     */
    @GetMapping("/node/{code}/downstream")
    public ResponseEntity<GraphNeighborhoodView> findDownstream(
            @PathVariable String code,
            @RequestParam(defaultValue = "2") int maxHops) {
        GraphNeighborhoodView view = graphQueryService.findDownstream(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Failure/change impact: what is affected if this element fails or changes?
     */
    @GetMapping("/node/{code}/failure-impact")
    public ResponseEntity<ChangeImpactView> findFailureImpact(
            @PathVariable String code,
            @RequestParam(defaultValue = "3") int maxHops) {
        ChangeImpactView view = graphQueryService.findFailureImpact(code, maxHops);
        return ResponseEntity.ok(view);
    }
}
