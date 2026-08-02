package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionProductCandidateView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Product Catalogue")
public class ProductCatalogController {

    private final ProductCatalogService productService;
    private final WorkspaceResolver workspaceResolver;

    public ProductCatalogController(ProductCatalogService productService,
                                    WorkspaceResolver workspaceResolver) {
        this.productService = productService;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/products")
    @Operation(summary = "Create a sourced and dated product catalogue entry")
    public ResponseEntity<ProductView> create(@RequestBody CreateProductRequest request) {
        RequestScope scope = scope();
        ProductView product = productService.createProduct(
                request, scope.username(), scope.context());
        return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
    }

    @GetMapping("/products")
    @Operation(summary = "List sourced products in the current workspace")
    public List<ProductView> list() {
        RequestScope scope = scope();
        return productService.listProducts(scope.username(), scope.context());
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "Read one product including claim provenance")
    public ProductView get(@PathVariable Long productId) {
        RequestScope scope = scope();
        return productService.getProduct(productId, scope.username(), scope.context());
    }

    @PatchMapping("/products/{productId}")
    @Operation(summary = "Update a product claim and verification timestamp")
    public ProductView update(@PathVariable Long productId,
                              @RequestBody UpdateProductRequest request) {
        RequestScope scope = scope();
        return productService.updateProduct(
                productId, request, scope.username(), scope.context());
    }

    @PostMapping("/products/{productId}/taxonomy-coverage")
    @Operation(summary = "Create or update evidence-backed product taxonomy coverage")
    public ProductView upsertCoverage(
            @PathVariable Long productId,
            @RequestBody UpsertTaxonomyCoverageRequest request) {
        RequestScope scope = scope();
        return productService.upsertTaxonomyCoverage(
                productId, request, scope.username(), scope.context());
    }

    @PostMapping("/projects/{projectId}/solutions/{projectSolutionId}/products")
    @Operation(summary = "Add or review a product candidate for a project solution")
    public SolutionProductCandidateView upsertCandidate(
            @PathVariable Long projectId,
            @PathVariable Long projectSolutionId,
            @RequestBody UpsertProductCandidateRequest request) {
        RequestScope scope = scope();
        return productService.upsertCandidate(
                projectId, projectSolutionId, request, scope.username(), scope.context());
    }

    @GetMapping("/projects/{projectId}/solutions/{projectSolutionId}/products")
    @Operation(summary = "List product candidates for a project solution")
    public List<SolutionProductCandidateView> listCandidates(
            @PathVariable Long projectId,
            @PathVariable Long projectSolutionId) {
        RequestScope scope = scope();
        return productService.listCandidates(
                projectId, projectSolutionId, scope.username(), scope.context());
    }

    private RequestScope scope() {
        return new RequestScope(
                workspaceResolver.resolveCurrentUsername(),
                workspaceResolver.resolveCurrentContext());
    }

    private record RequestScope(String username, WorkspaceContext context) {
    }
}
