package com.taxonomy.controller;

import com.taxonomy.dto.ApqcHierarchyNode;
import com.taxonomy.dto.ChangeImpactView;
import com.taxonomy.dto.EnrichedChangeImpactView;
import com.taxonomy.dto.GraphNeighborhoodView;
import com.taxonomy.dto.RequirementImpactView;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.model.ArchitectureDslDocument;
import com.taxonomy.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.service.ArchitectureGraphQueryService;
import com.taxonomy.service.EnrichedImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for graph-based architecture queries.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/graph/impact} — Requirement impact analysis</li>
 *   <li>{@code GET  /api/graph/node/{code}/upstream} — Upstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/downstream} — Downstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/failure-impact} — Failure/change impact</li>
 *   <li>{@code GET  /api/graph/node/{code}/enriched-failure-impact} — Enriched failure impact with requirement correlation</li>
 *   <li>{@code GET  /api/graph/apqc-hierarchy} — APQC process hierarchy from imported DSL</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph Queries")
public class GraphQueryApiController {

    private final ArchitectureGraphQueryService graphQueryService;
    private final EnrichedImpactService enrichedImpactService;
    private final ArchitectureDslDocumentRepository documentRepository;

    public GraphQueryApiController(ArchitectureGraphQueryService graphQueryService,
                                   EnrichedImpactService enrichedImpactService,
                                   ArchitectureDslDocumentRepository documentRepository) {
        this.graphQueryService = graphQueryService;
        this.enrichedImpactService = enrichedImpactService;
        this.documentRepository = documentRepository;
    }

    /**
     * Requirement impact: which elements are affected by a business requirement?
     *
     * <p>Request body must contain {@code scores} (map of nodeCode → score 0–100),
     * {@code businessText}, and optionally {@code maxHops} (default 2).
     */
    @Operation(summary = "Requirement impact analysis", description = "Determines which elements are affected by a business requirement")
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
    @Operation(summary = "Upstream neighbourhood", description = "Returns nodes that feed into this element")
    @GetMapping("/node/{code}/upstream")
    public ResponseEntity<GraphNeighborhoodView> findUpstream(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "2") int maxHops) {
        GraphNeighborhoodView view = graphQueryService.findUpstream(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Downstream neighborhood: what depends on this element?
     */
    @Operation(summary = "Downstream neighbourhood", description = "Returns nodes that depend on this element")
    @GetMapping("/node/{code}/downstream")
    public ResponseEntity<GraphNeighborhoodView> findDownstream(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "2") int maxHops) {
        GraphNeighborhoodView view = graphQueryService.findDownstream(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Failure/change impact: what is affected if this element fails or changes?
     */
    @Operation(summary = "Failure/change impact", description = "Returns what is affected if this element fails or changes")
    @GetMapping("/node/{code}/failure-impact")
    public ResponseEntity<ChangeImpactView> findFailureImpact(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "3") int maxHops) {
        ChangeImpactView view = graphQueryService.findFailureImpact(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Enriched failure/change impact: includes requirement correlation for each affected element.
     */
    @Operation(summary = "Enriched failure impact",
               description = "Returns failure impact enriched with requirement coverage data and risk score")
    @GetMapping("/node/{code}/enriched-failure-impact")
    public ResponseEntity<EnrichedChangeImpactView> findEnrichedFailureImpact(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "3") int maxHops) {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Returns the APQC process hierarchy extracted from imported DSL documents.
     *
     * <p>Scans all stored DSL documents for elements with the
     * {@code x-source-framework: "apqc"} extension and reconstructs the
     * parent–child hierarchy using {@code x-apqc-parent} chains.
     */
    @Operation(summary = "APQC hierarchy",
               description = "Returns APQC process elements from imported DSL documents as a hierarchy tree")
    @GetMapping("/apqc-hierarchy")
    public ResponseEntity<List<ApqcHierarchyNode>> apqcHierarchy() {
        TaxDslParser parser = new TaxDslParser();
        AstToModelMapper mapper = new AstToModelMapper();
        List<ArchitectureElement> apqcElements = new ArrayList<>();

        for (ArchitectureDslDocument doc : documentRepository.findAll()) {
            if (doc.getRawContent() == null || doc.getRawContent().isBlank()) continue;
            try {
                var ast = parser.parse(doc.getRawContent(), doc.getPath());
                CanonicalArchitectureModel model = mapper.map(ast);
                for (ArchitectureElement el : model.getElements()) {
                    if ("apqc".equals(el.getExtensions().get("x-source-framework"))) {
                        apqcElements.add(el);
                    }
                }
            } catch (Exception e) {
                // Skip unparseable documents
            }
        }

        // Build lookup map and tree
        Map<String, ApqcHierarchyNode> nodeMap = new LinkedHashMap<>();
        for (ArchitectureElement el : apqcElements) {
            Map<String, String> ext = el.getExtensions();
            String level = ext.getOrDefault("x-apqc-level", "Unknown");
            String pcfId = ext.getOrDefault("x-apqc-pcf-id", "");
            String parentId = ext.get("x-apqc-parent");
            String taxonomyRoot = TaxonomyRootTypes.rootFor(el.getType());
            nodeMap.put(el.getId(), new ApqcHierarchyNode(
                    el.getId(), el.getTitle(), level, pcfId, parentId,
                    taxonomyRoot != null ? taxonomyRoot : "",
                    new ArrayList<>()));
        }

        // Link children to parents
        List<ApqcHierarchyNode> roots = new ArrayList<>();
        for (ApqcHierarchyNode node : nodeMap.values()) {
            if (node.parentId() != null && nodeMap.containsKey(node.parentId())) {
                nodeMap.get(node.parentId()).children().add(node);
            } else {
                roots.add(node);
            }
        }

        return ResponseEntity.ok(roots);
    }
}
