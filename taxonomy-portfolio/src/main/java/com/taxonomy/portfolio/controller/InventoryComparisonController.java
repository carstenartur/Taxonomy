package com.taxonomy.portfolio.controller;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Mapping;
import com.taxonomy.portfolio.inventory.InventoryComparisonService;
import com.taxonomy.portfolio.inventory.InventoryComparisonService.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Existing authenticated API and CSRF/security boundary applies to all mutations. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(com.taxonomy.extension.api.inventory.InventoryEvidencePort.class)
@RestController
@RequestMapping("/api")
public class InventoryComparisonController {
    private final InventoryComparisonService service;private final WorkspaceResolver workspaces;
    public InventoryComparisonController(InventoryComparisonService service,WorkspaceResolver workspaces) {this.service=service;this.workspaces=workspaces;}
    @PostMapping("/projects/{projectId}/snapshots/{snapshotId}/inventory-comparisons")
    public Comparison create(@PathVariable Long projectId,@PathVariable String snapshotId,@RequestBody CreateComparisonRequest request) {return service.compare(projectId,snapshotId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/projects/{projectId}/snapshots/{snapshotId}/inventory-comparisons")
    public List<Comparison> list(@PathVariable Long projectId,@PathVariable String snapshotId) {return service.list(projectId,snapshotId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/inventory-comparisons/{comparisonId}")
    public Comparison read(@PathVariable UUID comparisonId) {return service.read(comparisonId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @GetMapping("/inventory-comparisons/{comparisonId}/reviews")
    public List<Reviewed> reviews(@PathVariable UUID comparisonId) {return service.reviews(comparisonId,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/inventory-comparisons/{comparisonId}/reviews")
    public Reviewed review(@PathVariable UUID comparisonId,@RequestBody Review request) {return service.review(comparisonId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/inventory-comparisons/{comparisonId}/candidates/{candidateId}/mappings")
    public Mapping map(@PathVariable UUID comparisonId,@PathVariable String candidateId,@RequestBody MapCandidateRequest request) {return service.map(comparisonId,candidateId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @PostMapping("/products/{productId}/inventory-assertions")
    public Assertion assertEvidence(@PathVariable Long productId,@RequestBody Assertion request) {return service.appendAssertion(productId,request,workspaces.resolveCurrentUsername(),workspaces.resolveCurrentContext());}
    @ExceptionHandler(com.taxonomy.extension.api.inventory.InventoryEvidencePort.Failure.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> failure(com.taxonomy.extension.api.inventory.InventoryEvidencePort.Failure failure) {
        var problem=org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),failure.getMessage());problem.setTitle("Inventory evidence operation failed");problem.setProperty("code",failure.code());return org.springframework.http.ResponseEntity.status(failure.status()).body(problem);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> invalid(IllegalArgumentException failure) {
        return org.springframework.http.ResponseEntity.badRequest().body(org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid inventory comparison metadata or interval"));
    }

}
