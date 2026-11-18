package com.taxonomy.security.controller;

import com.taxonomy.security.service.VersionedRepositoryRefPolicy;
import com.taxonomy.workspace.model.RepositoryRefRule;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/ref-policy")
@ConditionalOnProperty(prefix = "taxonomy.features.multi-repository-api", name = "enabled", havingValue = "true")
public class RepositoryRefPolicyController {
    private final VersionedRepositoryRefPolicy policies;

    public RepositoryRefPolicyController(VersionedRepositoryRefPolicy policies) { this.policies = policies; }

    public record Request(Long expectedRevision, Boolean preventUpdates, Boolean preventForce,
                          Boolean preventDelete, Boolean adminOverride, String rationale) { }

    @GetMapping
    @Operation(summary = "Read the selected branch protection policy",
            description = "Returns the revision and update, force-update and deletion protections for the exact branch in an accessible active repository. Supply workspaceId to inspect an accessible working copy; otherwise the central repository is selected. Unconfigured branches return revision zero with all protection flags disabled.")
    public ResponseEntity<RepositoryRefRule.View> read(@PathVariable String repositoryId,
            @RequestParam(required = false) String workspaceId, @RequestParam String branch, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(policies.inspect(authentication, repositoryId, workspaceId, branch));
    }

    @PutMapping
    @Operation(summary = "Change the exact branch protection policy",
            description = "The owner supplies the displayed policy revision, three explicit protection flags and a rationale. An explicitly requested administrator override is audited and retains private working-copy visibility and active-repository checks. The policy applies atomically to native ref writes, force updates and deletion in the selected physical repository.")
    public ResponseEntity<VersionedRepositoryRefPolicy.Change> update(@PathVariable String repositoryId,
            @RequestParam(required = false) String workspaceId, @RequestParam String branch,
            @RequestBody Request request, Authentication authentication) {
        if (request.expectedRevision() == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
        if (request.preventUpdates() == null || request.preventForce() == null || request.preventDelete() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explicit protection flags are required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.change(authentication,
                repositoryId, workspaceId, branch, request.expectedRevision(), request.preventUpdates(),
                request.preventForce(), request.preventDelete(), Boolean.TRUE.equals(request.adminOverride()), request.rationale()));
    }
}
