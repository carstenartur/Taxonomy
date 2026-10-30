package com.taxonomy.composition.architecture;

import com.taxonomy.composition.architecture.ArchitectureLifecycleService.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;

@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
@RequestMapping("/api/architecture/lifecycle")
public class ArchitectureLifecycleController {
    private final ArchitectureLifecycleService service; private final LifecycleHttpContext contexts;
    public ArchitectureLifecycleController(ArchitectureLifecycleService service,LifecycleHttpContext contexts){this.service=service;this.contexts=contexts;}
    @GetMapping
    @Operation(summary="Read requirement realization at the exact selected architecture stand",description="Historical commits and semantic revisions are read-only and never overlaid with current portfolio records.")
    public ResponseEntity<Overview> overview(@RequestParam(required=false) String repositoryId,@RequestParam(required=false) String workspaceScopeKey,
            @RequestParam(required=false) String branch,@RequestParam(required=false) String commit,@RequestParam(required=false) Long revision) throws IOException {
        var result=service.overview(contexts.resolve(repositoryId,workspaceScopeKey,branch),commit,revision);
        if(!LifecycleHttpContext.mayEdit()) result=result.readOnly();
        return LifecycleHttpContext.response(result.context()).header("X-Taxonomy-Source",result.source()).body(result);
    }
    @PostMapping("/realizations/preview")
    @Operation(summary="Preview an exact requirement realization review",description="Checks the requirement text version, contribution project and selected architecture dependencies without writing.")
    public ResponseEntity<ReviewPreview> preview(@RequestBody Request request,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) throws IOException {
        LifecycleHttpContext.requireRevision(request.context(),ifMatch,ifNoneMatch);
        var result=service.preview(contexts.resolve(),request);return LifecycleHttpContext.response(result.context()).body(result);
    }
    @PostMapping("/realizations")
    @Operation(summary="Accept a reviewed requirement realization and checkpoint it",description="Requires the exact preview fingerprint and revision; retries retain the original command identity and Git publication state.")
    public ResponseEntity<Publication> accept(@RequestBody Request request,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) throws IOException {
        LifecycleHttpContext.requireRevision(request.context(),ifMatch,ifNoneMatch);
        var result=service.accept(contexts.resolve(),request);return LifecycleHttpContext.response(result.context()).body(result);
    }
}
