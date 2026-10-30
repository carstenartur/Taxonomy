package com.taxonomy.composition.architecture;

import com.taxonomy.composition.architecture.ArchitectureRequirementService.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;

@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
public class ArchitectureRequirementController {
    private final ArchitectureRequirementService service; private final LifecycleHttpContext contexts;
    public ArchitectureRequirementController(ArchitectureRequirementService service,LifecycleHttpContext contexts){this.service=service;this.contexts=contexts;}
    @PostMapping("/api/architecture/editor/requirements")
    @Operation(summary="Apply an explicit requirement intake choice to the architecture",description="NEW, CHANGE or LINK retains the exact input hash, existing version precondition and stable command identity. Requirement, journal and checkpoint intent share one transaction; Git publication follows it.")
    public ResponseEntity<Saved> save(@RequestBody Request request,@RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @RequestHeader(value=HttpHeaders.IF_NONE_MATCH,required=false) String ifNoneMatch) throws IOException {
        LifecycleHttpContext.requireRevision(request.context(),ifMatch,ifNoneMatch);
        var result=service.save(contexts.resolve(),request);return LifecycleHttpContext.response(result.publication().context()).body(result);
    }
}
