package com.taxonomy.controller;

import com.taxonomy.dto.ArchitectureSummary;
import com.taxonomy.dto.NodeGraphMetadata;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.service.ArchitectureSummaryService;
import com.taxonomy.service.DerivedMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for Architecture Summary and Derived Metadata.
 *
 * <p>Provides:
 * <ul>
 *     <li>Architecture summary with next-step guidance</li>
 *     <li>Node-level graph metadata (relation counts, roles)</li>
 *     <li>Trigger for metadata recomputation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/architecture")
@Tag(name = "Architecture Intelligence")
public class ArchitectureSummaryApiController {

    private final ArchitectureSummaryService summaryService;
    private final DerivedMetadataService metadataService;
    private final TaxonomyNodeRepository nodeRepository;

    public ArchitectureSummaryApiController(ArchitectureSummaryService summaryService,
                                            DerivedMetadataService metadataService,
                                            TaxonomyNodeRepository nodeRepository) {
        this.summaryService = summaryService;
        this.metadataService = metadataService;
        this.nodeRepository = nodeRepository;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get architecture summary with next steps",
            description = "Returns key findings (top capabilities, processes, services, hubs, gaps) " +
                    "and recommended next actions based on the current architecture state.")
    public ResponseEntity<ArchitectureSummary> getSummary() {
        return ResponseEntity.ok(summaryService.buildSummary());
    }

    @PostMapping("/metadata/recompute")
    @Operation(summary = "Recompute derived graph metadata",
            description = "Recalculates relation counts, requirement coverage, and graph roles " +
                    "for all taxonomy nodes. This should be called after bulk data changes.")
    public ResponseEntity<Map<String, Object>> recomputeMetadata() {
        int updated = metadataService.recomputeAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedNodes", updated);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/metadata/{nodeCode}")
    @Operation(summary = "Get derived metadata for a specific node",
            description = "Returns relation counts, requirement coverage count, and graph role " +
                    "for the given node code.")
    public ResponseEntity<?> getNodeMetadata(@PathVariable String nodeCode) {
        return nodeRepository.findByCode(nodeCode)
                .map(node -> {
                    NodeGraphMetadata metadata = new NodeGraphMetadata(
                            node.getCode(),
                            node.getIncomingRelationCount(),
                            node.getOutgoingRelationCount(),
                            node.getTotalRelationCount(),
                            node.getRequirementCoverageCount(),
                            node.getGraphRole()
                    );
                    return ResponseEntity.ok((Object) metadata);
                })
                .orElseGet(() -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "Node not found: " + nodeCode);
                    return ResponseEntity.notFound().build();
                });
    }
}
