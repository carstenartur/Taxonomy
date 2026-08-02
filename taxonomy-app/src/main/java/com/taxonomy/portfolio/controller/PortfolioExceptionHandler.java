package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.PortfolioException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** RFC 9457 responses for the project portfolio API. */
@RestControllerAdvice(basePackages = "com.taxonomy.portfolio")
public class PortfolioExceptionHandler {

    @ExceptionHandler(PortfolioException.class)
    public ResponseEntity<ProblemDetail> handlePortfolioException(PortfolioException exception) {
        HttpStatus status = switch (exception.getKind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case ANALYSIS_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(switch (exception.getKind()) {
            case NOT_FOUND -> "Portfolio resource not found";
            case CONFLICT -> "Portfolio state conflict";
            case VALIDATION -> "Invalid portfolio request";
            case ANALYSIS_FAILED -> "Portfolio analysis payload failure";
        });
        problem.setType(URI.create("urn:taxonomy:portfolio:" + exception.getKind().name().toLowerCase()));
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            DataIntegrityViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The requested portfolio change violates a uniqueness or reference constraint.");
        problem.setTitle("Portfolio constraint conflict");
        problem.setType(URI.create("urn:taxonomy:portfolio:constraint-conflict"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
