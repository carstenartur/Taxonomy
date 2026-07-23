package com.taxonomy.provenance.controller;

import com.taxonomy.provenance.service.DocumentLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

/** Stable error mapping for multipart and document-processing resource limits. */
@RestControllerAdvice(assignableTypes = DocumentImportController.class)
public class DocumentImportExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> handleServletUploadLimit(
            MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(Map.of(
                "error", "UPLOAD_TOO_LARGE",
                "message", "The uploaded file exceeds the configured request limit"));
    }

    @ExceptionHandler(DocumentLimitException.class)
    ResponseEntity<Map<String, Object>> handleDocumentLimit(DocumentLimitException error) {
        int status = "UPLOAD_TOO_LARGE".equals(error.getCode()) ? 413
                : "EMPTY_FILE".equals(error.getCode()) ? 400 : 422;
        return ResponseEntity.status(status).body(Map.of(
                "error", error.getCode(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, Object>> handleMultipartFailure(MultipartException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_MULTIPART_REQUEST",
                "message", "The multipart request could not be processed"));
    }
}