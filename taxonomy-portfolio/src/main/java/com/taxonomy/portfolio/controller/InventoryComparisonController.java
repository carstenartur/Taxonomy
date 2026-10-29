package com.taxonomy.portfolio.controller;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Mapping;
import com.taxonomy.portfolio.inventory.InventoryComparisonService;
import com.taxonomy.portfolio.inventory.InventoryComparisonService.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Existing authenticated API and CSRF/security boundary applies to all mutations. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.taxonomy.extension.api.inventory.InventoryEvidencePort.class)
@RestController
@RequestMapping("/api")
@Tag(name = "Inventory comparison")
public class InventoryComparisonController {
    private final InventoryComparisonService service;private final WorkspaceResolver workspaces;
    public InventoryComparisonController(InventoryComparisonService service,WorkspaceResolver workspaces) {this.service=service;this.workspaces=workspaces;}
    @PostMapping("/projects/{projectId}/snapshots/{snapshotId}/inventory-comparisons")
    @Operation(summary = "Compare inventory against selected nodes from a saved analysis",
            description = "Retains a comparison for the exact saved demand, selected catalogue nodes and explicit time question in the authenticated workspace. Reads only explicitly selected authorized source profiles; the saved analysis is not rerun or rescored.")
    public Comparison create(@Parameter(description = "Project containing the saved analysis") @PathVariable Long projectId,
            @Parameter(description = "Exact saved analysis snapshot identifier") @PathVariable String snapshotId,
            @RequestBody CreateComparisonRequest request) {return service.compare(projectId,snapshotId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/projects/{projectId}/snapshots/{snapshotId}/inventory-comparisons")
    @Operation(summary = "List retained inventory comparisons for a saved analysis",
            description = "Lists locally retained comparisons visible in the authenticated project and snapshot scope. Rechecks source grants without refreshing external sources or creating new observations.")
    public List<Comparison> list(@Parameter(description = "Project containing the saved analysis") @PathVariable Long projectId,
            @Parameter(description = "Exact saved analysis snapshot identifier") @PathVariable String snapshotId) {return service.list(projectId,snapshotId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/inventory-comparisons/{comparisonId}")
    @Operation(summary = "Read a retained inventory comparison and its evidence",
            description = "Reads the frozen demand, source evidence and criterion findings after checking the current workspace, project and source grants. This read does not refresh vendor data or alter the comparison.")
    public Comparison read(@Parameter(description = "Retained inventory comparison identifier") @PathVariable UUID comparisonId) {return service.read(comparisonId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/inventory-comparisons/{comparisonId}/reviews")
    @Operation(summary = "Read the retained review history of an inventory comparison",
            description = "Returns recorded reviews after checking access to the retained comparison. Review history is read locally and does not reapply a portfolio decision or query an external source.")
    public List<Reviewed> reviews(@Parameter(description = "Retained inventory comparison identifier") @PathVariable UUID comparisonId) {return service.reviews(comparisonId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/inventory-comparisons/{comparisonId}/reviews")
    @Operation(summary = "Record an explicit inventory review and optional portfolio application",
            description = "Records the supplied rationale and optionally applies an existing product candidate and requirement link using the retained comparison. Hard exclusions prevent selection and changed inputs require a new comparison. An identical review identifier and payload return the retained result without reapplication.")
    public Reviewed review(@Parameter(description = "Retained inventory comparison identifier") @PathVariable UUID comparisonId,
            @RequestBody Review request) {return service.review(comparisonId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/inventory-comparisons/{comparisonId}/candidates/{candidateId}/mappings")
    @Operation(summary = "Map a retained external candidate to an existing sourced product",
            description = "Records an explicit identity mapping with rationale and the current product fingerprint. Names alone do not establish identity. The mapping takes effect in a new comparison and does not select the product.")
    public Mapping map(@Parameter(description = "Retained inventory comparison identifier") @PathVariable UUID comparisonId,
            @Parameter(description = "Exact external candidate identity retained in the comparison") @PathVariable String candidateId,
            @RequestBody MapCandidateRequest request) {return service.map(comparisonId,candidateId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/products/{productId}/inventory-assertions")
    @Operation(summary = "Append a sourced inventory assertion for an existing product",
            description = "Retains an immutable, cited assertion with explicit criterion, organization, environment and date precision in the authenticated product scope. The assertion is evidence for a new comparison and does not rewrite an earlier comparison or selection.")
    public Assertion assertEvidence(@Parameter(description = "Existing product catalogue identifier in the current workspace") @PathVariable Long productId,
            @RequestBody Assertion request) {return service.appendAssertion(productId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @ExceptionHandler(com.taxonomy.extension.api.inventory.InventoryEvidencePort.Failure.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> failure(com.taxonomy.extension.api.inventory.InventoryEvidencePort.Failure failure) {
        var problem=org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),failure.getMessage());problem.setTitle("Inventory evidence operation failed");problem.setProperty("code",failure.code());return org.springframework.http.ResponseEntity.status(failure.status()).body(problem);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> invalid(IllegalArgumentException failure) {
        return org.springframework.http.ResponseEntity.badRequest().body(org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid inventory comparison metadata or interval"));
    }

}
