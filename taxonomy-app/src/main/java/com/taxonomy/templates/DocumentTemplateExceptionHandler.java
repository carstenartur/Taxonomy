package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/** Maps template API failures without exposing storage internals. */
@RestControllerAdvice(assignableTypes = DocumentTemplateAdminController.class)
public class DocumentTemplateExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentTemplateExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    ResponseEntity<Map<String, String>> missing(TemplateNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TemplateConflictException.class)
    ResponseEntity<Map<String, String>> conflict(TemplateConflictException exception) {
        return error(HttpStatus.PRECONDITION_FAILED, exception.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> storage(IOException exception) {
        log.warn("Document template API operation failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Template operation failed");
    }

    private static ResponseEntity<Map<String, String>> error(
            HttpStatus status,
            String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
