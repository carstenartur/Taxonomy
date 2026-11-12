package com.taxonomy.catalog.controller;

import com.taxonomy.catalog.service.CatalogueMappingReviewHistory;
import com.taxonomy.catalog.service.CatalogueMappingReviewService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.security.Principal;

@RestController
@RequestMapping("/api/admin/catalogue/provisional-information-products/{product}")
public class CatalogueMappingReviewController {
    private final CatalogueMappingReviewService reviews;
    private final CatalogueMappingReviewHistory history;
    public CatalogueMappingReviewController(CatalogueMappingReviewService reviews, CatalogueMappingReviewHistory history) {
        this.reviews = reviews;
        this.history = history;
    }
    @GetMapping("/reviews")
    @Operation(summary = "Read a product's versioned mapping review history",
            description = "Administrators can follow the commit cursor through prior decisions, including obsolete overlay versions.")
    public ResponseEntity<CatalogueMappingReviewHistory.Page> history(@PathVariable String product,
            @RequestParam(required = false) String after) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.page(product, after));
    }
    @PostMapping("/reviews")
    @Operation(summary = "Record an internal mapping review and prepare an overlay proposal",
            description = "Requires the expected review head, applied mapping version and rationale. A proposal is validated against the original workbook; promotion requires a reviewed Git change.")
    public ResponseEntity<CatalogueMappingReviewHistory.Entry> review(@PathVariable String product,
            @RequestBody CatalogueMappingReviewService.Command command, Principal principal) throws IOException {
        if (principal == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.review(product, command, principal.getName()));
    }
    @GetMapping("/reviews/{commit}/overlay")
    @Operation(summary = "Download the validated candidate overlay for a reviewed Git change",
            description = "The returned JSON is the immutable candidate from the selected product review. A rejected decision has no candidate.")
    public ResponseEntity<byte[]> overlay(@PathVariable String product, @PathVariable String commit) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalogue-overlay-proposal.json\"")
                .body(history.proposal(product, commit));
    }
    @ExceptionHandler(IOException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
    }
}
