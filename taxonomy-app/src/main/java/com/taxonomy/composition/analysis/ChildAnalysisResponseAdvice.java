package com.taxonomy.composition.analysis;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preserves the exact receipt body and headers on the synchronous, typed-result routes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChildAnalysisResponseAdvice {
    @ExceptionHandler(ScopedAnalysisChildOperations.ReceiptStatus.class)
    ResponseEntity<?> receipt(ScopedAnalysisChildOperations.ReceiptStatus status) {
        return status.receipt;
    }
}
