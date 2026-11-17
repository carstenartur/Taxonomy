package com.taxonomy.architecture.controller;

import com.taxonomy.relations.controller.RelationApiController;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/** Unavailable evidence fails closed, never as an empty successful assessment. */
@RestControllerAdvice(assignableTypes = {GapAnalysisApiController.class, RecommendationApiController.class,
        PatternDetectionApiController.class, GraphQueryApiController.class})
public class ObligationApiErrors {
    private static final Map<String, String> UNAVAILABLE = Map.of("status", "UNAVAILABLE", "message",
            "Selected model evidence is unavailable. Refresh the selected source and its projection status.");

    @ExceptionHandler(RelationProjectionUnavailableException.class)
    public ResponseEntity<Map<String,String>> projection(RelationProjectionUnavailableException error) {
        ReadinessState projection = error.getReadinessState();
        String state = switch (projection) {
            case NOT_BUILT, BRANCH_MISSING -> "MISSING";
            case STALE -> "STALE";
            case CORRUPT, READY -> "FAILED";
        };
        var source = new ArchitectureQuerySource(error.getContext(), error.getCurrentHeadCommit());
        var response = source.headers(ResponseEntity.status(projection == ReadinessState.BRANCH_MISSING ? 404 : 409))
                .header("X-Taxonomy-Source-State", state)
                .header(RelationApiController.PROJECTION_STATE_HEADER, projection.name());
        if (error.getPendingRecoveryCount() > 0) {
            response.header(RelationApiController.PENDING_RECOVERY_HEADER, Long.toString(error.getPendingRecoveryCount()));
        }
        return response.body(UNAVAILABLE);
    }

    @ExceptionHandler(BranchHeadConflictException.class)
    public ResponseEntity<Map<String,String>> moved(BranchHeadConflictException error) {
        var head = error.getActualHeadCommit();
        var response = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Source-State", head == null ? "MISSING" : "STALE");
        if (head != null) response.header("X-Taxonomy-Commit", head);
        return response.body(UNAVAILABLE);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException error) {
        if (error.getCause() instanceof BranchHeadConflictException moved) return moved(moved);
        if (error.getCause() instanceof IOException failure) return unavailable(failure);
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Source-State", "FAILED").body(UNAVAILABLE);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String,String>> unavailable(IOException error) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Source-State", "FAILED").body(UNAVAILABLE);
    }
}
