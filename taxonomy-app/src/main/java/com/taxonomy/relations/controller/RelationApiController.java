package com.taxonomy.relations.controller;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadResult;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Relations")
public class RelationApiController {

    public static final String READ_MODEL_HEADER =
            "X-Taxonomy-Relation-Read-Model";
    public static final String PROJECTION_STATE_HEADER =
            "X-Taxonomy-Relation-Projection-State";
    public static final String PENDING_RECOVERY_HEADER =
            "X-Taxonomy-Relation-Pending-Recovery";

    private final RelationProjectionReadService relationReadService;
    private final WorkspaceResolver workspaceResolver;

    public RelationApiController(
            RelationProjectionReadService relationReadService,
            WorkspaceResolver workspaceResolver) {
        this.relationReadService = relationReadService;
        this.workspaceResolver = workspaceResolver;
    }

    @Operation(
            summary = "List relations",
            description = "Returns the complete relation projection for the selected repository/workspace, optionally filtered by type")
    @GetMapping("/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelations(
            @Parameter(description = "Filter by relation type")
            @RequestParam(required = false) String type) {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        try {
            ReadResult result;
            if (type != null && !type.isBlank()) {
                RelationType relationType;
                try {
                    relationType = RelationType.valueOf(
                            type.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    return ResponseEntity.badRequest().build();
                }
                result = relationReadService.readByType(context, relationType);
            } else {
                result = relationReadService.readAll(context);
            }
            return readResponse(result);
        } catch (RelationProjectionUnavailableException error) {
            return unavailable(error).build();
        }
    }

    @Operation(
            summary = "Get node relations",
            description = "Returns complete incoming and outgoing projected relations for one node in the selected repository/workspace")
    @GetMapping("/node/{code}/relations")
    public ResponseEntity<List<TaxonomyRelationDto>> getRelationsForNode(
            @PathVariable String code) {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        try {
            return readResponse(
                    relationReadService.readForNode(context, code));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (RelationProjectionUnavailableException error) {
            return unavailable(error).build();
        }
    }

    /**
     * The historic DB-first write route is deliberately retired. Git-authoritative
     * callers use PUT /api/architecture/relations/{source}/{type}/{target} with
     * If-Match/If-None-Match and Idempotency-Key.
     */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first create route",
            description = "Use the Git-authoritative architecture relation command endpoint")
    @PostMapping("/relations")
    public ResponseEntity<TaxonomyRelationDto> createRelation(
            @RequestBody(required = false) Map<String, String> ignored) {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }

    /**
     * Projection row IDs are rebuild-local and cannot be authoritative mutation
     * identities. Call the Git-authoritative identity-based DELETE endpoint.
     */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first delete route",
            description = "Use DELETE /api/architecture/relations/{source}/{type}/{target}")
    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }

    @Operation(
            summary = "Count relations",
            description = "Returns the size of the same complete relation projection used by list and node reads")
    @GetMapping("/relations/count")
    public ResponseEntity<Map<String, Long>> countRelations() {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        try {
            ReadResult result = relationReadService.readAll(context);
            ResponseEntity.BodyBuilder response = readHeaders(result);
            return response.body(Map.of(
                    "count", (long) result.relations().size()));
        } catch (RelationProjectionUnavailableException error) {
            return unavailable(error).build();
        }
    }

    private static ResponseEntity<List<TaxonomyRelationDto>> readResponse(
            ReadResult result) {
        return readHeaders(result).body(result.relations());
    }

    private static ResponseEntity.BodyBuilder readHeaders(ReadResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(READ_MODEL_HEADER, result.readModel().name())
                .header(PROJECTION_STATE_HEADER,
                        result.readinessState().name())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (result.authoritativeCommitId() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            result.authoritativeCommitId()));
        }
        return response;
    }

    private static ResponseEntity.BodyBuilder unavailable(
            RelationProjectionUnavailableException error) {
        HttpStatus status = error.getReadinessState()
                == ReadinessState.BRANCH_MISSING
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(PROJECTION_STATE_HEADER,
                        error.getReadinessState().name())
                .header(PENDING_RECOVERY_HEADER,
                        String.valueOf(error.getPendingRecoveryCount()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.getCurrentHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getCurrentHeadCommit()));
        }
        return response;
    }
}
