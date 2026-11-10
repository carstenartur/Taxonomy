package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;
import java.util.Map;

/** Unavailable evidence fails closed, never as an empty successful assessment. */
@RestControllerAdvice(assignableTypes = {GapAnalysisApiController.class, RecommendationApiController.class, PatternDetectionApiController.class})
public class ObligationApiErrors {
    @ExceptionHandler({IllegalStateException.class, BranchHeadConflictException.class})
    public ResponseEntity<Map<String,String>> conflict(Exception error) {
        return ResponseEntity.status(409).body(Map.of("status", "UNAVAILABLE", "message", "Selected model evidence is unavailable or changed; refresh the selected revision."));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String,String>> unavailable(IOException error) {
        return ResponseEntity.status(503).body(Map.of("status", "UNAVAILABLE", "message", "Selected model evidence could not be read."));
    }
}
