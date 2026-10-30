package com.taxonomy.composition.architecture;

import com.taxonomy.versioning.service.RequirementHistoryService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;

@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
public class ArchitectureRequirementHistoryController {
    private final RequirementHistoryService history;private final ArchitectureLifecycleService lifecycle;private final LifecycleHttpContext contexts;
    public ArchitectureRequirementHistoryController(RequirementHistoryService history,ArchitectureLifecycleService lifecycle,LifecycleHttpContext contexts){this.history=history;this.lifecycle=lifecycle;this.contexts=contexts;}
    @GetMapping("/api/architecture/lifecycle/history")
    @Operation(summary="Search authoritative requirement and realization history with JGit",description="Walks actual reachable commit ancestry at a captured HEAD. Bounded pages report coverage, actual parents and signature evidence; a moved HEAD requires restarting the search.")
    public ResponseEntity<RequirementHistoryService.Page> history(@RequestParam(required=false) String repositoryId,
            @RequestParam(required=false) String workspaceScopeKey,@RequestParam(required=false) String branch,
            @RequestParam(required=false) String projectKey,@RequestParam(required=false) String requirementKey,
            @RequestParam(required=false) String text,@RequestParam(required=false) String expectedHead,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="100") int scanLimit) throws IOException {
        var context=contexts.resolve(repositoryId,workspaceScopeKey,branch);lifecycle.authorize(context,false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.search(context,
                new RequirementHistoryService.Query(projectKey,requirementKey,text,expectedHead,offset,limit,scanLimit)));
    }
}
