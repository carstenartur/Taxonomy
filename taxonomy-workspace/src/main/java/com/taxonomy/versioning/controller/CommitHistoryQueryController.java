package com.taxonomy.versioning.controller;

import com.taxonomy.dto.VersionedSearchResult;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.ANCESTOR;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.SAME;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.UNAVAILABLE;

/** Repository/workspace history includes matches from all indexed branches in the selected Git store. */
@RestController
@RequestMapping("/api/dsl/history")
@Tag(name = "Architecture DSL")
public class CommitHistoryQueryController {
    private final CommitIndexService index;
    private final WorkspaceResolver workspaces;
    private final WorkspaceDslReadPort git;
    private final ObjectMapper json;

    public CommitHistoryQueryController(CommitIndexService index, WorkspaceResolver workspaces,
                                        WorkspaceDslReadPort git, ObjectMapper json) {
        this.index = index;
        this.workspaces = workspaces;
        this.git = git;
        this.json = json;
    }

    @Operation(summary = "Search indexed history in the selected repository/workspace")
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String query, @RequestParam(defaultValue = "50") int maxResults) {
        var selected = workspaces.resolveUnambiguousRepositoryForRead();
        return observed(selected, "search", List.of(query, maxResults), null, index.search(query, maxResults, selected));
    }

    @Operation(summary = "Find indexed commits affecting an element")
    @GetMapping("/element/{elementId}")
    public ResponseEntity<?> element(@PathVariable String elementId) {
        var selected = workspaces.resolveUnambiguousRepositoryForRead();
        return observed(selected, "element", elementId, null, index.findByElement(elementId, selected));
    }

    @Operation(summary = "Find indexed commits affecting a relation")
    @GetMapping("/relation")
    public ResponseEntity<?> relation(@RequestParam String key) {
        var selected = workspaces.resolveUnambiguousRepositoryForRead();
        return observed(selected, "relation", key, null, index.findByRelation(key, selected));
    }

    @Operation(summary = "Aggregate the indexed history of an element in the selected repository/workspace")
    @GetMapping("/element/{elementId}/aggregation")
    public ResponseEntity<?> aggregate(@PathVariable String elementId) {
        var selected = workspaces.resolveUnambiguousRepositoryForRead();
        var result = index.aggregateElementHistory(elementId, selected);
        Object body = result != null ? result : Map.of("elementId", elementId, "message", "No history found for element " + elementId);
        return observed(selected, "aggregation", elementId, null, body);
    }

    @Operation(summary = "Search indexed history and prove lineage against the selected Git head",
            description = "Matches span the selected repository/workspace. Optional currentBranch and commitId assert the current selection. The source commit and ETag identify the versioned observation.")
    @GetMapping("/search-versioned")
    public ResponseEntity<?> versioned(@RequestParam String query, @RequestParam(defaultValue = "50") int maxResults,
                                       @RequestParam(required = false) String currentBranch,
                                       @RequestParam(required = false) String commitId) {
        var selected = workspaces.resolveUnambiguousRepositoryForRead();
        if (currentBranch != null && !currentBranch.equals(selected.branch())) {
            return unavailable(HttpStatus.CONFLICT, "STALE", null);
        }
        String expected;
        try {
            expected = commitId == null ? null : WorkspaceDslReadPort.normalizeCommitId(commitId);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        try {
            var read = git.openRead(selected);
            String head = read.currentHead();
            if (head == null) return unavailable(HttpStatus.NOT_FOUND, "MISSING", null);
            if (expected != null && !expected.equals(head)) return unavailable(HttpStatus.CONFLICT, "STALE", head);
            var hits = index.search(query, maxResults, selected);
            var relationships = read.relationshipsTo(head, hits.stream().map(ArchitectureCommitIndex::getCommitId).toList());
            if (relationships.contains(UNAVAILABLE)) return unavailable(HttpStatus.CONFLICT, "STALE", head);
            var rows = versionedRows(hits, relationships);
            read.verifyExpectedHead(head);
            return observed(selected, "versioned", List.of(query, maxResults), head, rows);
        } catch (BranchHeadConflictException moved) {
            return unavailable(HttpStatus.CONFLICT, "STALE", moved.getActualHeadCommit());
        } catch (IOException failure) {
            return unavailable(HttpStatus.SERVICE_UNAVAILABLE, "FAILED", null);
        }
    }

    private static List<VersionedSearchResult> versionedRows(List<ArchitectureCommitIndex> hits,
            List<WorkspaceDslReadPort.CommitRelationship> relationships) {
        var newest = Comparator.comparing(ArchitectureCommitIndex::getCommitTimestamp)
                .thenComparing(ArchitectureCommitIndex::getCommitId);
        var latest = hits.stream().filter(hit -> hit.getCommitTimestamp() != null).max(newest).orElse(null);
        ArchitectureCommitIndex latestOnBranch = null;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            if (onLineage(relationships.get(i)) && hit.getCommitTimestamp() != null
                    && (latestOnBranch == null || newest.compare(hit, latestOnBranch) > 0)) latestOnBranch = hit;
        }
        var results = new ArrayList<VersionedSearchResult>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            boolean onLineage = onLineage(relationships.get(i));
            boolean latestOnLineage = latestOnBranch != null && latestOnBranch.getCommitId().equals(hit.getCommitId());
            boolean latestOverall = latest != null && latest.getCommitId().equals(hit.getCommitId());
            var actions = latestOnLineage ? List.of("OPEN_READ_ONLY", "CREATE_VARIANT", "COMPARE")
                    : List.of("OPEN_READ_ONLY", "SWITCH", "CREATE_VARIANT", "COMPARE");
            results.add(new VersionedSearchResult(hit.getCommitId(), hit.getBranch(), hit.getCommitTimestamp(),
                    hit.getAffectedElementIds(), hit.getMessage(), 0.0f, onLineage, latestOnLineage, latestOverall, actions));
        }
        return List.copyOf(results);
    }

    private static boolean onLineage(WorkspaceDslReadPort.CommitRelationship relationship) {
        return relationship == SAME || relationship == ANCESTOR;
    }

    private ResponseEntity<?> observed(RepositoryContext selected, String route, Object parameters, String head, Object body) {
        var source = List.of(selected.repositoryId(), Objects.toString(selected.workspaceId(), ""), selected.branch());
        var identity = List.of("commit-history-v1", source, route, parameters, Objects.toString(head, ""), body);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("W/\"" + StableIdentityHash.sha256(json.writeValueAsString(identity)) + "\"")
                .header("X-Taxonomy-Read-Model", "REPOSITORY_WORKSPACE_COMMIT_INDEX")
                .header("X-Taxonomy-Repository", selected.repositoryId())
                .header("X-Taxonomy-Workspace-Id", Objects.toString(selected.workspaceId(), ""))
                .header("X-Taxonomy-Branch", selected.branch());
        if (head != null) response.header("X-Taxonomy-Commit", head).header("X-Taxonomy-Source-State", "READY");
        return response.body(body);
    }

    private static ResponseEntity<?> unavailable(HttpStatus status, String state, String head) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Taxonomy-Source-State", state);
        if (head != null) response.header("X-Taxonomy-Commit", head);
        return response.build();
    }
}
