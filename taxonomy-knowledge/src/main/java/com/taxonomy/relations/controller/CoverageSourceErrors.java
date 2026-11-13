package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.CoverageSourceUnavailable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CoverageSourceErrors {
    @ExceptionHandler(CoverageSourceUnavailable.class)
    ResponseEntity<?> unavailable(CoverageSourceUnavailable error) {
        var response = ResponseEntity.status(switch (error.state()) {
            case MISSING -> HttpStatus.NOT_FOUND;
            case STALE -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        }).cacheControl(CacheControl.noStore()).header("X-Taxonomy-Source-State", error.state().name());
        if (error.head() != null) response.header("X-Taxonomy-Commit", error.head());
        return response.build();
    }
}
