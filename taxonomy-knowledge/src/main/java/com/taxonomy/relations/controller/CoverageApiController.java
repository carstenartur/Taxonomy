package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.CoverageSourceUnavailable;
import com.taxonomy.relations.service.RequirementCoverageService;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import com.taxonomy.identity.StableIdentityHash;
import java.util.Objects;
import java.util.function.BiFunction;
import com.taxonomy.workspace.service.RepositoryContext;
import static com.taxonomy.relations.service.CoverageSourceUnavailable.State.*;

/** User-recorded coverage is owned by the repository/workspace, branch and Git revision observed at record time. */
@RestController
@RequestMapping("/api/coverage")
@Tag(name = "Requirement Coverage")
public class CoverageApiController {
    private final RequirementCoverageService coverage;
    private final WorkspaceResolver workspaces;
    private final WorkspaceDslReadPort git;

    private final ObjectMapper json;

    public CoverageApiController(RequirementCoverageService coverage, WorkspaceResolver workspaces, WorkspaceDslReadPort git, ObjectMapper json) {
        this.json = json;
        this.coverage = coverage;
        this.workspaces = workspaces;
        this.git = git;
    }

    public record RecordCoverageRequest(String requirementId, String requirementText, Map<String, Double> scores, int minScore) {}

    @Operation(summary = "Record requirement coverage at the selected workspace revision")
    @PostMapping("/record")
    public ResponseEntity<?> recordCoverage(@RequestBody RecordCoverageRequest request, @RequestParam String commitId) {
        if (request.requirementId() == null || request.requirementId().isBlank() || request.requirementId().length() > 255
                || request.requirementText() != null && request.requirementText().length() > 2000) return ResponseEntity.badRequest().build();
        if (request.scores() != null && request.scores().entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 255 ||
                entry.getValue() != null && !com.taxonomy.dto.AnalysisPercentages.valid(entry.getValue()))) {
            return ResponseEntity.badRequest().build();
        }
        return atSource(commitId, "record", true, (context, head) -> {
            coverage.analyzeCoverage(context, head, Objects.requireNonNullElse(request.scores(), Map.of()),
                    request.requirementId(), request.requirementText(), request.minScore());
            return null;
        });
    }

    @GetMapping("/node/{code}")
    public ResponseEntity<?> forNode(@PathVariable String code, @RequestParam(required = false) String commitId) {
        return atSource(commitId, "node/" + code, false, (context, head) -> coverage.getCoverageForNode(context, head, code));
    }

    @GetMapping("/requirement/{id}")
    public ResponseEntity<?> forRequirement(@PathVariable String id, @RequestParam(required = false) String commitId) {
        return atSource(commitId, "requirement/" + id, false, (context, head) -> coverage.getCoverageForRequirement(context, head, id));
    }

    @GetMapping("/statistics")
    public ResponseEntity<?> statistics(@RequestParam(required = false) String commitId) {
        return atSource(commitId, "statistics", false, coverage::getCoverageStatistics);
    }

    @GetMapping("/density")
    public ResponseEntity<?> density(@RequestParam(required = false) String commitId) {
        return atSource(commitId, "density", false, coverage::getRequirementDensityMap);
    }

    @DeleteMapping("/requirement/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, @RequestParam String commitId) {
        var response = atSource(commitId, "delete", true, (context, head) -> {
            coverage.deleteCoverageForRequirement(context, head, id);
            return null;
        });
        return response.getStatusCode().is2xxSuccessful()
                ? ResponseEntity.noContent().headers(response.getHeaders()).build() : response;
    }

    private ResponseEntity<?> atSource(String pin, String view, boolean write, BiFunction<RepositoryContext, String, Object> operation) {
        var context = workspaces.resolveUnambiguousRepositoryForRead();
        if (write && context.scope() != RepositoryScope.WORKSPACE) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String expected;
        try { expected = pin == null ? null : WorkspaceDslReadPort.normalizeCommitId(pin); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().build(); }
        try {
            var read = git.openRead(context);
            String head = read.currentHead();
            if (head == null) throw new CoverageSourceUnavailable(MISSING, null);
            if (expected != null && !expected.equals(head)) throw new CoverageSourceUnavailable(STALE, head);
            var result = operation.apply(context, head);
            if (!write) read.verifyExpectedHead(head);
            var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("X-Taxonomy-Source-State", "READY").header("X-Taxonomy-Commit", head)
                    .header("X-Taxonomy-Repository", context.repositoryId()).header("X-Taxonomy-Workspace-Id", Objects.toString(context.workspaceId(), ""))
                    .header("X-Taxonomy-Branch", context.branch());
            if (!write) {
                var identity = List.of("requirement-coverage-v1", context.repositoryId(), Objects.toString(context.workspaceId(), ""),
                        context.branch(), head, view, result);
                response.eTag("W/\"" + StableIdentityHash.sha256(json.writeValueAsString(identity)) + "\"");
            }
            return response.body(result);
        } catch (BranchHeadConflictException moved) {
            throw new CoverageSourceUnavailable(STALE, moved.getActualHeadCommit());
        } catch (IOException failure) {
            throw new CoverageSourceUnavailable(FAILED, null);
        }
    }

}
